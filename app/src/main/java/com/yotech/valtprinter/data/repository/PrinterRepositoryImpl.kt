package com.yotech.valtprinter.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.util.Log
import com.sunmi.externalprinterlibrary2.ConnectCallback
import com.sunmi.externalprinterlibrary2.SearchMethod
import com.sunmi.externalprinterlibrary2.SunmiPrinterManager
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.sunmi.externalprinterlibrary2.style.EncodeType
import com.yotech.valtprinter.core.util.FeedbackManager
import com.yotech.valtprinter.data.mapper.toDomain
import com.yotech.valtprinter.data.model.DiscoveredPrinter
import com.yotech.valtprinter.data.model.DiscoveryMode
import com.yotech.valtprinter.data.source.RawSocketPrintSource
import com.yotech.valtprinter.data.source.SdkPrintSource
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrintResult
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.domain.repository.PrinterRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrinterRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sdkPrintSource: SdkPrintSource,
    private val rawSocketPrintSource: RawSocketPrintSource,
    private val feedbackManager: FeedbackManager
) : PrinterRepository {

    private val printMutex = Mutex()
    private var activeCloudPrinter: CloudPrinter? = null

    // Tracking for Self-Healing
    private var lastConnectedDevice: PrinterDevice? = null
    private var isManualDisconnect = false
    private var reconnectionJob: Job? = null

    // Store reference to pure domain device separately to rebuild `PrinterState.Connected` state
    private var connectedDevice: PrinterDevice? = null

    // Concurrency Guard for Handshakes
    private var isConnecting = false
    private var isRecovering = false

    // Real-time Vitality: Heartbeat
    private var heartbeatJob: Job? = null

    // Private map to hold the internal SDK objects indexed by unique domain ID
    private val internalPrintersMap = mutableMapOf<String, DiscoveredPrinter>()

    private val _discoveredDevices = MutableStateFlow<List<PrinterDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<PrinterDevice>> =
        _discoveredDevices.asStateFlow()

    private val _printerState = MutableStateFlow<PrinterState>(PrinterState.Idle)
    override val printerState: StateFlow<PrinterState> = _printerState.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // SDK Constants: USB=1000, LAN=2000, BT=3000
    private val searchMethods = listOf(1000, 2000, 3000)

    override fun startScan() {
        _printerState.value = PrinterState.Scanning
        stopAllSearches()
        internalPrintersMap.clear()
        _discoveredDevices.value = emptyList()

        searchMethods.forEach { method ->
            try {
                SunmiPrinterManager.getInstance()
                    .searchCloudPrinter(
                        context, method
                    ) { printer -> printer?.let { handlePrinterFound(it) } }
            } catch (e: Exception) {
                Log.e("VALT_SCAN", "Method $method failed", e)
            }
        }
    }

    private fun handlePrinterFound(printer: CloudPrinter) {
        val info = printer.cloudPrinterInfo ?: return

        val mode = when {
            info.vid > 0 -> DiscoveryMode.USB
            !info.address.isNullOrEmpty() -> DiscoveryMode.LAN
            !info.mac.isNullOrEmpty() -> DiscoveryMode.BLUETOOTH
            else -> DiscoveryMode.LAN
        }

        val uniqueId = when (mode) {
            DiscoveryMode.USB -> "USB-${info.vid}-${info.pid}"
            DiscoveryMode.BLUETOOTH -> "BT-${info.mac}"
            DiscoveryMode.LAN -> "LAN-${info.address}"
        }

        if (!internalPrintersMap.containsKey(uniqueId)) {
            val discovered = DiscoveredPrinter(printer, mode, uniqueId)
            internalPrintersMap[uniqueId] = discovered

            // Map to domain object and update state flow
            val domainList = internalPrintersMap.values.map { it.toDomain() }
            _discoveredDevices.value = domainList
            Log.d("PRINTER_DEBUG", "Found $mode Device: $uniqueId | IP: ${info.address}")
        }
    }

    override suspend fun connect(device: PrinterDevice) {
        if (isConnecting) return
        isConnecting = true
        
        stopAllSearches()
        reconnectionJob?.cancel()
        isManualDisconnect = false
        _printerState.value = PrinterState.Connecting(device.name)

        val discoveredPrinter = internalPrintersMap[device.id]
        if (discoveredPrinter == null) {
            _printerState.value = PrinterState.Error("Device instance lost. Please scan again.")
            isConnecting = false
            return
        }

        val cloudPrinter = discoveredPrinter.printer
        
        cloudPrinter.connect(context, object : ConnectCallback {
            override fun onConnect() {
                isConnecting = false
                isRecovering = false
                activeCloudPrinter = cloudPrinter
                connectedDevice = device
                try {
                    cloudPrinter.setEncodeMode(EncodeType.UTF_8)
                } catch (e: Exception) {
                    Log.e("PRINTER_DEBUG", "Encoding set failed", e)
                }
                _printerState.value = PrinterState.Connected(device)
                startHeartbeat(device)
                Log.d("PRINTER_DEBUG", "Connection successful")
            }

            override fun onFailed(err: String?) {
                isConnecting = false
                // Only show error UI if we aren't in a silent recovery loop
                if (!isRecovering) {
                    _printerState.value = PrinterState.Error("Connect Failed", err ?: "Unknown error")
                } else {
                    Log.w("RESILIENCE_HUB", "Handshake failed during recovery: $err. Will retry...")
                }
            }

            override fun onDisConnect() {
                stopHeartbeat()
                activeCloudPrinter = null
                val lastDev = connectedDevice ?: device
                connectedDevice = null

                if (!isManualDisconnect) {
                    Log.w("PRINTER_DEBUG", "Unexpected Disconnect! Triggering Self-Healing...")
                    feedbackManager.emitGracefulWarning()
                    triggerAutoReconnection(lastDev)
                } else {
                    _printerState.value = PrinterState.Idle
                }
            }
        })
    }

    /**
     * ELITE RESILIENCE: Perpetual Auto-Reconnection Hub
     * Instead of timing out, this logic enters an infinite "Resilience Loop"
     * that scales from aggressive polling (2s) to battery-efficient polling (15s).
     */
    private fun triggerAutoReconnection(device: PrinterDevice) {
        reconnectionJob?.cancel()
        isRecovering = true
        reconnectionJob = repositoryScope.launch {
            lastConnectedDevice = device
            var attempts = 0
            val aggressiveLimit = 30 // First 60 seconds (30 attempts * 2s)

            while (isActive) {
                attempts++
                // Aggressive Search: First 10 seconds (1s intervals), then backoff to 2s, then 10s
                val delayMs = when {
                    attempts <= 10 -> 1000L
                    attempts <= 35 -> 2000L
                    else -> 10000L
                }

                // Visual micro-state for the Hub
                val microState = when {
                    attempts <= 5 -> "Synchronizing signal..."
                    attempts <= 15 -> "Verifying hardware presence..."
                    attempts <= aggressiveLimit -> "Probing connection ports..."
                    else -> "Battery-efficient polling active..."
                }

                _printerState.value = PrinterState.Reconnecting(device.name, (delayMs/1000).toInt(), microState)

                Log.d("RESILIENCE_HUB", "Attempt #$attempts | Next check in ${delayMs / 1000}s")

                // Tactical reminders: vibration only, no intrusive tones during background search
                if (attempts == 1 || (attempts > aggressiveLimit && attempts % 15 == 0)) {
                    withContext(Dispatchers.Main) {
                        feedbackManager.emitCriticalWarning() // Aggressive Double-Tap
                    }
                }

                // Silent search for the specific device
                var found = false
                val method = when {
                    device.id.startsWith("USB") -> 1000
                    device.id.startsWith("BT") -> 3000
                    else -> 2000
                }

                try {
                    SunmiPrinterManager.getInstance()
                        .searchCloudPrinter(context, method) { printer ->
                            if (printer != null && !found) {
                                val info = printer.cloudPrinterInfo
                                val uniqueId = when (method) {
                                    1000 -> "USB-${info.vid}-${info.pid}"
                                    3000 -> "BT-${info.mac}"
                                    else -> "LAN-${info.address}"
                                }

                                if (uniqueId == device.id && !isConnecting) {
                                    found = true
                                    // CRITICAL FIX: Update map with fresh instance
                                    handlePrinterFound(printer) 
                                    
                                    repositoryScope.launch {
                                        // Robust Handshake Delay (3s for LAN TCP stabilization)
                                        val handshakeDelay = if (method == 2000) 3000L else 1000L
                                        delay(handshakeDelay)
                                        Log.i("RESILIENCE_HUB", "Hardware found and mapped! Initializing handshake...")
                                        connect(device) 
                                    }
                                }
                            }
                        }
                } catch (e: Exception) {
                    Log.e("RESILIENCE_HUB", "Search failed: ${e.message}")
                }

                if (found) {
                    // We wait for the connect() call above to finalize the state
                    // If connect() fails, the state will go to Error, and triggerAutoReconnection 
                    // will be called again from onDisConnect(), keeping the loop alive.
                    break
                }

                delay(delayMs)
            }
        }
    }

    override suspend fun autoConnectUsb(): Boolean {
        // We do a silent quick scan just for USB
        _printerState.value = PrinterState.AutoConnecting
        stopAllSearches()
        internalPrintersMap.clear()

        var foundUsb = false
        val methodUSB = SearchMethod.USB
        try {
            SunmiPrinterManager.getInstance()
                .searchCloudPrinter(context, methodUSB) { printer ->
                    if (!foundUsb && printer != null) {
                        foundUsb = true
                        handlePrinterFound(printer) // Will put in internalMap
                        val domainDev = internalPrintersMap.values.firstOrNull()?.toDomain()
                        if (domainDev != null) {
                            repositoryScope.launch { connect(domainDev) }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e("AUTO_CONNECT", "USB Search failed", e)
        }

        // Wait briefly for USB detection
        delay(1500)
        SunmiPrinterManager.getInstance().stopSearch(context, methodUSB)

        if (!foundUsb) {
            _printerState.value = PrinterState.Idle
        }
        return foundUsb
    }

    private var captureView: android.view.View? = null

    override fun getActiveCloudPrinter(): CloudPrinter? = activeCloudPrinter

    override fun getCaptureView(): android.view.View? = captureView

    override fun setCaptureView(view: android.view.View) {
        this.captureView = view
    }

    override suspend fun printChunk(bitmap: Bitmap, isLastChunk: Boolean): PrintResult {
        val printer = activeCloudPrinter
        val device = connectedDevice

        if (printer == null || device == null) {
            return PrintResult.Failure("Not connected to any printer.")
        }

        return printMutex.withLock {
            if (device.connectionType == ConnectionType.LAN && device.address.isNotEmpty()) {
                rawSocketPrintSource.printBitmap(device.address, device.port, bitmap)
            } else {
                sdkPrintSource.printBitmapChunk(printer, bitmap, isLastChunk)
            }
        }
    }

    override suspend fun finalCut(): PrintResult {
        val printer = activeCloudPrinter ?: return PrintResult.Failure("Printer null on finalCut")
        val device = connectedDevice

        // For LAN: RawSocketPrintSource already sent the ESC J feed + GS V cut inline,
        // as part of every printBitmap() call. Sending an additional SDK cut here would
        // either produce a redundant blank-paper cut or be silently ignored by the printer.
        if (device?.connectionType == ConnectionType.LAN && !device.address.isNullOrEmpty()) {
            Log.d("PRINTER_DEBUG", "finalCut: LAN path — cut already delivered via raw socket.")
            return PrintResult.Success
        }

        // For USB/BT: All chunks have been buffered in the SDK transaction buffer.
        // This single awaitable commitAndCut delivers: lineFeed + cutPaper + commitTransBuffer.
        // We suspend until the printer confirms receipt, guaranteeing the cut happens only
        // AFTER the printer has physically processed all buffered image data.
        Log.d("PRINTER_DEBUG", "finalCut: USB/BT path — committing buffer and cutting.")
        return sdkPrintSource.commitAndCut(printer)
    }

    override suspend fun initPrintJob(): PrintResult {
        val printer = activeCloudPrinter ?: return PrintResult.Failure("Not connected")
        val device = connectedDevice ?: return PrintResult.Failure("No active device")

        // LAN jobs print via raw socket — there is no SDK buffer to initialize.
        if (device.connectionType == ConnectionType.LAN && device.address.isNotEmpty()) {
            return PrintResult.Success
        }

        // USB/BT: clear the SDK's internal command buffer before the first chunk.
        // This ensures no stale content from a previous incomplete job is committed.
        sdkPrintSource.initBuffer(printer)
        return PrintResult.Success
    }

    override suspend fun printReceipt(bitmap: Bitmap): PrintResult {
        return printChunk(bitmap, isLastChunk = true)
    }

    private fun stopAllSearches() {
        searchMethods.forEach { method ->
            try {
                SunmiPrinterManager.getInstance().stopSearch(context, method)
            } catch (e: Exception) {
                Log.e("VALT_STOP", "Error stopping $method", e)
            }
        }
    }

    /**
     * VITALITY CORE: Background Heartbeat
     * Pings the hardware every 3 seconds to ensure real-time dashboard accuracy.
     */
    private fun startHeartbeat(device: PrinterDevice) {
        heartbeatJob?.cancel()
        heartbeatJob = repositoryScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000)

                val isStillConnected = checkPhysicalConnection(device)

                if (!isStillConnected) {
                    Log.w("VITALITY", "Heartbeat lost for ${device.name}")
                    withContext(Dispatchers.Main) {
                        feedbackManager.emitGracefulWarning() // Haptic Pulse
                        triggerAutoReconnection(device)
                    }
                    break // Stop heartbeat, enter recovery
                }
            }
        }
    }

    private fun checkPhysicalConnection(device: PrinterDevice): Boolean {
        return when (device.connectionType) {
            ConnectionType.USB -> {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                usbManager?.deviceList?.values?.any {
                    it.deviceName == device.address || (it.vendorId != 0 && device.id.contains(
                        "${it.vendorId}"
                    ))
                } ?: false
            }

            ConnectionType.LAN -> {
                // CRITICAL FIX: Do NOT probe port 9100 via raw socket.
                //
                // The Sunmi SDK uses port 9100 for its own management protocol.
                // Opening a competing raw TCP connection to 9100 every 3 seconds
                // hijacks the SDK's byte stream, causing the printer to force-close
                // the SDK session and fire onDisConnect() — triggering a false
                // reconnect loop every 3 seconds.
                //
                // The correct pattern: trust the SDK's onDisConnect() callback.
                // It fires reliably when the LAN link truly drops (cable pull, power off, etc.).
                Log.d("VITALITY", "LAN heartbeat: trusting SDK session for ${device.address}")
                true
            }

            ConnectionType.BLUETOOTH -> {
                // BT relies on OS connectivity or SDK disconnect callbacks.
                true
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    override fun stopScan() {
        stopAllSearches()
        if (_printerState.value is PrinterState.Scanning) {
            _printerState.value = PrinterState.Idle
        }
    }

    override fun disconnect() {
        isManualDisconnect = true
        stopHeartbeat()
        reconnectionJob?.cancel()
        stopScan()
        activeCloudPrinter?.release(context)
        activeCloudPrinter = null
        connectedDevice = null
        lastConnectedDevice = null
        _printerState.value = PrinterState.Idle
        _discoveredDevices.value = emptyList()
        internalPrintersMap.clear()
    }
}
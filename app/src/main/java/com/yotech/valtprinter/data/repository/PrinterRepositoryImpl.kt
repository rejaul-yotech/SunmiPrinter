package com.yotech.valtprinter.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.sunmi.externalprinterlibrary2.ConnectCallback
import com.sunmi.externalprinterlibrary2.SearchCallback
import com.sunmi.externalprinterlibrary2.SearchMethod
import com.sunmi.externalprinterlibrary2.SunmiPrinterManager
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.sunmi.externalprinterlibrary2.style.EncodeType
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
import com.yotech.valtprinter.core.util.FeedbackManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                    .searchCloudPrinter(context, method
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
        stopAllSearches()
        reconnectionJob?.cancel()
        isManualDisconnect = false
        _printerState.value = PrinterState.Connecting(device.name)

        val discoveredPrinter = internalPrintersMap[device.id]
        if (discoveredPrinter == null) {
            _printerState.value = PrinterState.Error("Device instance lost. Please scan again.")
            return
        }

        val cloudPrinter = discoveredPrinter.printer
        repositoryScope.launch {
            delay(500)
            cloudPrinter.connect(context, object : ConnectCallback {
                override fun onConnect() {
                    activeCloudPrinter = cloudPrinter
                    connectedDevice = device
                    try {
                        cloudPrinter.setEncodeMode(EncodeType.UTF_8)
                    } catch (e: Exception) {
                        Log.e("PRINTER_DEBUG", "Encoding set failed", e)
                    }
                    _printerState.value = PrinterState.Connected(device)
                    Log.d("PRINTER_DEBUG", "Connection successful")
                }

                override fun onFailed(err: String?) {
                    _printerState.value = PrinterState.Error("Connect Failed: $err")
                }

                override fun onDisConnect() {
                    if (activeCloudPrinter === cloudPrinter) {
                        activeCloudPrinter = null
                        connectedDevice = null
                        
                        if (!isManualDisconnect) {
                            Log.w("PRINTER_DEBUG", "Unexpected Disconnect! Triggering Self-Healing...")
                            feedbackManager.emitError()
                            triggerAutoReconnection(device)
                        } else {
                            _printerState.value = PrinterState.Idle
                        }
                    }
                }
            })
        }
    }

    private fun triggerAutoReconnection(device: PrinterDevice) {
        reconnectionJob?.cancel()
        reconnectionJob = repositoryScope.launch {
            lastConnectedDevice = device
            var secondsPassed = 0
            val maxSeconds = 60

            while (secondsPassed < maxSeconds) {
                val remaining = maxSeconds - secondsPassed
                _printerState.value = PrinterState.Reconnecting(device.name, remaining)
                
                Log.d("RECONNECT", "Attempting recovery... $remaining s remaining")
                
                // Silent search for the specific device
                var found = false
                val method = when {
                    device.id.startsWith("USB") -> 1000
                    device.id.startsWith("BT") -> 3000
                    else -> 2000
                }

                try {
                    SunmiPrinterManager.getInstance().searchCloudPrinter(context, method) { printer ->
                        if (printer != null && !found) {
                            val info = printer.cloudPrinterInfo
                            val uniqueId = when (method) {
                                1000 -> "USB-${info.vid}-${info.pid}"
                                3000 -> "BT-${info.mac}"
                                else -> "LAN-${info.address}"
                            }
                            
                            if (uniqueId == device.id) {
                                found = true
                                repositoryScope.launch { 
                                    connect(device) 
                                    feedbackManager.emitSuccess()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RECONNECT", "Search failed: ${e.message}")
                }

                if (found) break

                delay(2000) // Retry every 2 seconds
                secondsPassed += 2
            }

            if (activeCloudPrinter == null) {
                _printerState.value = PrinterState.Error("Printer Lost. Please check power/cable.")
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
        val printer = activeCloudPrinter ?: return PrintResult.Failure("Printer null")
        return try {
            printer.cutPaper(true)
            printer.commitTransBuffer(null)
            PrintResult.Success
        } catch (e: Exception) {
            PrintResult.Failure("Cut Failed: ${e.message}")
        }
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

    override fun stopScan() {
        stopAllSearches()
        if (_printerState.value is PrinterState.Scanning) {
            _printerState.value = PrinterState.Idle
        }
    }

    override fun disconnect() {
        isManualDisconnect = true
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
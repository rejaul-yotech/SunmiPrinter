package com.yotech.valtprinter.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrinterRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sdkPrintSource: SdkPrintSource,
    private val rawSocketPrintSource: RawSocketPrintSource,
    private val feedbackManager: FeedbackManager
) : PrinterRepository {
    private enum class RecoveryReason {
        SDK_DISCONNECT,
        HEARTBEAT_LOSS,
        PRINT_TRANSPORT_LOSS,
        ACTIVE_PRINTER_MISSING
    }

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
    // Monotonic token for connect() attempts; stale callbacks are ignored.
    private var activeConnectAttemptId = 0L

    // Real-time Vitality: Heartbeat
    private var heartbeatJob: Job? = null
    private var btConsecutiveMisses = 0
    private var lastSuccessfulPrintCommitMs = 0L
    private var lastBtProbeMs = 0L
    private var lastPrintActivityMs = 0L
    private var lastBtConfirmedHitMs = 0L
    private var lastRecoveryRequestMs = 0L
    private var activeRecoveryDeviceId: String? = null
    private var recoverySessionId = 0L

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
        // Manual scan should take control and stop auto-recovery loop.
        reconnectionJob?.cancel()
        isRecovering = false
        activeRecoveryDeviceId = null
        btConsecutiveMisses = 0
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

        // Always refresh with the latest SDK instance. Reusing stale CloudPrinter objects
        // after reconnect can cause transaction failures (e.g., CommitCut UNKNOWN).
        val discovered = DiscoveredPrinter(printer, mode, uniqueId)
        internalPrintersMap[uniqueId] = discovered

        // Map to domain object and update state flow
        val domainList = internalPrintersMap.values.map { it.toDomain() }
        _discoveredDevices.value = domainList
        Log.d("PRINTER_DEBUG", "Found/Updated $mode Device: $uniqueId | IP: ${info.address}")
    }

    override suspend fun connect(device: PrinterDevice) {
        if (isConnecting) return
        isConnecting = true
        val connectAttemptId = ++activeConnectAttemptId
        
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
                if (connectAttemptId != activeConnectAttemptId) {
                    Log.w("PRINTER_DEBUG", "Ignoring stale onConnect for ${device.id} (attempt=$connectAttemptId)")
                    return
                }
                isConnecting = false
                isRecovering = false
                activeRecoveryDeviceId = null
                btConsecutiveMisses = 0
                lastBtConfirmedHitMs = System.currentTimeMillis()
                activeCloudPrinter = cloudPrinter
                connectedDevice = device
                lastConnectedDevice = device
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
                if (connectAttemptId != activeConnectAttemptId) {
                    Log.w("PRINTER_DEBUG", "Ignoring stale onFailed for ${device.id} (attempt=$connectAttemptId): $err")
                    return
                }
                isConnecting = false
                // If already connected, never let a delayed failure callback overwrite success state.
                if (connectedDevice?.id == device.id && activeCloudPrinter != null) {
                    Log.w("PRINTER_DEBUG", "Ignoring late onFailed after active connection for ${device.id}: $err")
                    return
                }
                // Only show error UI if we aren't in a silent recovery loop
                if (!isRecovering) {
                    _printerState.value = PrinterState.Error("Connect Failed", err ?: "Unknown error")
                } else {
                    Log.w("RESILIENCE_HUB", "Handshake failed during recovery: $err. Will retry...")
                }
            }

            override fun onDisConnect() {
                if (connectAttemptId != activeConnectAttemptId) {
                    Log.w("PRINTER_DEBUG", "Ignoring stale onDisConnect for ${device.id} (attempt=$connectAttemptId)")
                    return
                }
                stopHeartbeat()
                activeCloudPrinter = null
                btConsecutiveMisses = 0
                val lastDev = connectedDevice ?: device
                connectedDevice = null

                if (!isManualDisconnect) {
                    Log.w("PRINTER_DEBUG", "Unexpected Disconnect! Triggering Self-Healing...")
                    feedbackManager.emitGracefulWarning()
                    requestRecovery(
                        device = lastDev,
                        reason = RecoveryReason.SDK_DISCONNECT,
                        details = "SDK onDisConnect callback"
                    )
                } else {
                    _printerState.value = PrinterState.Idle
                }
            }
        })
    }

    override suspend fun connectPairedDevice(device: PrinterDevice): Boolean {
        if (internalPrintersMap.containsKey(device.id)) {
            connect(device)
            return true
        }

        val method = when (device.connectionType) {
            ConnectionType.USB -> 1000
            ConnectionType.LAN -> 2000
            ConnectionType.BLUETOOTH -> 3000
        }

        var found = false
        try {
            SunmiPrinterManager.getInstance()
                .searchCloudPrinter(context, method) { printer ->
                    if (printer == null || found) return@searchCloudPrinter
                    val info = printer.cloudPrinterInfo ?: return@searchCloudPrinter
                    val uniqueId = when (method) {
                        1000 -> "USB-${info.vid}-${info.pid}"
                        3000 -> "BT-${info.mac}"
                        else -> "LAN-${info.address}"
                    }
                    if (uniqueId == device.id) {
                        found = true
                        handlePrinterFound(printer)
                    }
                }
            delay(1500)
            SunmiPrinterManager.getInstance().stopSearch(context, method)
        } catch (e: Exception) {
            Log.e("PRINTER_DEBUG", "connectPairedDevice search failed", e)
        }

        if (found && internalPrintersMap.containsKey(device.id)) {
            connect(device)
            return true
        }
        return false
    }

    /**
     * ELITE RESILIENCE: Perpetual Auto-Reconnection Hub
     * Instead of timing out, this logic enters an infinite "Resilience Loop"
     * that scales from aggressive polling (2s) to battery-efficient polling (15s).
     */
    private fun requestRecovery(device: PrinterDevice, reason: RecoveryReason, details: String) {
        if (isManualDisconnect) return
        val now = System.currentTimeMillis()
        val sameDevice = activeRecoveryDeviceId == device.id
        if (isRecovering && sameDevice) {
            Log.d("RESILIENCE_HUB", "Recovery already active for ${device.id}. Ignoring duplicate trigger: $reason ($details)")
            return
        }
        val cooldownMs = 1200L
        if (now - lastRecoveryRequestMs < cooldownMs && sameDevice) {
            Log.d("RESILIENCE_HUB", "Recovery trigger suppressed by cooldown: $reason ($details)")
            return
        }
        lastRecoveryRequestMs = now
        recoverySessionId += 1
        triggerAutoReconnection(device, reason, details, recoverySessionId)
    }

    private fun triggerAutoReconnection(
        device: PrinterDevice,
        reason: RecoveryReason,
        details: String,
        sessionId: Long
    ) {
        reconnectionJob?.cancel()
        isRecovering = true
        activeRecoveryDeviceId = device.id
        btConsecutiveMisses = 0
        // Force close any stale SDK session before searching/rebinding.
        // This reduces stale sockets and repeated OS-level re-pair prompts.
        try {
            activeCloudPrinter?.release(context)
        } catch (e: Exception) {
            Log.w("RESILIENCE_HUB", "Release before recovery failed: ${e.message}")
        }
        activeCloudPrinter = null
        connectedDevice = null
        reconnectionJob = repositoryScope.launch {
            lastConnectedDevice = device
            var attempts = 0
            var connectScheduled = false
            val aggressiveLimit = 30 // First 60 seconds (30 attempts * 2s)
            Log.i("RESILIENCE_HUB", "Recovery session $sessionId started. reason=$reason details=$details device=${device.id}")

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

                Log.d("RESILIENCE_HUB", "Session $sessionId | Attempt #$attempts | Next check in ${delayMs / 1000}s")

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

                                if (uniqueId == device.id && !isConnecting && !connectScheduled) {
                                    found = true
                                    connectScheduled = true
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
            if (!isActive) {
                Log.d("RESILIENCE_HUB", "Recovery session $sessionId cancelled.")
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
            lastConnectedDevice?.let {
                if (!isRecovering) {
                    Log.w("PRINTER_DEBUG", "printChunk detected missing active connection. Triggering recovery.")
                    requestRecovery(
                        device = it,
                        reason = RecoveryReason.ACTIVE_PRINTER_MISSING,
                        details = "printChunk called with null activeCloudPrinter/device"
                    )
                }
            }
            return PrintResult.Failure("Not connected to any printer.")
        }

        return printMutex.withLock {
            lastPrintActivityMs = System.currentTimeMillis()
            val result = if (device.connectionType == ConnectionType.LAN && device.address.isNotEmpty()) {
                rawSocketPrintSource.printBitmap(device.address, device.port, bitmap)
            } else {
                sdkPrintSource.printBitmapChunk(printer, bitmap, isLastChunk)
            }
            lastPrintActivityMs = System.currentTimeMillis()
            if (result is PrintResult.Failure && isTransportLoss(result.reason) && !isRecovering) {
                Log.w("PRINTER_DEBUG", "printChunk transport loss: ${result.reason}. Triggering recovery.")
                requestRecovery(
                    device = device,
                    reason = RecoveryReason.PRINT_TRANSPORT_LOSS,
                    details = result.reason
                )
            }
            result
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
        val result = sdkPrintSource.commitAndCut(printer)
        if (result is PrintResult.Success) {
            lastSuccessfulPrintCommitMs = System.currentTimeMillis()
            lastPrintActivityMs = lastSuccessfulPrintCommitMs
            btConsecutiveMisses = 0
        }
        return result
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
        return if (sdkPrintSource.initBuffer(printer)) {
            PrintResult.Success
        } else {
            PrintResult.Failure("Buffer init failed")
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
                        requestRecovery(
                            device = device,
                            reason = RecoveryReason.HEARTBEAT_LOSS,
                            details = "Physical heartbeat probe failed"
                        )
                    }
                    break // Stop heartbeat, enter recovery
                }
            }
        }
    }

    private suspend fun checkPhysicalConnection(device: PrinterDevice): Boolean {
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
                probeBluetoothPresenceWithConfirmation(device)
            }
        }
    }

    private suspend fun probeBluetoothPresenceWithConfirmation(device: PrinterDevice): Boolean {
        // Never probe while print transaction lock is active.
        if (printMutex.isLocked) return true

        val now = System.currentTimeMillis()
        // Avoid heartbeat decisions during/just-after print pipeline activity.
        val printActivityGraceMs = 5_000L
        if (now - lastPrintActivityMs < printActivityGraceMs) return true

        // Guard window after successful commit to avoid post-print stack turbulence.
        val postCommitGraceMs = 6_000L
        if (now - lastSuccessfulPrintCommitMs < postCommitGraceMs) return true

        // Balanced cadence: quick enough for UX, conservative enough to avoid random flaps.
        val minProbeIntervalMs = 3_000L
        if (now - lastBtProbeMs < minProbeIntervalMs) return true
        lastBtProbeMs = now

        val firstHit = runBtDiscoveryProbe(device.id)
        val confirmedHit = if (firstHit) {
            true
        } else {
            // Confirmation pass to reduce false negatives from one noisy scan cycle.
            delay(350)
            runBtDiscoveryProbe(device.id)
        }

        if (confirmedHit) {
            btConsecutiveMisses = 0
            lastBtConfirmedHitMs = now
            Log.d("VITALITY", "BT heartbeat probe confirmed for ${device.name}")
            return true
        }

        btConsecutiveMisses++
        // High-confidence disconnect gate:
        // - 3 consecutive confirmed miss cycles, AND
        // - no positive hit for at least 10 seconds.
        val staleForMs = now - lastBtConfirmedHitMs
        val hardDisconnect = btConsecutiveMisses >= 3 && staleForMs >= 10_000L
        Log.w(
            "VITALITY",
            "BT probe miss ${btConsecutiveMisses}/3 for ${device.name} (staleForMs=$staleForMs hardDisconnect=$hardDisconnect)"
        )
        return !hardDisconnect
    }

    private suspend fun runBtDiscoveryProbe(expectedId: String): Boolean {
        val found = withTimeoutOrNull(800L) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                var resolved = false
                try {
                    SunmiPrinterManager.getInstance().searchCloudPrinter(context, SearchMethod.BT) { printer ->
                        if (resolved || continuation.isCompleted || printer == null) return@searchCloudPrinter
                        val info = printer.cloudPrinterInfo ?: return@searchCloudPrinter
                        val uniqueId = "BT-${info.mac}"
                        if (uniqueId == expectedId) {
                            resolved = true
                            continuation.resume(true)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VITALITY", "BT probe search failed: ${e.message}", e)
                    if (!resolved && !continuation.isCompleted) {
                        resolved = true
                        continuation.resume(false)
                    }
                }

                continuation.invokeOnCancellation {
                    try {
                        SunmiPrinterManager.getInstance().stopSearch(context, SearchMethod.BT)
                    } catch (_: Exception) {
                    }
                }
            }
        } ?: false

        try {
            SunmiPrinterManager.getInstance().stopSearch(context, SearchMethod.BT)
        } catch (_: Exception) {
        }
        return found
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun isTransportLoss(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val lower = reason.lowercase()
        return lower.contains("not connected")
                || lower.contains("disconnect")
                || lower.contains("socket")
                || lower.contains("timeout")
                || lower.contains("offline")
                || lower.contains("commitcut error")
                || (lower.contains("unknown") && lower.contains("commit"))
    }

    override fun stopScan() {
        stopAllSearches()
        if (_printerState.value is PrinterState.Scanning) {
            _printerState.value = PrinterState.Idle
        }
    }

    override fun isUsbPrinterPresent(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        return usbManager?.deviceList?.isNotEmpty() == true
    }

    @android.annotation.SuppressLint("MissingPermission")
    override fun isBtDeviceBonded(mac: String): Boolean {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            adapter?.bondedDevices?.any { it.address == mac } == true
        } catch (e: Exception) {
            Log.w("PRINTER_DEBUG", "BT bond check failed: ${e.message}")
            // If bond state can't be read (usually missing permission), do NOT attempt connect.
            false
        }
    }

    override fun hasBtConnectPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun disconnect() {
        isManualDisconnect = true
        stopHeartbeat()
        reconnectionJob?.cancel()
        isRecovering = false
        activeRecoveryDeviceId = null
        stopScan()
        activeCloudPrinter?.release(context)
        activeCloudPrinter = null
        connectedDevice = null
        lastConnectedDevice = null
        lastSuccessfulPrintCommitMs = 0L
        lastBtProbeMs = 0L
        lastPrintActivityMs = 0L
        lastBtConfirmedHitMs = 0L
        btConsecutiveMisses = 0
        _printerState.value = PrinterState.Idle
        _discoveredDevices.value = emptyList()
        internalPrintersMap.clear()
    }
}
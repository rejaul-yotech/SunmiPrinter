package com.yotech.valtprinter.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.ContextCompat
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.yotech.valtprinter.core.util.FeedbackManager
import com.yotech.valtprinter.data.repository.internal.ConnectionController
import com.yotech.valtprinter.data.repository.internal.ConnectionState
import com.yotech.valtprinter.data.repository.internal.Coordinator
import com.yotech.valtprinter.data.repository.internal.HeartbeatManager
import com.yotech.valtprinter.data.repository.internal.PrintPipeline
import com.yotech.valtprinter.data.repository.internal.RecoveryManager
import com.yotech.valtprinter.data.repository.internal.RecoveryReason
import com.yotech.valtprinter.data.repository.internal.ScanController
import com.yotech.valtprinter.data.repository.internal.UsbTakeoverController
import com.yotech.valtprinter.data.source.RawSocketPrintSource
import com.yotech.valtprinter.data.source.SdkPrintSource
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrintResult
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.domain.repository.PrinterRepository
import com.yotech.valtprinter.domain.repository.RenderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.lang.ref.WeakReference

/**
 * Thin orchestrator composing the specialized managers that together implement
 * the printer connection & print lifecycle:
 *
 * - [ScanController] — device discovery
 * - [ConnectionController] — connect/disconnect handshake
 * - [HeartbeatManager] — real-time vitality probe
 * - [RecoveryManager] — self-healing reconnection loop
 * - [PrintPipeline] — per-job init → chunk → cut
 * - [UsbTakeoverController] — USB hot-plug promotion / fallback
 *
 * Managers reach cross-cutting operations via a private [Coordinator] adapter
 * ([coordinator]) rather than holding direct references to each other — this
 * keeps the dependency graph acyclic while preserving the tight behavioral
 * coupling the state machine requires. The adapter is a nested object so the
 * public class never exposes `internal` Coordinator members.
 */
class PrinterRepositoryImpl(
    private val context: Context,
    sdkPrintSource: SdkPrintSource,
    rawSocketPrintSource: RawSocketPrintSource,
    private val feedbackManager: FeedbackManager
) : PrinterRepository, RenderRepository {

    // --- Shared state & primitives -------------------------------------------
    private val state: ConnectionState = ConnectionState()
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val printMutex = Mutex()

    // --- Public StateFlows ----------------------------------------------------
    private val _discoveredDevices = MutableStateFlow<List<PrinterDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<PrinterDevice>> =
        _discoveredDevices.asStateFlow()

    private val _printerState = MutableStateFlow<PrinterState>(PrinterState.Idle)
    override val printerState: StateFlow<PrinterState> = _printerState.asStateFlow()

    // --- Internal coordinator ------------------------------------------------
    // Bridges managers back to this repository for cross-cutting ops. Defined
    // as a private anonymous object so the public class surface doesn't leak
    // `internal` Coordinator members.
    private val coordinator: Coordinator = object : Coordinator {
        override val context: Context get() = this@PrinterRepositoryImpl.context
        override val scope: CoroutineScope get() = this@PrinterRepositoryImpl.scope
        override val state: ConnectionState get() = this@PrinterRepositoryImpl.state
        override val feedbackManager: FeedbackManager
            get() = this@PrinterRepositoryImpl.feedbackManager
        override val printerStateFlow: MutableStateFlow<PrinterState> get() = _printerState
        override val discoveredDevicesFlow: MutableStateFlow<List<PrinterDevice>>
            get() = _discoveredDevices

        override fun handlePrinterFound(printer: CloudPrinter) =
            scanController.handlePrinterFound(printer)
        override fun stopAllSearches() = scanController.stopAllSearches()
        override suspend fun connect(device: PrinterDevice) =
            connectionController.connect(device)
        override fun disconnect() = connectionController.disconnect()
        override fun requestRecovery(
            device: PrinterDevice,
            reason: RecoveryReason,
            details: String
        ) = recoveryManager.request(device, reason, details)
        override fun startHeartbeat(device: PrinterDevice) = heartbeatManager.start(device)
        override fun stopHeartbeat() = heartbeatManager.stop()
    }

    // --- Manager composition --------------------------------------------------
    // Construction order mirrors the dependency direction; each manager only
    // depends on already-constructed peers or on [coordinator].
    private val scanController = ScanController(context, _discoveredDevices)
    private val heartbeatManager = HeartbeatManager(coordinator, printMutex)
    private val recoveryManager = RecoveryManager(coordinator)
    private val connectionController = ConnectionController(coordinator, scanController)
    private val printPipeline = PrintPipeline(
        coordinator, sdkPrintSource, rawSocketPrintSource, printMutex
    )
    private val usbTakeover = UsbTakeoverController(
        coordinator, connectionController, scanController
    )

    // --- Capture view (RenderRepository) -------------------------------------
    // Hold the host's capture view through a WeakReference so a forgotten
    // clear() cannot leak the entire activity tree once the host destroys
    // the screen.
    private var captureViewRef: WeakReference<android.view.View>? = null

    override fun getCaptureView(): android.view.View? = captureViewRef?.get()
    override fun setCaptureView(view: android.view.View) {
        captureViewRef = WeakReference(view)
    }
    override fun clearCaptureView() {
        captureViewRef = null
    }

    // ═════════════════ PrinterRepository — Scan ═════════════════════════════

    override fun startScan() {
        _printerState.value = PrinterState.Scanning
        scanController.startScan(onBeforeSearch = {
            // Manual scan takes control from the auto-recovery loop.
            recoveryManager.cancel()
            state.btConsecutiveMisses = 0
        })
    }

    override fun stopScan() {
        scanController.stopAllSearches()
        if (_printerState.value is PrinterState.Scanning) {
            _printerState.value = PrinterState.Idle
        }
    }

    // ═════════════════ PrinterRepository — Connect ══════════════════════════

    override suspend fun connect(device: PrinterDevice) {
        recoveryManager.cancel()
        connectionController.connect(device)
    }

    override suspend fun connectPairedDevice(device: PrinterDevice): Boolean =
        connectionController.connectPaired(device)

    override suspend fun autoConnectUsb(): Boolean = usbTakeover.autoConnectUsb()

    override fun disconnect() = connectionController.disconnect()

    override fun isPrinterReady(): Boolean = state.isPrinterReady()

    override fun activeConnectionType(): ConnectionType? =
        state.connectedDevice?.connectionType

    override suspend fun promoteToUsb(): Boolean = usbTakeover.promoteToUsb()

    override suspend fun onUsbDetached() = usbTakeover.onUsbDetached()

    // ═════════════════ PrinterRepository — Print ════════════════════════════

    override suspend fun initPrintJob(): PrintResult = printPipeline.initJob()

    override suspend fun printChunk(bitmap: Bitmap, isLastChunk: Boolean): PrintResult =
        printPipeline.printChunk(bitmap, isLastChunk)

    override suspend fun finalCut(): PrintResult = printPipeline.finalCut()

    override suspend fun printReceipt(bitmap: Bitmap): PrintResult =
        printPipeline.printChunk(bitmap, isLastChunk = true)

    // ═════════════════ PrinterRepository — Hardware info ════════════════════

    override fun isUsbPrinterPresent(): Boolean = usbTakeover.isUsbPrinterPresent()

    @android.annotation.SuppressLint("MissingPermission")
    override fun isBtDeviceBonded(mac: String): Boolean {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            adapter?.bondedDevices?.any { it.address == mac } == true
        } catch (e: Exception) {
            Log.w("PRINTER_DEBUG", "BT bond check failed: ${e.message}")
            // If bond state can't be read (usually missing permission), do NOT
            // attempt connect.
            false
        }
    }

    override fun hasBtConnectPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}

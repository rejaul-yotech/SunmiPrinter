package com.yotech.valtprinter.data.repository.internal

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.sunmi.externalprinterlibrary2.SearchMethod
import com.sunmi.externalprinterlibrary2.SunmiPrinterManager
import com.yotech.valtprinter.core.util.SdkLogger
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrinterDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Real-time vitality monitor. Every 3 s pings the hardware for [connectedDevice]
 * and flags loss to [Coordinator.requestRecovery] when a high-confidence
 * disconnect is observed. Per-transport strategy:
 *
 * - **USB**: enumerate `UsbManager.deviceList` — the OS knows immediately.
 * - **LAN**: trust the SDK's `onDisConnect` callback. Probing port 9100
 *   would hijack the SDK's own management protocol and cause false positives.
 * - **Bluetooth**: active SDP discovery with a confirmation pass and a 3-strike
 *   gate, because raw discovery is noisy and individual scan cycles often miss
 *   a present device.
 */
internal class HeartbeatManager(
    private val coordinator: Coordinator,
    private val printMutex: Mutex
) {
    private val context: Context get() = coordinator.context
    private val state: ConnectionState get() = coordinator.state

    private var heartbeatJob: Job? = null

    fun start(device: PrinterDevice) {
        heartbeatJob?.cancel()
        heartbeatJob = coordinator.scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val stillConnected = checkPhysicalConnection(device)
                if (!stillConnected) {
                    SdkLogger.w(
                        "VITALITY",
                        "Heartbeat lost for ${SdkLogger.redactDeviceName(device.name)}"
                    )
                    withContext(Dispatchers.Main) {
                        coordinator.feedbackManager.emitGracefulWarning()
                        coordinator.requestRecovery(
                            device = device,
                            reason = RecoveryReason.HEARTBEAT_LOSS,
                            details = "Physical heartbeat probe failed"
                        )
                    }
                    break
                }
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun checkPhysicalConnection(device: PrinterDevice): Boolean =
        when (device.connectionType) {
            ConnectionType.USB -> checkUsb(device)
            ConnectionType.LAN -> checkLan(device)
            ConnectionType.BLUETOOTH -> probeBluetoothWithConfirmation(device)
        }

    private fun checkUsb(device: PrinterDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        return usbManager?.deviceList?.values?.any {
            it.deviceName == device.address ||
                (it.vendorId != 0 && device.id.contains("${it.vendorId}"))
        } ?: false
    }

    private fun checkLan(device: PrinterDevice): Boolean {
        // CRITICAL: Do NOT probe port 9100 via raw socket here. The Sunmi SDK
        // uses 9100 for its own management protocol. A competing TCP connection
        // every 3 seconds hijacks that stream, causing the printer to force-
        // close the SDK session and fire onDisConnect() — a false-positive
        // reconnect loop on a fully healthy link.
        //
        // The correct pattern: trust the SDK's onDisConnect() callback, which
        // fires reliably on real disconnects (cable pull, power off, etc.).
        SdkLogger.d(
            "VITALITY",
            "LAN heartbeat: trusting SDK session for ${SdkLogger.redactIp(device.address)}"
        )
        return true
    }

    private suspend fun probeBluetoothWithConfirmation(device: PrinterDevice): Boolean {
        // Never probe while the print transaction lock is held — in-flight
        // writes saturate the radio and can starve discovery.
        if (printMutex.isLocked) return true

        val now = System.currentTimeMillis()
        // Grace window around print pipeline activity: both BT stacks (Android's
        // and Sunmi's) exhibit brief "post-commit turbulence" where discovery
        // flakes — treat as present rather than risk a false disconnect.
        if (now - state.lastPrintActivityMs < PRINT_ACTIVITY_GRACE_MS) return true
        if (now - state.lastSuccessfulPrintCommitMs < POST_COMMIT_GRACE_MS) return true

        // Rate-limit: balance UX responsiveness against random flap.
        if (now - state.lastBtProbeMs < MIN_PROBE_INTERVAL_MS) return true
        state.lastBtProbeMs = now

        val firstHit = runDiscoveryProbe(device.id)
        val confirmedHit = if (firstHit) true else {
            delay(350)
            runDiscoveryProbe(device.id)
        }

        if (confirmedHit) {
            state.btConsecutiveMisses = 0
            state.lastBtConfirmedHitMs = now
            SdkLogger.d(
                "VITALITY",
                "BT heartbeat probe confirmed for ${SdkLogger.redactDeviceName(device.name)}"
            )
            return true
        }

        state.btConsecutiveMisses++
        val staleForMs = now - state.lastBtConfirmedHitMs
        val hardDisconnect =
            state.btConsecutiveMisses >= MISS_STRIKE_LIMIT && staleForMs >= STALE_THRESHOLD_MS
        SdkLogger.w(
            "VITALITY",
            "BT probe miss ${state.btConsecutiveMisses}/$MISS_STRIKE_LIMIT for " +
                "${SdkLogger.redactDeviceName(device.name)} " +
                "(staleForMs=$staleForMs hardDisconnect=$hardDisconnect)"
        )
        return !hardDisconnect
    }

    private suspend fun runDiscoveryProbe(expectedId: String): Boolean {
        val found = withTimeoutOrNull(BT_PROBE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                var resolved = false
                try {
                    SunmiPrinterManager.getInstance()
                        .searchCloudPrinter(context, SearchMethod.BT) { printer ->
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
                    } catch (_: Exception) { /* best-effort */ }
                }
            }
        } ?: false

        try {
            SunmiPrinterManager.getInstance().stopSearch(context, SearchMethod.BT)
        } catch (_: Exception) { /* best-effort */ }
        return found
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 3_000L
        const val PRINT_ACTIVITY_GRACE_MS = 5_000L
        const val POST_COMMIT_GRACE_MS = 6_000L
        const val MIN_PROBE_INTERVAL_MS = 3_000L
        const val BT_PROBE_TIMEOUT_MS = 800L
        const val MISS_STRIKE_LIMIT = 3
        const val STALE_THRESHOLD_MS = 10_000L
    }
}

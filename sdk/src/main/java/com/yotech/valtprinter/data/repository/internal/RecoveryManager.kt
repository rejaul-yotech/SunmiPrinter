package com.yotech.valtprinter.data.repository.internal

import android.content.Context
import android.util.Log
import com.sunmi.externalprinterlibrary2.SunmiPrinterManager
import com.yotech.valtprinter.core.util.SdkLogger
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Self-healing engine. On transport failure this class enters a perpetual
 * search-then-handshake loop that scales from aggressive polling (1 s) to
 * battery-efficient polling (10 s) once the device has been absent for a
 * while. It never "gives up" — giving up in production means lost orders.
 *
 * Entry points:
 * - [request] — cooldown-guarded, idempotent trigger. Safe to call from any
 *   callsite that observes a failure.
 *
 * The loop is cancelled by [cancel] (host-initiated disconnect) or by a
 * successful `connect()` completing via its own handshake flow.
 */
internal class RecoveryManager(
    private val coordinator: Coordinator
) {
    private val context: Context get() = coordinator.context
    private val state: ConnectionState get() = coordinator.state

    private var reconnectionJob: Job? = null

    fun isActiveFor(deviceId: String): Boolean =
        state.isRecovering && state.activeRecoveryDeviceId == deviceId

    fun cancel() {
        reconnectionJob?.cancel()
        reconnectionJob = null
        state.isRecovering = false
        state.activeRecoveryDeviceId = null
    }

    fun request(device: PrinterDevice, reason: RecoveryReason, details: String) {
        if (state.isManualDisconnect) return
        val now = System.currentTimeMillis()
        val sameDevice = state.activeRecoveryDeviceId == device.id

        if (state.isRecovering && sameDevice) {
            SdkLogger.d(
                "RESILIENCE_HUB",
                "Recovery already active for ${SdkLogger.redactDeviceId(device.id)}. " +
                    "Ignoring duplicate trigger: $reason ($details)"
            )
            return
        }
        if (now - state.lastRecoveryRequestMs < COOLDOWN_MS && sameDevice) {
            Log.d("RESILIENCE_HUB", "Recovery trigger suppressed by cooldown: $reason ($details)")
            return
        }
        state.lastRecoveryRequestMs = now
        state.recoverySessionId += 1
        launchLoop(device, reason, details, state.recoverySessionId)
    }

    private fun launchLoop(
        device: PrinterDevice,
        reason: RecoveryReason,
        details: String,
        sessionId: Long
    ) {
        reconnectionJob?.cancel()
        state.isRecovering = true
        state.activeRecoveryDeviceId = device.id
        state.btConsecutiveMisses = 0

        // Force close any stale SDK session before searching/rebinding. Reduces
        // stale sockets and repeated OS-level re-pair prompts. Snapshot once,
        // then atomically clear so a concurrent printChunk() can't observe a
        // half-cleared connection.
        val priorSnap = state.clearConnection()
        try {
            priorSnap?.cloudPrinter?.release(context)
        } catch (e: Exception) {
            Log.w("RESILIENCE_HUB", "Release before recovery failed: ${e.message}")
        }
        state.lanSession?.closeQuietly()
        state.lanSession = null

        reconnectionJob = coordinator.scope.launch {
            state.lastConnectedDevice = device
            var attempts = 0
            var connectScheduled = false

            SdkLogger.i(
                "RESILIENCE_HUB",
                "Recovery session $sessionId started. reason=$reason details=$details " +
                    "device=${SdkLogger.redactDeviceId(device.id)}"
            )

            while (isActive) {
                attempts++
                val delayMs = when {
                    attempts <= 10 -> 1_000L
                    attempts <= 35 -> 2_000L
                    else -> 10_000L
                }
                val microState = when {
                    attempts <= 5 -> "Synchronizing signal..."
                    attempts <= 15 -> "Verifying hardware presence..."
                    attempts <= AGGRESSIVE_LIMIT -> "Probing connection ports..."
                    else -> "Battery-efficient polling active..."
                }

                coordinator.printerStateFlow.value = PrinterState.Reconnecting(
                    device.name, (delayMs / 1000).toInt(), microState
                )
                Log.d(
                    "RESILIENCE_HUB",
                    "Session $sessionId | Attempt #$attempts | Next check in ${delayMs / 1000}s"
                )

                // Tactical reminders: vibration only, no intrusive tones during background search.
                if (attempts == 1 || (attempts > AGGRESSIVE_LIMIT && attempts % 15 == 0)) {
                    withContext(Dispatchers.Main) {
                        coordinator.feedbackManager.emitCriticalWarning()
                    }
                }

                val method = when {
                    device.id.startsWith("USB") -> ScanController.USB_METHOD
                    device.id.startsWith("BT") -> ScanController.BT_METHOD
                    else -> ScanController.LAN_METHOD
                }

                var found = false
                try {
                    SunmiPrinterManager.getInstance()
                        .searchCloudPrinter(context, method) { printer ->
                            if (printer == null || found) return@searchCloudPrinter
                            val info = printer.cloudPrinterInfo
                            val uniqueId = when (method) {
                                ScanController.USB_METHOD -> "USB-${info.vid}-${info.pid}"
                                ScanController.BT_METHOD -> "BT-${info.mac}"
                                else -> "LAN-${info.address}"
                            }
                            if (uniqueId == device.id && !state.isConnecting && !connectScheduled) {
                                found = true
                                connectScheduled = true
                                coordinator.handlePrinterFound(printer)

                                coordinator.scope.launch {
                                    // LAN needs extra time for the TCP stack to stabilize
                                    // before the SDK handshake; BT/USB are faster.
                                    val handshakeDelay =
                                        if (method == ScanController.LAN_METHOD) 3_000L else 1_000L
                                    delay(handshakeDelay)
                                    Log.i(
                                        "RESILIENCE_HUB",
                                        "Hardware found and mapped! Initializing handshake..."
                                    )
                                    coordinator.connect(device)
                                }
                            }
                        }
                } catch (e: Exception) {
                    Log.e("RESILIENCE_HUB", "Search failed: ${e.message}")
                }

                if (found) {
                    // connect() now drives the final state. If it fails, onDisConnect
                    // will fire and trigger another recovery request — the loop stays
                    // alive via that new session rather than by this coroutine looping.
                    break
                }
                delay(delayMs)
            }
            if (!isActive) {
                Log.d("RESILIENCE_HUB", "Recovery session $sessionId cancelled.")
            }
        }
    }

    private companion object {
        const val COOLDOWN_MS = 1_200L
        const val AGGRESSIVE_LIMIT = 30 // First ~60 s of aggressive polling
    }
}

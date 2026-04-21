package com.yotech.valtprinter.data.repository.internal

import android.content.Context
import android.util.Log
import com.sunmi.externalprinterlibrary2.ConnectCallback
import com.yotech.valtprinter.core.util.SdkLogger
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import com.sunmi.externalprinterlibrary2.style.EncodeType

/**
 * Drives the connection lifecycle — the handshake with `CloudPrinter.connect()`
 * and the corresponding teardown. Staleness of SDK callbacks (which can fire
 * after the caller has moved on to a different device) is gated by the
 * monotonic [ConnectionState.activeConnectAttemptId] token — every stale
 * callback short-circuits.
 */
internal class ConnectionController(
    private val coordinator: Coordinator,
    private val scanController: ScanController
) {
    private val context: Context get() = coordinator.context
    private val state: ConnectionState get() = coordinator.state

    suspend fun connect(device: PrinterDevice) {
        if (state.isConnecting) return
        state.isConnecting = true
        val connectAttemptId = ++state.activeConnectAttemptId

        scanController.stopAllSearches()
        state.isManualDisconnect = false
        coordinator.printerStateFlow.value = PrinterState.Connecting(device.name)

        val discoveredPrinter = scanController.internalPrintersMap[device.id]
        if (discoveredPrinter == null) {
            coordinator.printerStateFlow.value =
                PrinterState.Error("Device instance lost. Please scan again.")
            state.isConnecting = false
            return
        }

        val cloudPrinter = discoveredPrinter.printer
        cloudPrinter.connect(context, object : ConnectCallback {
            override fun onConnect() {
                if (connectAttemptId != state.activeConnectAttemptId) {
                    SdkLogger.w(
                        "PRINTER_DEBUG",
                        "Ignoring stale onConnect for ${SdkLogger.redactDeviceId(device.id)} (attempt=$connectAttemptId)"
                    )
                    return
                }
                state.isConnecting = false
                state.isRecovering = false
                state.activeRecoveryDeviceId = null
                state.btConsecutiveMisses = 0
                state.lastBtConfirmedHitMs = System.currentTimeMillis()
                state.activeCloudPrinter = cloudPrinter
                state.connectedDevice = device
                state.lastConnectedDevice = device
                try {
                    cloudPrinter.setEncodeMode(EncodeType.UTF_8)
                } catch (e: Exception) {
                    Log.e("PRINTER_DEBUG", "Encoding set failed", e)
                }
                coordinator.printerStateFlow.value = PrinterState.Connected(device)
                coordinator.startHeartbeat(device)
                Log.d("PRINTER_DEBUG", "Connection successful")
            }

            override fun onFailed(err: String?) {
                if (connectAttemptId != state.activeConnectAttemptId) {
                    SdkLogger.w(
                        "PRINTER_DEBUG",
                        "Ignoring stale onFailed for ${SdkLogger.redactDeviceId(device.id)} (attempt=$connectAttemptId): $err"
                    )
                    return
                }
                state.isConnecting = false
                // Never let a delayed failure callback overwrite an already-active connection.
                if (state.connectedDevice?.id == device.id && state.activeCloudPrinter != null) {
                    SdkLogger.w(
                        "PRINTER_DEBUG",
                        "Ignoring late onFailed after active connection for ${SdkLogger.redactDeviceId(device.id)}: $err"
                    )
                    return
                }
                if (!state.isRecovering) {
                    coordinator.printerStateFlow.value =
                        PrinterState.Error("Connect Failed", err ?: "Unknown error")
                } else {
                    Log.w(
                        "RESILIENCE_HUB",
                        "Handshake failed during recovery: $err. Will retry..."
                    )
                }
            }

            override fun onDisConnect() {
                if (connectAttemptId != state.activeConnectAttemptId) {
                    SdkLogger.w(
                        "PRINTER_DEBUG",
                        "Ignoring stale onDisConnect for ${SdkLogger.redactDeviceId(device.id)} (attempt=$connectAttemptId)"
                    )
                    return
                }
                coordinator.stopHeartbeat()
                state.activeCloudPrinter = null
                state.lanSession?.closeQuietly()
                state.lanSession = null
                state.btConsecutiveMisses = 0
                val lastDev = state.connectedDevice ?: device
                state.connectedDevice = null

                if (!state.isManualDisconnect) {
                    Log.w("PRINTER_DEBUG", "Unexpected Disconnect! Triggering Self-Healing...")
                    coordinator.feedbackManager.emitGracefulWarning()
                    coordinator.requestRecovery(
                        device = lastDev,
                        reason = RecoveryReason.SDK_DISCONNECT,
                        details = "SDK onDisConnect callback"
                    )
                } else {
                    coordinator.printerStateFlow.value = PrinterState.Idle
                }
            }
        })
    }

    suspend fun connectPaired(device: PrinterDevice): Boolean {
        if (scanController.rediscoverPairedDevice(device)) {
            connect(device)
            return true
        }
        return false
    }

    /**
     * Full teardown. Invalidates any in-flight connect attempt by bumping the
     * token so a late `onConnect`/`onFailed` callback short-circuits.
     */
    fun disconnect() {
        state.isManualDisconnect = true
        // Bump the token so any in-flight ConnectCallback sees itself as stale.
        // Without this, a USB promotion that tears down a half-finished BT
        // handshake could be overwritten by the BT callback firing one frame later.
        state.activeConnectAttemptId++
        // Reset the in-flight guard so a follow-up connect() (e.g. promoteToUsb's
        // autoConnectUsb after we tore down BT/LAN here) is not blocked.
        state.isConnecting = false

        coordinator.stopHeartbeat()
        scanController.stopAllSearches()

        try {
            state.activeCloudPrinter?.release(context)
        } catch (e: Exception) {
            Log.w("PRINTER_DEBUG", "Release during disconnect failed: ${e.message}")
        }
        state.reset()

        coordinator.printerStateFlow.value = PrinterState.Idle
        scanController.clearDiscovered()
    }
}

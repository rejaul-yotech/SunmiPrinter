package com.yotech.valtprinter.data.repository.internal

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.sunmi.externalprinterlibrary2.SearchMethod
import com.sunmi.externalprinterlibrary2.SunmiPrinterManager
import com.yotech.valtprinter.data.mapper.toDomain
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrinterState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles USB hot-plug takeover. When a USB printer is attached while another
 * transport is in use, the host is promoted to USB automatically (fewer cable
 * moving parts = most reliable path). On USB detach, the previously-displaced
 * device is restored.
 *
 * Both paths rely on [Coordinator]'s connect/disconnect/recovery primitives —
 * this class owns only the USB-specific policy and the `preferredFallbackDevice`
 * bookkeeping.
 */
internal class UsbTakeoverController(
    private val coordinator: Coordinator,
    private val connectionController: ConnectionController,
    private val scanController: ScanController
) {
    private val context: Context get() = coordinator.context
    private val state: ConnectionState get() = coordinator.state

    fun isUsbPrinterPresent(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val devices = usbManager?.deviceList?.values ?: return false
        return devices.any { device ->
            device.deviceClass == USB_CLASS_PRINTER || device.vendorId == VID_STMICRO
        }
    }

    suspend fun promoteToUsb(): Boolean {
        val currentDev = state.connectedDevice
        if (!isUsbPrinterPresent()) return false

        // Already on USB and the device is still present — nothing to do.
        if (currentDev?.connectionType == ConnectionType.USB && state.activeCloudPrinter != null) {
            return true
        }

        // Remember the displaced device so onUsbDetached() can fall back to it.
        if (currentDev != null && currentDev.connectionType != ConnectionType.USB) {
            state.preferredFallbackDevice = currentDev
        }

        if (currentDev != null) {
            // disconnect() clears preferredFallbackDevice as part of its reset,
            // so save and restore across the call.
            val rememberFallback = state.preferredFallbackDevice
            connectionController.disconnect()

            // Wait briefly for the state machine to settle to Idle before
            // kicking off the new USB scan-and-connect.
            withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
                while (isActive && coordinator.printerStateFlow.value !is PrinterState.Idle) {
                    delay(20L)
                }
            }

            state.preferredFallbackDevice = rememberFallback
            // disconnect() set isManualDisconnect = true so the SDK callback
            // doesn't trigger recovery; clear it now so the upcoming USB
            // handshake is treated as a normal connection lifecycle.
            state.isManualDisconnect = false
        }

        coordinator.printerStateFlow.value = PrinterState.AutoConnecting
        val success = autoConnectUsb()

        // BOUNCE-BACK: If USB promotion failed (e.g. device filter missed or
        // scan failed), immediately trigger recovery for the displaced printer
        // so we don't stay Idle.
        if (!success && state.preferredFallbackDevice != null) {
            val fallback = state.preferredFallbackDevice!!
            state.preferredFallbackDevice = null
            Log.w("PRINTER_DEBUG", "USB promotion failed; bouncing back to ${fallback.connectionType}")
            coordinator.requestRecovery(
                device = fallback,
                reason = RecoveryReason.SDK_DISCONNECT,
                details = "USB auto-connect failed; reverting to preferred fallback"
            )
        }
        return success
    }

    suspend fun onUsbDetached() {
        val currentDev = state.connectedDevice ?: return
        if (currentDev.connectionType != ConnectionType.USB) return
        val fallback = state.preferredFallbackDevice
        connectionController.disconnect()
        if (fallback != null) {
            state.isManualDisconnect = false
            state.preferredFallbackDevice = null
            coordinator.requestRecovery(
                device = fallback,
                reason = RecoveryReason.SDK_DISCONNECT,
                details = "USB detached; falling back to ${fallback.connectionType}"
            )
        }
    }

    suspend fun autoConnectUsb(): Boolean {
        coordinator.printerStateFlow.value = PrinterState.AutoConnecting
        scanController.stopAllSearches()
        scanController.internalPrintersMap.clear()

        var foundUsb = false
        try {
            SunmiPrinterManager.getInstance()
                .searchCloudPrinter(context, SearchMethod.USB) { printer ->
                    if (!foundUsb && printer != null) {
                        foundUsb = true
                        scanController.handlePrinterFound(printer)
                        val domainDev = scanController.internalPrintersMap.values
                            .firstOrNull()?.toDomain()
                        if (domainDev != null) {
                            coordinator.scope.launch {
                                coordinator.connect(domainDev)
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e("AUTO_CONNECT", "USB Search failed", e)
        }

        delay(USB_SEARCH_WINDOW_MS)
        SunmiPrinterManager.getInstance().stopSearch(context, SearchMethod.USB)

        if (!foundUsb) {
            coordinator.printerStateFlow.value = PrinterState.Idle
        }
        return foundUsb
    }

    private companion object {
        const val SETTLE_TIMEOUT_MS = 250L
        const val USB_SEARCH_WINDOW_MS = 1_500L
        const val USB_CLASS_PRINTER = 7
        const val VID_STMICRO = 1155 // 0x0483
    }
}

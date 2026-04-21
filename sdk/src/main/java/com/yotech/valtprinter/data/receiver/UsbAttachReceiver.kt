package com.yotech.valtprinter.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.yotech.valtprinter.sdk.ValtPrinterSdk
import kotlinx.coroutines.launch

/**
 * Broadcast receiver that promotes USB to the active transport whenever a
 * supported Sunmi USB printer is plugged in, and falls back when one is
 * unplugged.
 *
 * ## Why this lives in the foreground service, not the host activity
 *
 * The previous design relied on the host's `MainActivity` `<intent-filter>` for
 * `USB_DEVICE_ATTACHED` and `onNewIntent` to react. That had three weaknesses:
 *
 * 1. **Activity must be in foreground.** If the cashier had backgrounded the
 *    app, plugging in a USB printer did nothing until they opened the app.
 * 2. **No takeover.** The activity called `autoConnectUsb()` directly, which
 *    started a USB scan but did not release the existing BT/LAN session.
 *    The SDK ended up holding two `CloudPrinter` instances in inconsistent
 *    state.
 * 3. **No detach handling.** Pulling the USB cable left the SDK pointing at a
 *    defunct device until the heartbeat or recovery loop noticed.
 *
 * This receiver is registered in
 * [com.yotech.valtprinter.data.service.PrinterForegroundService.onCreate], which
 * is started at SDK init and on `BOOT_COMPLETED`, so USB attach is handled as
 * long as the foreground service is alive — which is the entire lifetime of the
 * print server.
 *
 * ## Dependency access
 *
 * Manifest-declared receivers cannot use constructor injection (Android
 * instantiates them via reflection). Dependencies are therefore resolved via
 * the typed [com.yotech.valtprinter.sdk.SdkComponent], obtained through one
 * [ValtPrinterSdk.component] call. No direct field-reach-through into the SDK.
 *
 * ## Filtering
 *
 * The receiver matches Sunmi printers via the same heuristic as
 * `res/xml/device_filter.xml` (USB Printer class 7, plus the STMicro VID 0x0483
 * used by Sunmi USB control firmware). Anything else is ignored.
 */
internal class UsbAttachReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: run {
            Log.w(TAG, "Received ${intent.action} with no UsbDevice extra; ignoring.")
            return
        }

        if (!isSupportedPrinter(device)) {
            Log.d(TAG, "Ignoring non-printer USB device vid=${device.vendorId} pid=${device.productId}")
            return
        }

        // Single typed reach-through into the SDK — see SdkComponent for why
        // this is the only sanctioned path from manifest-declared receivers.
        val component = try {
            ValtPrinterSdk.component()
        } catch (e: Exception) {
            Log.e(TAG, "SDK not initialized during USB event", e)
            return
        }

        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.i(TAG, "Sunmi USB printer attached — requesting promotion.")
                component.asyncScope.launch {
                    val ok = component.printerRepository.promoteToUsb()
                    Log.i(TAG, "promoteToUsb result=$ok")
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Log.i(TAG, "Sunmi USB printer detached — releasing if active.")
                component.asyncScope.launch {
                    component.printerRepository.onUsbDetached()
                }
            }

            else -> {
                Log.w(TAG, "Unexpected action ${intent.action}")
            }
        }
    }

    /**
     * Mirrors `res/xml/device_filter.xml`. Returns true if the device is a USB
     * printer (class 7) or matches one of the known Sunmi vendor IDs.
     */
    private fun isSupportedPrinter(device: UsbDevice): Boolean {
        if (device.deviceClass == USB_CLASS_PRINTER) return true
        // 1155 == 0x0483 (STMicro). Sunmi USB control firmware uses this VID.
        return device.vendorId == VID_STMICRO
    }

    private companion object {
        const val TAG = "USB_ATTACH_RECEIVER"
        const val USB_CLASS_PRINTER = 7
        const val VID_STMICRO = 1155
    }
}

package com.yotech.valtprinter.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yotech.valtprinter.core.util.NotificationHelper
import com.yotech.valtprinter.data.local.datastore.PrinterDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Validates precise Sunmi hardware events and serves as the Resumption Hook.
 */
class SunmiPrinterReceiver(
    private val printerDataStore: PrinterDataStore
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d("SUNMI_RECEIVER", "Hardware Event Detected: $action")
        
        CoroutineScope(Dispatchers.IO).launch {
            when (action) {
                "woyou.aidlservice.jiuv5.OUT_OF_PAPER_ACTION",
                "com.sunmi.extprinterservice.OUT_OF_PAPER_ACTION" -> {
                    printerDataStore.setPrinterPaused(true)
                    NotificationHelper.updateNotification(context, "Printer Fault: Out of Paper", 1)
                }
                "woyou.aidlservice.jiuv5.COVER_OPEN_ACTION",
                "com.sunmi.extprinterservice.COVER_OPEN_ACTION" -> {
                    printerDataStore.setPrinterPaused(true)
                    NotificationHelper.updateNotification(context, "Printer Fault: Cover Open", 1)
                }
                "woyou.aidlservice.jiuv5.OVER_HEATING_ACITON",
                "com.sunmi.extprinterservice.HOT_ACTION" -> {
                    printerDataStore.setPrinterPaused(true)
                    NotificationHelper.updateNotification(context, "Printer Fault: Overheated", 1)
                }
                "woyou.aidlservice.jiuv5.NORMAL_ACTION",
                "com.sunmi.extprinterservice.NORMAL_ACTION" -> {
                    // RESUMPTION HOOK
                    // Hardware resolved automatically un-pauses the ZERO-LOSS queue.
                    printerDataStore.setPrinterPaused(false)
                    NotificationHelper.updateNotification(context, "Printer Ready. Resuming...", 1)
                }
            }
        }
    }
}

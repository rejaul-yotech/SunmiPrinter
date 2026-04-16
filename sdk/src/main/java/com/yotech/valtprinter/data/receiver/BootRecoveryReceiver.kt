package com.yotech.valtprinter.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yotech.valtprinter.data.service.PrinterForegroundService

/**
 * Ensures that the Printer Hub automatically resumes after a power cut or system reboot.
 */
class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, PrinterForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

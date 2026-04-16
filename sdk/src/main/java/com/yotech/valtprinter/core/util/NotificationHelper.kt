package com.yotech.valtprinter.core.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Handles the "Elite" persistent notification for our Printer Hub.
 * Provides real-time visibility of the printer's status.
 */
object NotificationHelper {
    private const val CHANNEL_ID = "printer_service_channel"
    private const val CHANNEL_NAME = "Valt Printer Hub"
    private const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Valt Printer server alive for zero-loss printing."
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun getServiceNotification(
        context: Context,
        status: String = "Monitoring Queue...",
        queueCount: Int = 0
    ): Notification {
        val contentText = if (queueCount > 0) {
            "$status ($queueCount jobs pending)"
        } else {
            status
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Valt Printer Server Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun updateNotification(context: Context, status: String, queueCount: Int) {
        val notification = getServiceNotification(context, status, queueCount)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun getNotificationId() = NOTIFICATION_ID
}

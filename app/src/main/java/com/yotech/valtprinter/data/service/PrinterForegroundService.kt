package com.yotech.valtprinter.data.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.yotech.valtprinter.core.util.NotificationHelper
import com.yotech.valtprinter.data.queue.QueueDispatcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The "Elite" sticky service that hosts the print queue dispatcher.
 * Ensures that ValtPrinter is always ready even if the main activity is closed.
 */
@AndroidEntryPoint
class PrinterForegroundService : Service() {

    @Inject
    lateinit var queueDispatcher: QueueDispatcher

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
        val notification = NotificationHelper.getServiceNotification(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.getNotificationId(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NotificationHelper.getNotificationId(), notification)
        }

        // Start the engine
        queueDispatcher.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky ensures the OS tries to restart us if killed for memory
        return START_STICKY
    }

    override fun onDestroy() {
        queueDispatcher.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

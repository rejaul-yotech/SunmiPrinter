package com.yotech.valtprinter.data.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import android.hardware.usb.UsbManager
import com.yotech.valtprinter.core.util.NotificationHelper
import com.yotech.valtprinter.data.queue.QueueDispatcher
import com.yotech.valtprinter.data.receiver.SunmiPrinterReceiver
import com.yotech.valtprinter.data.receiver.UsbAttachReceiver
import com.yotech.valtprinter.sdk.ValtPrinterSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Sticky foreground service that hosts the [QueueDispatcher].
 * Kept alive by the OS so print jobs survive activity destruction.
 * Dependencies are resolved from [ValtPrinterSdk] (manual DI — no Hilt).
 */
internal class PrinterForegroundService : Service() {

    private lateinit var queueDispatcher: QueueDispatcher
    private lateinit var sunmiReceiver: SunmiPrinterReceiver
    private lateinit var usbAttachReceiver: UsbAttachReceiver
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        val sdk = ValtPrinterSdk.get()
        queueDispatcher = sdk.queueDispatcher
        val printerDataStore = sdk.printerDataStore
        val printerRepository = sdk.printerRepository

        sunmiReceiver = SunmiPrinterReceiver(printerDataStore)
        val filter = IntentFilter().apply {
            addAction("woyou.aidlservice.jiuv5.OUT_OF_PAPER_ACTION")
            addAction("com.sunmi.extprinterservice.OUT_OF_PAPER_ACTION")
            addAction("woyou.aidlservice.jiuv5.COVER_OPEN_ACTION")
            addAction("com.sunmi.extprinterservice.COVER_OPEN_ACTION")
            addAction("woyou.aidlservice.jiuv5.OVER_HEATING_ACITON")
            addAction("com.sunmi.extprinterservice.HOT_ACTION")
            addAction("woyou.aidlservice.jiuv5.NORMAL_ACTION")
            addAction("com.sunmi.extprinterservice.NORMAL_ACTION")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sunmiReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sunmiReceiver, filter)
        }

        // USB attach/detach takeover lives on the service so it works even when
        // the host activity is backgrounded. The receiver delegates to the
        // service scope so promotion does not block onReceive's 10s budget.
        usbAttachReceiver = UsbAttachReceiver(
            repository = printerRepository,
            scope = serviceScope
        )
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbAttachReceiver, usbFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachReceiver, usbFilter)
        }

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

        queueDispatcher.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        queueDispatcher.stop()
        serviceScope.cancel()
        try { unregisterReceiver(sunmiReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbAttachReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** No binding — host apps interact via [ValtPrinterSdk], not IPC. */
    override fun onBind(intent: Intent?): IBinder? = null
}

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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The "Elite" sticky service that hosts the print queue dispatcher.
 * Ensures that ValtPrinter is always ready even if the main activity is closed.
 */
@AndroidEntryPoint
class PrinterForegroundService : Service() {

    @Inject
    lateinit var queueDispatcher: QueueDispatcher

    @Inject
    lateinit var printDao: com.yotech.valtprinter.data.local.dao.PrintDao

    @Inject
    lateinit var callbackManager: com.yotech.valtprinter.domain.util.PrinterCallbackManager

    @Inject
    lateinit var printerDataStore: com.yotech.valtprinter.data.local.datastore.PrinterDataStore

    private lateinit var sunmiReceiver: com.yotech.valtprinter.data.receiver.SunmiPrinterReceiver

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val binder = object : com.yotech.valtprinter.IPrinterService.Stub() {
        override fun submitPrintJob(jobId: String?, payloadJson: String?, isPriority: Boolean): String? {
            if (jobId == null || payloadJson == null) return null
            
            return kotlinx.coroutines.runBlocking {
                // Idempotency check
                val existing = printDao.getJobByExternalId(jobId)
                if (existing != null) return@runBlocking "EXISTING_${existing.id}"

                val entity = com.yotech.valtprinter.data.local.entity.PrintJobEntity(
                    externalJobId = jobId,
                    payloadJson = payloadJson,
                    isPriority = isPriority,
                    status = com.yotech.valtprinter.data.local.entity.PrintStatus.PENDING
                )
                val id = printDao.insertPrintJob(entity)
                if (id > 0) "VALT_TX_$id" else null
            }
        }

        override fun getQueueCount(): Int {
            return kotlinx.coroutines.runBlocking {
                printDao.getQueueCount()
            }
        }

        override fun clearCompletedLogs() {
            serviceScope.launch {
                printDao.deleteOldLogs(System.currentTimeMillis())
            }
        }

        override fun registerCallback(callback: com.yotech.valtprinter.IPrinterCallback?) {
            if (callback != null) callbackManager.list.register(callback)
        }

        override fun unregisterCallback(callback: com.yotech.valtprinter.IPrinterCallback?) {
            if (callback != null) callbackManager.list.unregister(callback)
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        sunmiReceiver = com.yotech.valtprinter.data.receiver.SunmiPrinterReceiver(printerDataStore)
        val filter = android.content.IntentFilter().apply {
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
            registerReceiver(sunmiReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(sunmiReceiver, filter)
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

        // Start the engine
        queueDispatcher.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky ensures the OS tries to restart us if killed for memory
        return START_STICKY
    }

    override fun onDestroy() {
        queueDispatcher.stop()
        serviceScope.cancel()
        unregisterReceiver(sunmiReceiver)
        callbackManager.list.kill()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

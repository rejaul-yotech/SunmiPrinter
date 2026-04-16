package com.yotech.valtprinter.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yotech.valtprinter.sdk.ValtPrinterSdk
import java.io.File

/**
 * Periodic maintenance worker — Log TTL pruning + Storage Sentinel.
 * Dependencies resolved from [ValtPrinterSdk] (no Hilt/AssistedInject).
 */
internal class CleanupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.d("CLEANUP_WORKER", "Starting Daily Household Maintenance...")

            val sdk = ValtPrinterSdk.get()
            val printDao = sdk.printDao
            val printerDataStore = sdk.printerDataStore

            // 1. Log Pruning (TTL)
            val ttlDays = printerDataStore.getLogTtlDays()
            val expirationTimestamp =
                System.currentTimeMillis() - (ttlDays.toLong() * 24 * 60 * 60 * 1000)
            printDao.deleteOldLogs(expirationTimestamp)

            // 2. Storage Sentinel (Emergency Cleanup)
            val internalStorage = File(applicationContext.filesDir.absolutePath)
            val totalSpace = internalStorage.totalSpace
            val freeSpace = internalStorage.freeSpace
            val usedPercent =
                ((totalSpace - freeSpace).toDouble() / totalSpace.toDouble()) * 100

            if (usedPercent > 95) {
                Log.w("CLEANUP_WORKER", "STORAGE SENTINEL: Disk at $usedPercent%. Aggressive pruning.")
                val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                printDao.deleteOldLogs(oneDayAgo)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("CLEANUP_WORKER", "Maintenance Failed: ${e.message}")
            Result.retry()
        }
    }
}

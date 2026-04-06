package com.yotech.valtprinter.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yotech.valtprinter.data.local.dao.PrintDao
import com.yotech.valtprinter.data.local.datastore.PrinterDataStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * The "Gold Standard" Cleanup Engine.
 * Responsible for Log TTL pruning and Storage Sentinel (Emergency Disk Cleanup).
 */
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val printDao: PrintDao,
    private val printerDataStore: PrinterDataStore
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.d("CLEANUP_WORKER", "Starting Daily Household Maintenance...")

            // 1. Log Pruning (TTL)
            val ttlDays = printerDataStore.getLogTtlDays()
            val expirationTimestamp = System.currentTimeMillis() - (ttlDays.toLong() * 24 * 60 * 60 * 1000)
            printDao.deleteOldLogs(expirationTimestamp)

            // 2. Storage Sentinel (Emergency Cleanup)
            val internalStorage = File(applicationContext.filesDir.absolutePath)
            val totalSpace = internalStorage.totalSpace
            val freeSpace = internalStorage.freeSpace
            val usedPercent = ((totalSpace - freeSpace).toDouble() / totalSpace.toDouble()) * 100

            if (usedPercent > 95) {
                Log.w("CLEANUP_WORKER", "STORAGE SENTINEL: Disk at ${usedPercent}%. Performing aggressive pruning.")
                // Delete everything older than 1 day if we are running out of space
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

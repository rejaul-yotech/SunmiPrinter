package com.yotech.valtprinter.data.queue

import android.content.Context
import android.util.Log
import com.yotech.valtprinter.core.util.BitmapRenderer
import com.yotech.valtprinter.core.util.NotificationHelper
import com.yotech.valtprinter.data.local.dao.PrintDao
import com.yotech.valtprinter.data.local.datastore.PrinterDataStore
import com.yotech.valtprinter.data.local.entity.PrintStatus
import com.yotech.valtprinter.domain.model.PrintResult
import com.yotech.valtprinter.domain.repository.PrinterRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Gold Standard" Queue Engine. 
 * Handles priority, state persistence, and hardware resilience.
 */
@Singleton
class QueueDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val printDao: PrintDao,
    private val printerDataStore: PrinterDataStore,
    private val printerRepository: PrinterRepository
) {
    @Inject
    lateinit var payloadParser: com.yotech.valtprinter.domain.util.PayloadParser

    @Inject
    lateinit var callbackManager: com.yotech.valtprinter.domain.util.PrinterCallbackManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dispatcherJob: Job? = null
    private val CHUNK_SIZE_PX = 400 // Atomic slice height for persistence

    fun start(serviceContext: Context) {
        if (dispatcherJob?.isActive == true) return

        dispatcherJob = scope.launch {
            while (isActive) {
                try {
                    val nextJob = printDao.getNextJob()
                    if (nextJob == null) {
                        NotificationHelper.updateNotification(serviceContext, "Monitoring Queue...", 0)
                        delay(2000)
                        continue
                    }

                    // 0. Global Pause Check
                    if (printerDataStore.isPrinterPaused()) {
                        NotificationHelper.updateNotification(serviceContext, "Queue Paused (Manual/Auto)", 0)
                        delay(5000)
                        continue
                    }

                    // 1. Initial State Check (Heuristics)
                    val printer = printerRepository.getActiveCloudPrinter()
                    if (printer == null) {
                        NotificationHelper.updateNotification(serviceContext, "Error: Printer Not Connected", 1)
                        delay(5000)
                        continue
                    }

                    // 2. Parse Payload safely
                    val printPayload = payloadParser.parse(nextJob.payloadJson)

                    // 3. Mark as Processing
                    printDao.updateStatus(nextJob.id, PrintStatus.PROCESSING)

                    val logMessage = when (printPayload) {
                        is com.yotech.valtprinter.domain.model.PrintPayload.Billing -> "Printing Order #${printPayload.data.orderId}"
                        is com.yotech.valtprinter.domain.model.PrintPayload.RawText -> "Printing Raw Text Document"
                        is com.yotech.valtprinter.domain.model.PrintPayload.Unknown -> "Printing Unknown Payload Format"
                    }
                    NotificationHelper.updateNotification(serviceContext, logMessage, 1)

                    // 4. THE CHUNKED RESILIENT LOOP
                    // Logic: We render and flush slices. Upon every hardware success, we update the DB.
                    var currentChunk = nextJob.currentChunkIndex
                    var isFinished = false
                    var lastError: String? = null

                    while (!isFinished && isActive) {
                        val captureView = printerRepository.getCaptureView()
                        if (captureView == null) {
                            lastError = "Capture View is null. UI not ready for headless rendering."
                            break
                        }

                        // Render exactly one slice based on our current persistent index
                        val bitmapChunk = BitmapRenderer.renderReceiptChunk(
                            parentView = captureView, // Now safely non-null
                            chunkIndex = currentChunk,
                            chunkSizePx = CHUNK_SIZE_PX
                        ) {
                            when (printPayload) {
                                is com.yotech.valtprinter.domain.model.PrintPayload.Billing -> {
                                    com.yotech.valtprinter.ui.receipt.PosPrintingScreen(data = printPayload.data, isScrollEnabled = false)
                                }
                                is com.yotech.valtprinter.domain.model.PrintPayload.RawText -> {
                                    com.yotech.valtprinter.ui.receipt.RawTextScreen(text = printPayload.text)
                                }
                                is com.yotech.valtprinter.domain.model.PrintPayload.Unknown -> {
                                    com.yotech.valtprinter.ui.receipt.RawTextScreen(text = printPayload.rawJson)
                                }
                            }
                        }

                        if (bitmapChunk == null) {
                            isFinished = true
                            break
                        }

                        // Physical Flush
                        val result = printerRepository.printChunk(bitmapChunk, isLastChunk = false)

                        if (result is PrintResult.Success) {
                            currentChunk++
                            printDao.updateChunkProgress(nextJob.id, currentChunk)
                            printerDataStore.updateAccumulatedHeight(bitmapChunk.height)
                        } else {
                            lastError = (result as PrintResult.Failure).reason
                            break
                        }
                    }

                    // 5. Finalization (Cut & Status Update)
                    if (isFinished) {
                        printerRepository.finalCut() 
                        printDao.updateStatus(nextJob.id, PrintStatus.COMPLETED)
                        callbackManager.notifySuccess(nextJob.externalJobId)
                    } else if (lastError?.contains("Paper Out", ignoreCase = true) == true) {
                        // "Elite" Policy: On Paper-Out mid-print, mark for Full Reprint
                        printDao.updateChunkProgress(nextJob.id, 0) // Reset progress
                        printDao.updateStatus(nextJob.id, PrintStatus.INTERRUPTED)
                        NotificationHelper.updateNotification(serviceContext, "Paused: Out of Paper", 1)
                        suspendQueue()
                    } else {
                        printDao.updateStatus(nextJob.id, PrintStatus.FAILED)
                        callbackManager.notifyFailed(nextJob.externalJobId, lastError ?: "Unknown physical hardware error")
                    }

                } catch (e: Exception) {
                    Log.e("QUEUE_SERVER", "Engine Error: ${e.message}", e)
                    delay(5000)
                }
            }
        }
    }

    private suspend fun suspendQueue() {
        printerDataStore.setPrinterPaused(true)
    }

    fun stop() {
        dispatcherJob?.cancel()
        dispatcherJob = null
    }
}

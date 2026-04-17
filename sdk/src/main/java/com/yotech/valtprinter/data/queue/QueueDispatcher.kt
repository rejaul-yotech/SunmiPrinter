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
import com.yotech.valtprinter.domain.repository.RenderRepository
import kotlinx.coroutines.*

/**
 * The "Gold Standard" Queue Engine. 
 * Handles priority, state persistence, and hardware resilience.
 */

internal class QueueDispatcher(
    private val context: Context,
    private val printDao: PrintDao,
    private val printerDataStore: PrinterDataStore,
    private val printerRepository: PrinterRepository,
    private val renderRepository: RenderRepository,
    private val payloadParser: com.yotech.valtprinter.domain.util.PayloadParser,
    private val callbackManager: com.yotech.valtprinter.domain.util.PrinterCallbackManager
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dispatcherJob: Job? = null
    private var stateMonitorJob: Job? = null
    private val CHUNK_SIZE_PX = 400 // Atomic slice height for persistence

    private var wasAutoPaused = false

    fun start(serviceContext: Context) {
        if (dispatcherJob?.isActive == true) return
        
        // Start monitoring state for Auto-Resume
        startStateMonitoring()

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

                    // 1. Initial State Check — abstract readiness probe; no Sunmi types leak here.
                    if (!printerRepository.isPrinterReady()) {
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

                    // Initialize the SDK buffer BEFORE the first chunk.
                    // This clears any stale commands from a previous incomplete job on USB/BT.
                    // For LAN this is a no-op (raw socket handles each chunk atomically).
                    val initResult = printerRepository.initPrintJob()
                    if (initResult is PrintResult.Failure) {
                        lastError = "Buffer init failed: ${initResult.reason}"
                    } else {
                        while (!isFinished && isActive) {
                            val captureView = renderRepository.getCaptureView()
                            if (captureView == null) {
                                lastError = "Capture View is null. UI not ready for headless rendering."
                                break
                            }

                            // Render exactly one slice based on our current persistent index
                            val bitmapChunk = BitmapRenderer.renderReceiptChunk(
                                parentView = captureView,
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

                            // Physical Flush: buffer chunk in SDK (no commit yet for USB/BT)
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
                    } // end else (initPrintJob succeeded)

                    // 5. Finalization (Cut & Status Update)
                    if (isFinished) {
                        // For USB/BT: commitAndCut sends the entire buffered receipt atomically.
                        // For LAN: no-op (raw socket already sent feed+cut per chunk).
                        val cutResult = printerRepository.finalCut()
                        if (cutResult is PrintResult.Success) {
                            // keep isFinished=true and continue to completion branch below
                        } else {
                            lastError = (cutResult as PrintResult.Failure).reason
                            isFinished = false
                        }
                    }

                    if (isFinished && lastError == null) {
                        printDao.updateStatus(nextJob.id, PrintStatus.COMPLETED)
                        callbackManager.notifySuccess(nextJob.externalJobId)
                    } else if (isConnectivityLoss(lastError)) {
                        // If transport/session drops (BT power-off, unplug, network break),
                        // keep the job resumable and pause until self-healing reconnects.
                        printDao.updateStatus(nextJob.id, PrintStatus.INTERRUPTED)
                        NotificationHelper.updateNotification(serviceContext, "Printer offline. Waiting for auto-reconnect...", 1)
                        wasAutoPaused = true
                        suspendQueue()
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

    private fun isConnectivityLoss(lastError: String?): Boolean {
        if (lastError.isNullOrBlank()) return false
        val lower = lastError.lowercase()
        return lower.contains("not connected")
                || lower.contains("printer null")
                || lower.contains("disconnect")
                || lower.contains("socket")
                || lower.contains("timeout")
                || lower.contains("commitcut error")
                || (lower.contains("unknown") && lower.contains("commit"))
    }

    private fun startStateMonitoring() {
        stateMonitorJob?.cancel()
        stateMonitorJob = scope.launch {
            printerRepository.printerState.collect { state ->
                when (state) {
                    is com.yotech.valtprinter.domain.model.PrinterState.Connected -> {
                        if (wasAutoPaused) {
                            Log.d("QUEUE_DISPATCHER", "Self-Healing: Printer reconnected. Resuming queue...")
                            printerDataStore.setPrinterPaused(false)
                            wasAutoPaused = false
                        }
                    }
                    is com.yotech.valtprinter.domain.model.PrinterState.Reconnecting -> {
                        if (!printerDataStore.isPrinterPaused()) {
                            Log.w("QUEUE_DISPATCHER", "Printer lost mid-operation. Auto-pausing queue.")
                            printerDataStore.setPrinterPaused(true)
                            wasAutoPaused = true
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun stop() {
        dispatcherJob?.cancel()
        stateMonitorJob?.cancel()
        dispatcherJob = null
        stateMonitorJob = null
    }
}

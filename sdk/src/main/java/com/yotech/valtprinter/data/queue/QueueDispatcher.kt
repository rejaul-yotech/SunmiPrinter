package com.yotech.valtprinter.data.queue

import android.content.Context
import android.util.Log
import com.yotech.valtprinter.core.util.BitmapRenderer
import com.yotech.valtprinter.core.util.NotificationHelper
import com.yotech.valtprinter.data.local.dao.PrintDao
import com.yotech.valtprinter.data.local.datastore.PrinterDataStore
import com.yotech.valtprinter.data.local.entity.PrintStatus
import com.yotech.valtprinter.data.repository.internal.TransportErrorClassifier
import com.yotech.valtprinter.domain.model.ConnectionType
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

    // Private: the print loop's scope is an implementation detail. Other SDK
    // components that need a background scope should use SdkComponent.asyncScope,
    // which has independent cancellation semantics. Sharing this scope would
    // entangle USB-attach / state-monitor work with the print-loop lifecycle.
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

                    // 3. Mark as Processing. If the pick-up was an INTERRUPTED
                    // job resuming after transport loss / paper-out, re-emit
                    // Enqueued so the host's flow collector can distinguish
                    // "new job starting" from stale Interrupted UI state.
                    if (nextJob.status == PrintStatus.INTERRUPTED) {
                        callbackManager.emitEnqueued(nextJob.externalJobId)
                    }
                    printDao.updateStatus(nextJob.id, PrintStatus.PROCESSING)

                    val logMessage = when (printPayload) {
                        is com.yotech.valtprinter.domain.model.PrintPayload.Billing -> "Printing Billing Order #${printPayload.data.orderId}"
                        is com.yotech.valtprinter.domain.model.PrintPayload.KitchenReceipt -> "Printing Kitchen Order #${printPayload.data.title}"
                        is com.yotech.valtprinter.domain.model.PrintPayload.RawText -> "Printing Raw Text Document"
                        is com.yotech.valtprinter.domain.model.PrintPayload.Unknown -> "Printing Unknown Payload Format"
                    }
                    NotificationHelper.updateNotification(serviceContext, logMessage, 1)

                    // 4. THE CHUNKED RESILIENT LOOP
                    // Render the FULL receipt once (deterministic, single composition),
                    // then slice the resulting bitmap and flush each slice. On hardware
                    // success we update the DB so a mid-job crash can resume from the
                    // last persisted slice (USB/BT only — LAN forces a full reprint,
                    // see `isConnectivityLoss` branch below).
                    var currentChunk = nextJob.currentChunkIndex
                    var isFinished = false
                    var lastError: String? = null
                    var fullReceiptBitmap: android.graphics.Bitmap? = null

                    // Initialize the SDK buffer / open the LAN session BEFORE the first chunk.
                    // This clears any stale commands from a previous incomplete job on USB/BT,
                    // and opens the single TCP session that the entire LAN job will share.
                    val initResult = printerRepository.initPrintJob()
                    if (initResult is PrintResult.Failure) {
                        lastError = "Buffer init failed: ${initResult.reason}"
                    } else {
                        val captureView = renderRepository.getCaptureView()
                        if (captureView == null) {
                            lastError = "Capture View is null. UI not ready for headless rendering."
                        } else {
                            // ONE composition per job. Same pixels for every slice.
                            fullReceiptBitmap = BitmapRenderer.renderFullReceiptBitmap(
                                parentView = captureView
                            ) {
                                when (printPayload) {
                                    is com.yotech.valtprinter.domain.model.PrintPayload.Billing -> {
                                        com.yotech.valtprinter.ui.receipt.PosPrintingScreen(
                                            data = printPayload.data
                                        )
                                    }
                                    is com.yotech.valtprinter.domain.model.PrintPayload.KitchenReceipt -> {
                                        com.yotech.valtprinter.ui.receipt.KitchenReceipt(data = printPayload.data)
                                    }
                                    is com.yotech.valtprinter.domain.model.PrintPayload.RawText -> {
                                        com.yotech.valtprinter.ui.receipt.RawTextScreen(text = printPayload.text)
                                    }
                                    is com.yotech.valtprinter.domain.model.PrintPayload.Unknown -> {
                                        com.yotech.valtprinter.ui.receipt.RawTextScreen(text = printPayload.rawJson)
                                    }
                                }
                            }

                            // Capture into a local val so Kotlin can smart-cast through the
                            // slice loop without an explicit `!!`. `fullReceiptBitmap` is a
                            // `var` for the later recycle in the outer finally-like block.
                            val composedBitmap = fullReceiptBitmap
                            if (composedBitmap == null) {
                                lastError = "Composed receipt has zero height — empty payload?"
                            } else {
                                while (!isFinished && isActive) {
                                    val slice = BitmapRenderer.sliceBitmap(
                                        full = composedBitmap,
                                        chunkIndex = currentChunk,
                                        chunkSizePx = CHUNK_SIZE_PX
                                    )
                                    if (slice == null) {
                                        isFinished = true
                                        break
                                    }

                                    // Physical Flush: buffer chunk (USB/BT) or stream chunk (LAN).
                                    // NOTE: do NOT recycle `slice` here — Bitmap.createBitmap(src, x, y, w, h)
                                    // may share pixel storage with `fullReceiptBitmap` on some platforms,
                                    // and recycling would corrupt subsequent slices.
                                    val result = printerRepository.printChunk(slice, isLastChunk = false)

                                    if (result is PrintResult.Success) {
                                        currentChunk++
                                        printDao.updateChunkProgress(nextJob.id, currentChunk)
                                        printerDataStore.updateAccumulatedHeight(slice.height)
                                        // Progress event — host correlates by externalJobId.
                                        // totalChunks is null because the dispatcher does not
                                        // pre-compute the total (slices are produced lazily
                                        // until sliceBitmap returns null).
                                        callbackManager.emitPrinting(
                                            externalJobId = nextJob.externalJobId,
                                            chunkIndex = currentChunk,
                                            totalChunks = null
                                        )
                                    } else {
                                        lastError = (result as PrintResult.Failure).reason
                                        break
                                    }
                                }
                            }
                        }
                    } // end else (initPrintJob succeeded)

                    // Always release the full-receipt bitmap before leaving the
                    // job — it can be several megabytes for a long receipt.
                    fullReceiptBitmap?.recycle()
                    fullReceiptBitmap = null

                    // 5. Finalization (Cut & Status Update)
                    if (isFinished) {
                        // For USB/BT: commitAndCut sends the entire buffered receipt atomically.
                        // For LAN: delivers the single feed+cut and closes the TCP session.
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
                        //
                        // LAN exception: a half-sent ESC/POS image cannot be resumed —
                        // the printer has already advanced through whatever bytes arrived
                        // before the drop, and the next session must start from chunk 0
                        // to produce a coherent receipt. USB/BT buffer the entire job in
                        // the SDK's transaction buffer, so they CAN safely resume from
                        // the last persisted chunk index.
                        if (printerRepository.activeConnectionType() == ConnectionType.LAN) {
                            printDao.updateChunkProgress(nextJob.id, 0)
                        }
                        printDao.updateStatus(nextJob.id, PrintStatus.INTERRUPTED)
                        NotificationHelper.updateNotification(serviceContext, "Printer offline. Waiting for auto-reconnect...", 1)
                        callbackManager.emitInterrupted(
                            nextJob.externalJobId,
                            lastError ?: "Transport loss"
                        )
                        wasAutoPaused = true
                        suspendQueue()
                    } else if (lastError?.contains("Paper Out", ignoreCase = true) == true) {
                        // "Elite" Policy: On Paper-Out mid-print, mark for Full Reprint
                        printDao.updateChunkProgress(nextJob.id, 0) // Reset progress
                        printDao.updateStatus(nextJob.id, PrintStatus.INTERRUPTED)
                        NotificationHelper.updateNotification(serviceContext, "Paused: Out of Paper", 1)
                        callbackManager.emitInterrupted(
                            nextJob.externalJobId,
                            lastError ?: "Paper out"
                        )
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

    /**
     * Delegates to [TransportErrorClassifier] — the single source of truth for
     * classifying print failures as transport loss vs hardware fault. This
     * used to duplicate the classifier's substring logic; keeping it a thin
     * alias preserves call-site readability without drifting behaviour.
     */
    private fun isConnectivityLoss(lastError: String?): Boolean =
        TransportErrorClassifier.isTransportLoss(lastError)

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

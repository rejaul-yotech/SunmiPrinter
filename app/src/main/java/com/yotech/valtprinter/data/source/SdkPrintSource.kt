package com.yotech.valtprinter.data.source

import android.graphics.Bitmap
import android.util.Log
import com.sunmi.externalprinterlibrary2.ResultCallback
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.sunmi.externalprinterlibrary2.style.AlignStyle
import com.sunmi.externalprinterlibrary2.style.CloudPrinterStatus
import com.sunmi.externalprinterlibrary2.style.ImageAlgorithm
import com.yotech.valtprinter.domain.model.PrintResult
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SdkPrintSource @Inject constructor() {

    /**
     * Clears the SDK's internal transaction buffer to guarantee a clean slate.
     *
     * MUST be called once before the first [addToBuffer] call for any new print job.
     * Without this, stale commands from a previous failed or incomplete job accumulate
     * in the buffer and are committed together with the new job, causing:
     * - Corrupted / duplicate content printed before the real receipt
     * - Off-sync line positioning, leading to premature paper cuts on USB/BT
     */
    fun initBuffer(printer: CloudPrinter) {
        try {
            printer.commitTransBuffer(object : ResultCallback {
                override fun onComplete() {
                    Log.d("SDK_PRINT", "Buffer initialized — clean slate for new job")
                }

                override fun onFailed(p0: CloudPrinterStatus?) {
                    Log.d("SDK_PRINT", "Buffer initialization failed — $p0")
                }
            })
        } catch (e: Exception) {
            Log.e("SDK_PRINT", "initTransBuffer failed: ${e.message}", e)
        }
    }

    /**
     * Adds a bitmap chunk to the SDK's internal transaction buffer WITHOUT committing to hardware.
     *
     * ## Why no commitTransBuffer here?
     * Calling commitTransBuffer after each chunk creates a race condition on USB/BT:
     * - The SDK callback fires when data is QUEUED in the kernel driver buffer,
     *   **not** when the printer has physically advanced the paper through those pixels.
     * - If finalCut() is called immediately after the callback, the cut command arrives
     *   at the printer mid-print, slicing the receipt prematurely.
     *
     * The correct pattern is: buffer ALL chunks → one atomic [commitAndCut] at the end.
     */
    suspend fun addToBuffer(printer: CloudPrinter, bitmap: Bitmap) {
        try {
            printer.setAlignment(AlignStyle.LEFT)
            printer.printImage(bitmap, ImageAlgorithm.BINARIZATION)
            Log.d(
                "SDK_PRINT",
                "Chunk buffered (${bitmap.width}x${bitmap.height}px) — not yet committed"
            )
        } catch (e: Exception) {
            Log.e("SDK_PRINT", "Buffer add failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Finalizes the entire print job with a single atomic commit to hardware.
     *
     * This is the ONLY [commitTransBuffer] call for any USB/BT print job. It includes:
     * 1. [lineFeedCount] lines of paper advance — ensures the last printed pixel is
     *    physically past the cutter blade before the cut signal is sent.
     * 2. A full paper cut.
     * 3. An awaitable callback — we **suspend** until the printer confirms receipt,
     *    guaranteeing the cut happens only after all buffered content is delivered.
     *
     * @param lineFeedCount Lines to feed before cutting. Default 6 (~18mm at 30 dots/line),
     *                      which clears the print head-to-cutter gap on the Sunmi NT311.
     */
    suspend fun commitAndCut(printer: CloudPrinter, lineFeedCount: Int = 6): PrintResult {
        val completable = CompletableDeferred<PrintResult>()
        try {
            printer.lineFeed(lineFeedCount)
            printer.cutPaper(true)
            printer.commitTransBuffer(object : ResultCallback {
                override fun onComplete() {
                    Log.d("SDK_PRINT", "commitAndCut: printer confirmed receipt ✔")
                    completable.complete(PrintResult.Success)
                }

                override fun onFailed(err: CloudPrinterStatus?) {
                    val msg = "CommitCut Error: ${err?.name ?: "Unknown"}"
                    Log.e("SDK_PRINT", msg)
                    completable.complete(PrintResult.Failure(msg))
                }
            })
        } catch (e: Exception) {
            completable.complete(PrintResult.Failure("CommitCut Exception: ${e.message}"))
        }
        return completable.await()
    }

    /**
     * Prints a single bitmap as a complete standalone job (init + buffer + commit + cut).
     * Used for single-shot prints (e.g. direct test prints from the UI).
     */
    suspend fun printBitmap(printer: CloudPrinter, bitmap: Bitmap): PrintResult {
        return try {
            initBuffer(printer) // Clear any stale content before a fresh job
            addToBuffer(printer, bitmap)
            commitAndCut(printer)
        } catch (e: Exception) {
            PrintResult.Failure("Print Exception: ${e.message}")
        }
    }

    /**
     * Legacy entry point retained for backwards compatibility.
     *
     * - [isLastChunk] = false → buffers the image only (no commit, safe for chunked receipts)
     * - [isLastChunk] = true  → buffers + [commitAndCut] (atomic, awaitable)
     */
    suspend fun printBitmapChunk(
        printer: CloudPrinter,
        bitmap: Bitmap,
        isLastChunk: Boolean
    ): PrintResult {
        return if (isLastChunk) {
            try {
                // Single-shot print: init buffer first to guarantee a clean slate,
                // then buffer the image and commit+cut in one atomic operation.
                initBuffer(printer)
                addToBuffer(printer, bitmap)
                commitAndCut(printer)
            } catch (e: Exception) {
                PrintResult.Failure("Chunk Exception: ${e.message}")
            }
        } else {
            // Multi-chunk: only buffer. initBuffer() must already have been called
            // by the caller (QueueDispatcher via PrinterRepository.initPrintJob())
            // before the first chunk to guarantee a clean SDK buffer.
            try {
                addToBuffer(printer, bitmap)
                PrintResult.Success
            } catch (e: Exception) {
                PrintResult.Failure("Buffer Add Exception: ${e.message}")
            }
        }
    }
}

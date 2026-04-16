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


class SdkPrintSource constructor() {

    /**
     * Clears the SDK's internal transaction buffer and configures the printer for
     * edge-to-edge receipt printing — mirroring every pre-image command that LAN sends.
     *
     * MUST be called once before the first [addToBuffer] call for any new print job.
     *
     * ## Why backfeed?
     * After the previous receipt's `dotsFeed(96)` + `cutPaper()`, the paper sits 12 mm past
     * the cutter blade. Without pulling it back, the next receipt starts with 12 mm of blank
     * paper at the top. LAN sends `GS ( K` (enable auto-backfeed) + `ESC K 96` (pull back
     * 12 mm) before every chunk. We replicate this once at buffer init via [appendRawData].
     */
    fun initBuffer(printer: CloudPrinter): Boolean {
        try {
            printer.clearTransBuffer()

            // --- Mirror LAN's pre-image setup (RawSocketPrintSource lines 36–56) ---

            // 1. Enable auto-backfeed: GS ( K pL pH m n
            printer.appendRawData(byteArrayOf(0x1D, 0x28, 0x4B, 0x02, 0x00, 0x02, 0x02))

            // 2. Manual backfeed 96 dots (12 mm): ESC K n
            //    Pulls paper back so the first pixel prints flush with the cut edge.
            printer.appendRawData(byteArrayOf(0x1B, 0x4B, 0x60))

            // 3. Left margin = 0 and printable width = 576 dots (full 80 mm head)
            printer.setLeftSpace(0)
            printer.setPrintWidth(576)

            Log.d("SDK_PRINT", "Buffer cleared + backfeed + margins set — clean slate")
            return true
        } catch (e: Exception) {
            Log.e("SDK_PRINT", "initBuffer failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Adds a bitmap chunk to the SDK's internal transaction buffer WITHOUT committing to hardware.
     *
     * ## Why setLineSpacing(0) before printImage?
     * The SDK printer carries a persistent "line spacing" register (default ~30 dots).
     * After every printed line — including raster images — the firmware appends this many
     * blank dots to the paper. LAN printing fixes this with `ESC 3 0` before every chunk.
     * Without zeroing it here, each 400-px slice gains an extra ~3.75 mm of blank paper,
     * producing the visible top/bottom whitespace the user observes on USB/BT receipts.
     *
     * ## Why no commitTransBuffer here?
     * All chunks share one atomic transaction. The cut command sits at the very end of the
     * buffer and is executed in strict sequence — only after every image is printed.
     * Committing per-chunk would let the callback fire when data reaches the driver buffer,
     * not when the printer head has physically advanced through those pixels.
     *
     * The correct pattern is: buffer ALL chunks → one atomic [commitAndCut] at the end.
     */
    suspend fun addToBuffer(printer: CloudPrinter, bitmap: Bitmap) {
        try {
            printer.setLineSpacing(0)  // Mirror LAN's ESC 3 0: eliminate inter-chunk blank paper
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
     * 1. A [dotsFeed] of 96 dots (12 mm) — mirrors LAN's `ESC J 96` exactly, advancing the
     *    paper far enough to clear the print-head-to-cutter gap before the blade fires.
     *    NOTE: We use [dotsFeed] (absolute dots, `ESC J n`) NOT [lineFeed] (line-spacing
     *    multiples, `ESC d n`). After [addToBuffer] sets line spacing to 0, [lineFeed]
     *    would advance 0 dots and the cutter would fire through the last printed line.
     * 2. A full paper cut.
     * 3. An awaitable callback — we suspend until the printer confirms receipt.
     */
    suspend fun commitAndCut(printer: CloudPrinter): PrintResult {
        val completable = CompletableDeferred<PrintResult>()
        try {
            printer.dotsFeed(96)  // Mirror LAN's ESC J 96: 12 mm absolute feed before cut
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
                if (!initBuffer(printer)) {
                    return PrintResult.Failure("Buffer init failed before commit")
                }
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

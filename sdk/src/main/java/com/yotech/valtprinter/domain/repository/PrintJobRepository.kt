package com.yotech.valtprinter.domain.repository

import android.graphics.Bitmap
import com.yotech.valtprinter.domain.model.PrintResult

/**
 * Low-level print engine interface.
 * Prefer submitting jobs via [ValtPrinterSdk.submitPrintJob]; these methods are available
 * for advanced host-app integrations that need direct pipeline control.
 */
interface PrintJobRepository {
    /** True when a printer is physically connected and ready to accept data. */
    fun isPrinterReady(): Boolean
    suspend fun initPrintJob(): PrintResult
    suspend fun printChunk(bitmap: Bitmap, isLastChunk: Boolean): PrintResult
    suspend fun finalCut(): PrintResult
    /** Convenience single-shot print: renders [bitmap] as a complete job with a final cut. */
    suspend fun printReceipt(bitmap: Bitmap): PrintResult
}

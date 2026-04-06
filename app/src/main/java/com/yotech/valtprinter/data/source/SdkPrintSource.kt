package com.yotech.valtprinter.data.source

import android.graphics.Bitmap
import android.util.Log
import com.sunmi.externalprinterlibrary2.ResultCallback
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.sunmi.externalprinterlibrary2.style.AlignStyle
import com.sunmi.externalprinterlibrary2.style.CloudPrinterStatus
import com.yotech.valtprinter.domain.model.PrintResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SdkPrintSource @Inject constructor() {

    /**
     * Prints a bitmap chunk. 
     * If [isLastChunk] is false, the paper is NOT cut, allowing for continuous long receipts.
     */
    suspend fun printBitmapChunk(
        printer: CloudPrinter, 
        bitmap: Bitmap, 
        isLastChunk: Boolean
    ): PrintResult {
        val completable = CompletableDeferred<PrintResult>()
        try {
            printer.clearTransBuffer()
            printer.initStyle()
            printer.setAlignment(AlignStyle.CENTER)

            printer.printImage(bitmap, com.sunmi.externalprinterlibrary2.style.ImageAlgorithm.BINARIZATION)
            
            if (isLastChunk) {
                printer.cutPaper(true)
            }

            delay(100)
            printer.commitTransBuffer(object : ResultCallback {
                override fun onComplete() {
                    completable.complete(PrintResult.Success)
                }

                override fun onFailed(err: CloudPrinterStatus?) {
                    val errorMsg = "Chunk Error: ${err?.name ?: "Unknown"}"
                    completable.complete(PrintResult.Failure(errorMsg))
                }
            })
        } catch (e: Exception) {
            completable.complete(PrintResult.Failure("Chunk Exception: ${e.message}"))
        }

        return completable.await()
    }

    suspend fun printBitmap(printer: CloudPrinter, bitmap: Bitmap): PrintResult {
        return printBitmapChunk(printer, bitmap, isLastChunk = true)
    }
}

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

    suspend fun printBitmap(printer: CloudPrinter, bitmap: Bitmap): PrintResult {
        val completable = CompletableDeferred<PrintResult>()
        try {
            printer.clearTransBuffer()
            printer.initStyle()
            printer.setAlignment(AlignStyle.CENTER)

            // Print the bitmap
            printer.printImage(bitmap, com.sunmi.externalprinterlibrary2.style.ImageAlgorithm.BINARIZATION)
            
            // Feed and cut
            printer.printText("\n\n\n\n")
            printer.cutPaper(true)

            delay(100) // Ensure thermal head is ready
            printer.commitTransBuffer(object : ResultCallback {
                override fun onComplete() {
                    Log.d("SDK_PRINT", "Print buffer committed successfully")
                    completable.complete(PrintResult.Success)
                }

                override fun onFailed(err: CloudPrinterStatus?) {
                    val errorMsg = "SDK Error: ${err?.name ?: "Unknown"}"
                    Log.e("SDK_PRINT", errorMsg)
                    completable.complete(PrintResult.Failure(errorMsg))
                }
            })
        } catch (e: Exception) {
            val errorMsg = "SDK Print Exception: ${e.message}"
            Log.e("SDK_PRINT", errorMsg, e)
            completable.complete(PrintResult.Failure(errorMsg))
        }

        return completable.await()
    }
}

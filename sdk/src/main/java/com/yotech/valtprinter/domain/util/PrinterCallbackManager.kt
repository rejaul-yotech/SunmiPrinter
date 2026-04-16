package com.yotech.valtprinter.domain.util

import android.util.Log
import com.yotech.valtprinter.sdk.PrintJobCallback
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe in-process callback bus.
 * Replaces the AIDL RemoteCallbackList — host apps register a [PrintJobCallback]
 * directly via [ValtPrinterSdk] instead of binding over IPC.
 */
internal class PrinterCallbackManager {

    private val callbacks = CopyOnWriteArrayList<PrintJobCallback>()

    fun register(callback: PrintJobCallback) {
        callbacks.addIfAbsent(callback)
    }

    fun unregister(callback: PrintJobCallback) {
        callbacks.remove(callback)
    }

    fun notifySuccess(externalJobId: String?) {
        if (externalJobId == null) return
        callbacks.forEach {
            try { it.onJobSuccess(externalJobId) }
            catch (e: Exception) { Log.e("CALLBACK_MGR", "onJobSuccess failed", e) }
        }
    }

    fun notifyFailed(externalJobId: String?, reason: String) {
        if (externalJobId == null) return
        callbacks.forEach {
            try { it.onJobFailed(externalJobId, reason) }
            catch (e: Exception) { Log.e("CALLBACK_MGR", "onJobFailed failed", e) }
        }
    }
}

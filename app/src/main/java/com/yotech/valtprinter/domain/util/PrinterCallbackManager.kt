package com.yotech.valtprinter.domain.util

import android.os.RemoteCallbackList
import android.util.Log
import com.yotech.valtprinter.IPrinterCallback
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the Elite AIDL IPC callback system natively.
 * Allows QueueDispatcher to fire exact success/fail logic back to the Link App.
 */
@Singleton
class PrinterCallbackManager @Inject constructor() {
    val list = RemoteCallbackList<IPrinterCallback>()

    fun notifySuccess(externalJobId: String?) {
        if (externalJobId == null) return
        val count = list.beginBroadcast()
        for (index in 0 until count) {
            try {
                list.getBroadcastItem(index).onJobSuccess(externalJobId)
            } catch (e: Exception) {
                Log.e("CALLBACK_MGR", "Failed to broadcast success", e)
            }
        }
        list.finishBroadcast()
    }

    fun notifyFailed(externalJobId: String?, reason: String) {
        if (externalJobId == null) return
        val count = list.beginBroadcast()
        for (index in 0 until count) {
            try {
                list.getBroadcastItem(index).onJobFailed(externalJobId, reason)
            } catch (e: Exception) {
                Log.e("CALLBACK_MGR", "Failed to broadcast failure", e)
            }
        }
        list.finishBroadcast()
    }
}

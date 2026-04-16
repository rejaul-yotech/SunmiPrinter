package com.yotech.valtprinter.sdk

/**
 * Listener for print job lifecycle events.
 * Register via [ValtPrinterSdk.registerCallback]; unregister when done to avoid leaks.
 */
interface PrintJobCallback {
    /** Called when the job identified by [jobId] was printed successfully. */
    fun onJobSuccess(jobId: String)

    /** Called when the job identified by [jobId] failed with [reason]. */
    fun onJobFailed(jobId: String, reason: String)
}

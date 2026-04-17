package com.yotech.valtprinter.domain.repository

import android.view.View
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter

/**
 * Bridges the print pipeline to the underlying SDK printer object and the
 * Android [View] used for headless receipt rendering.
 * Consumed internally by [QueueDispatcher]; never exposed to host apps.
 */
internal interface RenderRepository {
    /** Returns the live SDK [CloudPrinter] instance, or null when disconnected. */
    fun getActiveCloudPrinter(): CloudPrinter?

    /** Returns the [View] currently registered for headless bitmap capture. */
    fun getCaptureView(): View?

    /** Registers [view] as the target surface for headless receipt rendering. */
    fun setCaptureView(view: View)
}

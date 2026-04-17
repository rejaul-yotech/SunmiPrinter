package com.yotech.valtprinter.domain.repository

import android.view.View

/**
 * SDK-internal registry for the Android [View] used by [BitmapRenderer] to perform
 * headless receipt rendering. Consumed by [QueueDispatcher]; never exposed to host apps.
 *
 * Implementations MUST hold the View through a [java.lang.ref.WeakReference] to avoid
 * leaking the host activity. The host calls `setCaptureView` once the view is attached and
 * `clearCaptureView` from `onDestroy` (or sooner).
 */
internal interface RenderRepository {
    /** Returns the registered [View], or `null` if none is set or the reference has been collected. */
    fun getCaptureView(): View?

    /** Registers [view] as the target surface for headless receipt rendering. */
    fun setCaptureView(view: View)

    /** Drops the current capture-view reference. Idempotent — safe to call when nothing is registered. */
    fun clearCaptureView()
}

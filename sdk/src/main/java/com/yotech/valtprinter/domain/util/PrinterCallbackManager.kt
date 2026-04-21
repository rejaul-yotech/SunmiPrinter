package com.yotech.valtprinter.domain.util

import android.util.Log
import com.yotech.valtprinter.sdk.JobEvent
import com.yotech.valtprinter.sdk.PrintJobCallback
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe in-process event fan-out for print-job lifecycle signals.
 *
 * Two parallel surfaces are kept in sync from a single emit site:
 *
 * 1. **[PrintJobCallback]** — simple listener interface. Backwards-compatible
 *    for hosts that only need terminal outcomes (success / failure).
 * 2. **[jobEvents] `SharedFlow`** — richer stream including progress events
 *    ([JobEvent.Printing]) and pause-for-recovery events ([JobEvent.Interrupted]).
 *    Hosts correlate by [JobEvent.externalJobId].
 *
 * ## Flow configuration
 *
 * - `replay = 0` — hot stream; late subscribers do not see past events.
 * - `extraBufferCapacity = 64` — absorbs bursts (e.g. a long receipt
 *   emitting many [JobEvent.Printing] events) without suspending emit.
 * - `onBufferOverflow = DROP_OLDEST` + emit via `tryEmit` — the dispatcher
 *   must never block on a slow host collector. If the collector can't keep
 *   up, the oldest buffered event is dropped. The persisted job row in Room
 *   remains authoritative, so dropped progress events cannot lose data.
 *
 * The bus lives for the SDK process lifetime; callers register/unregister
 * [PrintJobCallback] instances explicitly. The flow has no global collector —
 * hosts manage their own scope.
 */
internal class PrinterCallbackManager {

    // --- Classic callback interface (back-compat) -----------------------------

    private val callbacks = CopyOnWriteArrayList<PrintJobCallback>()

    fun register(callback: PrintJobCallback) {
        callbacks.addIfAbsent(callback)
    }

    fun unregister(callback: PrintJobCallback) {
        callbacks.remove(callback)
    }

    // --- Streaming JobEvent bus ----------------------------------------------

    private val _jobEvents = MutableSharedFlow<JobEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Read-only view for host apps. */
    val jobEvents: SharedFlow<JobEvent> = _jobEvents.asSharedFlow()

    // --- Emit API -------------------------------------------------------------

    fun emitEnqueued(externalJobId: String) {
        tryEmit(JobEvent.Enqueued(externalJobId))
    }

    fun emitPrinting(externalJobId: String?, chunkIndex: Int, totalChunks: Int?) {
        if (externalJobId == null) return
        tryEmit(JobEvent.Printing(externalJobId, chunkIndex, totalChunks))
    }

    fun emitInterrupted(externalJobId: String?, reason: String) {
        if (externalJobId == null) return
        tryEmit(JobEvent.Interrupted(externalJobId, reason))
    }

    /**
     * Terminal success — fires both the classic callback and the flow so
     * hosts using either API see the same event.
     */
    fun notifySuccess(externalJobId: String?) {
        if (externalJobId == null) return
        tryEmit(JobEvent.Completed(externalJobId))
        callbacks.forEach {
            try { it.onJobSuccess(externalJobId) }
            catch (e: Exception) { Log.e("CALLBACK_MGR", "onJobSuccess failed", e) }
        }
    }

    /**
     * Terminal failure — fires both the classic callback and the flow so
     * hosts using either API see the same event.
     */
    fun notifyFailed(externalJobId: String?, reason: String) {
        if (externalJobId == null) return
        tryEmit(JobEvent.Failed(externalJobId, reason))
        callbacks.forEach {
            try { it.onJobFailed(externalJobId, reason) }
            catch (e: Exception) { Log.e("CALLBACK_MGR", "onJobFailed failed", e) }
        }
    }

    private fun tryEmit(event: JobEvent) {
        // DROP_OLDEST means tryEmit never returns false once the buffer is at
        // capacity — the oldest slot is discarded to make room. We log the
        // (unexpected) false case as a belt-and-braces signal during dev.
        if (!_jobEvents.tryEmit(event)) {
            Log.w("CALLBACK_MGR", "tryEmit returned false for $event — flow buffer misconfigured?")
        }
    }
}

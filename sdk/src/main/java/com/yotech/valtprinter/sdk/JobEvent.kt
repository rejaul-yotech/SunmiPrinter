package com.yotech.valtprinter.sdk

/**
 * Lifecycle event emitted for a single print job identified by
 * [externalJobId] (the host-supplied idempotency key passed to
 * [ValtPrinterSdk.submitPrintJob]).
 *
 * Host apps observe [ValtPrinterSdk.jobEvents] and filter by [externalJobId]
 * to correlate UI state with the lifecycle of a specific order, independent
 * of any other jobs that may be in flight. This is the streaming counterpart
 * to the simpler [PrintJobCallback] interface — both fire from the same
 * internal source, so hosts can pick whichever ergonomics suit them.
 *
 * ## State machine
 *
 * ```
 *                        submitPrintJob()
 *                               │
 *                               ▼
 *                          [Enqueued]
 *                               │
 *                               ▼
 *                  ┌────── [Printing]* ──────┐
 *                  │    (one per chunk)      │
 *                  │                         │
 *                  ▼                         ▼
 *             [Completed]              [Interrupted]    transport/paper loss
 *                                            │          — job stays INTERRUPTED
 *                                            │            in the queue and
 *                                            │            resumes on reconnect
 *                                            ▼
 *                                       [Enqueued] … (on resume)
 *                  │
 *                  ▼
 *              [Failed]                       hardware fault that the queue
 *                                             won't auto-retry; job moves to
 *                                             FAILED status
 * ```
 *
 * ## Delivery semantics
 *
 * - **No replay.** Events are hot — a late subscriber does not see prior events.
 *   Use [PrinterRepository][com.yotech.valtprinter.domain.repository.PrinterRepository]'s
 *   persisted job status plus a fresh [Enqueued] on resume for durability.
 * - **Never blocks the print loop.** The dispatcher uses `tryEmit` into a
 *   64-slot buffer with `DROP_OLDEST` — if the host can't keep up, the oldest
 *   buffered event is dropped rather than suspending the dispatcher.
 * - **Fire-and-forget parity with [PrintJobCallback].** Every event on this
 *   stream has a direct analogue on the callback (or is purely informational,
 *   e.g. `Printing`). Hosts that only care about terminal outcomes can keep
 *   using the callback API.
 */
sealed class JobEvent {
    /** The externally-supplied job id this event refers to. */
    abstract val externalJobId: String

    /**
     * The job was persisted to the queue and will print when a printer is
     * ready. Also re-emitted when an [Interrupted] job is resumed.
     */
    data class Enqueued(override val externalJobId: String) : JobEvent()

    /**
     * The dispatcher has printed chunk [chunkIndex] (zero-based) of
     * [totalChunks]. [totalChunks] may be `null` if the total is not yet known
     * (streaming/LAN cases where the total chunk count is only determined
     * after the full bitmap is composed).
     *
     * This event fires on every successful chunk flush — rate can be high for
     * long receipts. Hosts that only want coarse progress should sample with
     * a Flow operator (e.g. `sample(200.ms)` / `distinctUntilChangedBy`).
     */
    data class Printing(
        override val externalJobId: String,
        val chunkIndex: Int,
        val totalChunks: Int?
    ) : JobEvent()

    /** The job printed successfully and was removed from the active queue. */
    data class Completed(override val externalJobId: String) : JobEvent()

    /**
     * The job failed with a **non-recoverable** hardware or protocol fault
     * (paper out is NOT this — that produces [Interrupted]; malformed payload
     * or printer-rejected commands are). The job's row is marked FAILED and
     * will not be retried automatically.
     */
    data class Failed(
        override val externalJobId: String,
        val reason: String
    ) : JobEvent()

    /**
     * The job was **paused** mid-flight by a recoverable condition: transport
     * loss (BT drop, USB unplug, LAN socket break) or paper-out. The job's
     * row is marked INTERRUPTED and the queue auto-pauses. When the condition
     * clears (reconnect / paper reload) the dispatcher resumes and the host
     * will observe a fresh [Enqueued] followed by further [Printing] events.
     *
     * Hosts use this to show a soft warning ("waiting for printer…") rather
     * than a hard failure banner.
     */
    data class Interrupted(
        override val externalJobId: String,
        val reason: String
    ) : JobEvent()
}

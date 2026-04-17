package com.yotech.valtprinter.sdk

/**
 * Outcome of [ValtPrinterSdk.submitPrintJob].
 *
 * The SDK never throws across the public boundary for ordinary submission paths — callers
 * branch on this sealed result instead. Use [Enqueued] for happy-path bookkeeping,
 * [Duplicate] to detect idempotency-key collisions (the existing job is preserved),
 * and [Failure] for unexpected persistence errors.
 */
sealed class SubmitResult {
    /** The payload was persisted and will be picked up by the print queue. */
    data class Enqueued(val externalJobId: String) : SubmitResult()

    /**
     * A job with the same [externalJobId] already exists in the queue.
     * The previously-enqueued job is preserved unchanged (idempotency).
     */
    data class Duplicate(val externalJobId: String) : SubmitResult()

    /** Unexpected error while persisting the job. [reason] is safe to surface in UI. */
    data class Failure(val reason: String) : SubmitResult()
}

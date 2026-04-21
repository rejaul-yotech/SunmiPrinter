package com.yotech.valtprinter.data.repository.internal

/**
 * Single source of truth for classifying a printer operation failure as a
 * transport-layer loss (socket closed, BT link dropped, USB detached, etc.)
 * versus a hardware/protocol fault (paper out, cover open, malformed command).
 *
 * Transport losses trigger self-healing; hardware faults surface to the host
 * as-is so the user can intervene.
 */
internal object TransportErrorClassifier {

    private val TRANSPORT_LOSS_MARKERS = listOf(
        "not connected",
        "disconnect",
        "socket",
        "timeout",
        "offline",
        "commitcut error"
    )

    fun isTransportLoss(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val lower = reason.lowercase()
        if (TRANSPORT_LOSS_MARKERS.any { lower.contains(it) }) return true
        // Special case: Sunmi SDK emits "Commit ... UNKNOWN" on session tear-down.
        if (lower.contains("unknown") && lower.contains("commit")) return true
        return false
    }
}

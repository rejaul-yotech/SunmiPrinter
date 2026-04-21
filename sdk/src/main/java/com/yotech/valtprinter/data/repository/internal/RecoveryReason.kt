package com.yotech.valtprinter.data.repository.internal

/**
 * Classifies why the self-healing loop was triggered. Purely diagnostic —
 * all reasons follow the same recovery algorithm — but lets logs attribute
 * the root cause for field diagnostics.
 */
internal enum class RecoveryReason {
    SDK_DISCONNECT,
    HEARTBEAT_LOSS,
    PRINT_TRANSPORT_LOSS,
    ACTIVE_PRINTER_MISSING
}

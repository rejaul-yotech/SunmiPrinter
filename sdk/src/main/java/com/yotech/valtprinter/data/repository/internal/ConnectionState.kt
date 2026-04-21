package com.yotech.valtprinter.data.repository.internal

import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.yotech.valtprinter.data.source.RawSocketPrintSource
import com.yotech.valtprinter.domain.model.PrinterDevice

/**
 * Shared mutable state for the printer connection lifecycle.
 *
 * **Thread-safety contract:**
 * - Fields mutated from the Sunmi SDK's `ConnectCallback` thread AND read by
 *   print-pipeline coroutines are marked `@Volatile` so neither side observes a
 *   torn reference. Staleness against logical connect attempts is additionally
 *   gated by [activeConnectAttemptId].
 * - Plain fields are touched only from the repository's Main-dispatcher scope.
 * - Writes to multiple fields that must appear atomic (e.g. a connect commit)
 *   happen inside the owning coroutine sequence, never interleaved with another
 *   sequence because the caller pattern is single-writer by design.
 */
internal class ConnectionState {

    // --- Live connection (cross-thread) ---------------------------------------
    @Volatile var activeCloudPrinter: CloudPrinter? = null
    @Volatile var connectedDevice: PrinterDevice? = null
    @Volatile var lanSession: RawSocketPrintSource.Session? = null

    // --- Connection attempt bookkeeping --------------------------------------
    var lastConnectedDevice: PrinterDevice? = null
    var isManualDisconnect: Boolean = false
    var isConnecting: Boolean = false
    var isRecovering: Boolean = false

    /**
     * Monotonic token bumped on every [connect] or [disconnect]. Stale SDK
     * callbacks whose captured id no longer matches are ignored.
     */
    var activeConnectAttemptId: Long = 0L

    // --- Recovery bookkeeping -------------------------------------------------
    var activeRecoveryDeviceId: String? = null
    var lastRecoveryRequestMs: Long = 0L
    var recoverySessionId: Long = 0L

    // --- Heartbeat bookkeeping ------------------------------------------------
    var btConsecutiveMisses: Int = 0
    var lastSuccessfulPrintCommitMs: Long = 0L
    var lastBtProbeMs: Long = 0L
    var lastPrintActivityMs: Long = 0L
    var lastBtConfirmedHitMs: Long = 0L

    // --- USB takeover --------------------------------------------------------
    /**
     * When `promoteToUsb()` displaces a non-USB device, the displaced device
     * is stashed here so `onUsbDetached()` can fall back to it.
     */
    var preferredFallbackDevice: PrinterDevice? = null

    /**
     * True only when the SDK believes the printer is fully ready to accept data:
     * an active [CloudPrinter] handle exists AND the domain device record is
     * still set. A dangling [CloudPrinter] without a device usually means the
     * connection is mid-teardown.
     */
    fun isPrinterReady(): Boolean =
        activeCloudPrinter != null && connectedDevice != null

    /** Resets all state to "disconnected, no history." */
    fun reset() {
        activeCloudPrinter = null
        connectedDevice = null
        lanSession?.closeQuietly()
        lanSession = null
        lastConnectedDevice = null
        isManualDisconnect = false
        isConnecting = false
        isRecovering = false
        activeRecoveryDeviceId = null
        btConsecutiveMisses = 0
        lastSuccessfulPrintCommitMs = 0L
        lastBtProbeMs = 0L
        lastPrintActivityMs = 0L
        lastBtConfirmedHitMs = 0L
        preferredFallbackDevice = null
    }
}

package com.yotech.valtprinter.data.repository.internal

import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.yotech.valtprinter.data.source.RawSocketPrintSource
import com.yotech.valtprinter.domain.model.PrinterDevice
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable snapshot of the *active* connection. Held inside [ConnectionState]
 * via an [AtomicReference] so concurrent readers always see either a complete
 * `(printer, device)` pair or `null` — never a torn half-state.
 */
internal data class ConnectionSnapshot(
    val cloudPrinter: CloudPrinter,
    val device: PrinterDevice
)

/**
 * Shared mutable state for the printer connection lifecycle.
 *
 * **Thread-safety contract:**
 *
 * - The active connection ([cloudPrinter] + [connectedDevice]) is a single
 *   atomically-replaced [ConnectionSnapshot]. Writers MUST go through
 *   [setConnected] / [clearConnection] rather than mutating the legacy
 *   property setters individually — those have been removed.
 * - Read sites that need a coherent pair MUST use [current] once and work with
 *   the local reference. Repeated `state.activeCloudPrinter` / `state.connectedDevice`
 *   accesses are safe individually (they go through [current] under the hood)
 *   but are NOT atomic relative to each other.
 * - [lanSession] and [lastConnectedDevice] are mutated from multiple threads
 *   and are marked `@Volatile`. They are independent of the connection
 *   snapshot lifecycle.
 * - All other fields are touched only from the repository's Main-dispatcher
 *   scope (single-writer by design).
 */
internal class ConnectionState {

    // --- Atomic connection snapshot (multi-threaded) -------------------------
    private val connectionRef = AtomicReference<ConnectionSnapshot?>(null)

    /** Current connection pair, or `null` if disconnected. Single atomic read. */
    val current: ConnectionSnapshot? get() = connectionRef.get()

    /** Convenience accessor — equivalent to `current?.cloudPrinter`. */
    val activeCloudPrinter: CloudPrinter? get() = connectionRef.get()?.cloudPrinter

    /** Convenience accessor — equivalent to `current?.device`. */
    val connectedDevice: PrinterDevice? get() = connectionRef.get()?.device

    /**
     * Atomically install a new active connection. Replaces any prior snapshot.
     */
    fun setConnected(cloudPrinter: CloudPrinter, device: PrinterDevice) {
        connectionRef.set(ConnectionSnapshot(cloudPrinter, device))
    }

    /**
     * Atomically clear the active connection. Returns the prior snapshot for
     * caller-side cleanup (e.g. logging the device that just dropped).
     */
    fun clearConnection(): ConnectionSnapshot? = connectionRef.getAndSet(null)

    /**
     * True only when an active [ConnectionSnapshot] is installed. Single
     * atomic read — never observes a half-set state, unlike a two-field check.
     */
    fun isPrinterReady(): Boolean = connectionRef.get() != null

    // --- Cross-thread, but independent of the snapshot ------------------------
    @Volatile var lanSession: RawSocketPrintSource.Session? = null

    /**
     * The last device we successfully connected to. Read by recovery flows
     * that race against teardown — so it must be volatile.
     */
    @Volatile var lastConnectedDevice: PrinterDevice? = null

    // --- Connection attempt bookkeeping (single-writer Main-dispatcher) ------
    var isManualDisconnect: Boolean = false
    var isConnecting: Boolean = false
    var isRecovering: Boolean = false

    /**
     * Monotonic token bumped on every connect attempt or [disconnect]. Stale
     * SDK callbacks whose captured id no longer matches are ignored.
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

    /** Resets all state to "disconnected, no history." */
    fun reset() {
        connectionRef.set(null)
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

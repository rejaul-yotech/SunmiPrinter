package com.yotech.valtprinter.domain.model

/**
 * Sealed class representing every possible state of the printer connection lifecycle.
 * The ViewModel exposes this as a StateFlow; the UI reacts to each state.
 */
sealed class PrinterState {
    /** Initial state — no scan running, nothing connected. */
    object Idle : PrinterState()

    /** USB-only scan is running at app startup attempting auto-connect. */
    object AutoConnecting : PrinterState()

    /** Full scan (USB + LAN + BT) is in progress. */
    object Scanning : PrinterState()

    /** A device was selected and connection is being established. */
    data class Connecting(val deviceName: String) : PrinterState()

    /** Successfully connected and ready to print. */
    data class Connected(val device: PrinterDevice) : PrinterState()

    /** A recoverable error occurred. User can retry. */
    data class Error(val message: String) : PrinterState()
}

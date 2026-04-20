package com.yotech.valtprinter.domain.repository

import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import kotlinx.coroutines.flow.StateFlow

/** Manages the printer connection lifecycle. */
interface ConnectionRepository {
    val printerState: StateFlow<PrinterState>
    suspend fun connect(device: PrinterDevice)
    suspend fun connectPairedDevice(device: PrinterDevice): Boolean
    suspend fun autoConnectUsb(): Boolean
    fun disconnect()

    /**
     * The transport currently in use, or null if no printer is connected.
     *
     * The QueueDispatcher uses this to apply transport-specific recovery policy —
     * most importantly, that a LAN job which fails mid-stream cannot be resumed
     * (the printer has already advanced through whatever bytes arrived before the
     * drop) and must be reprinted from chunk 0.
     */
    fun activeConnectionType(): ConnectionType?

    /**
     * Promotes USB to the active transport, overriding any current BT/LAN session.
     *
     * Contract:
     * - If the active connection is already USB and that USB device is still present,
     *   this is a no-op and returns `true`.
     * - Otherwise the current session is torn down deterministically ([disconnect]),
     *   then a USB-only scan-and-connect is attempted via [autoConnectUsb]. The
     *   previous device is remembered so that a later USB-detach can fall back to it
     *   (handled by [onUsbDetached]).
     *
     * Designed to be called from a USB attach broadcast receiver — runs on the
     * service scope, not the UI thread.
     */
    suspend fun promoteToUsb(): Boolean

    /**
     * Notifies the repository that the previously-active USB device has been
     * physically detached. If the active transport is USB, the session is released
     * and (if a non-USB fallback device was remembered by [promoteToUsb]) a recovery
     * attempt is requested for it.
     */
    suspend fun onUsbDetached()
}

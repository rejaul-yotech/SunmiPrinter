package com.yotech.valtprinter.data.repository.internal

import android.content.Context
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.yotech.valtprinter.core.util.FeedbackManager
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Internal contract binding the manager classes to the owning
 * `PrinterRepositoryImpl`. Each manager receives a [Coordinator] so it can
 * reach shared resources (state, scope, state flows) and trigger cross-cutting
 * operations (connect, disconnect, recovery) without holding direct references
 * to sibling managers — which would create dependency cycles.
 *
 * The repository implements [Coordinator]; managers call back through it.
 */
internal interface Coordinator {
    val context: Context
    val scope: CoroutineScope
    val state: ConnectionState
    val feedbackManager: FeedbackManager

    val printerStateFlow: MutableStateFlow<PrinterState>
    val discoveredDevicesFlow: MutableStateFlow<List<PrinterDevice>>

    /** Registers a freshly discovered [CloudPrinter] and updates [discoveredDevicesFlow]. */
    fun handlePrinterFound(printer: CloudPrinter)

    /** Broadcasts `stopSearch` across all Sunmi SDK search methods. */
    fun stopAllSearches()

    /** Initiates a fresh connection handshake for [device]. */
    suspend fun connect(device: PrinterDevice)

    /** Full teardown: cancels scope jobs, releases the SDK handle, resets state. */
    fun disconnect()

    /** Entry point for the self-healing loop. Idempotent & cooldown-guarded. */
    fun requestRecovery(device: PrinterDevice, reason: RecoveryReason, details: String)

    /** Starts the background heartbeat for [device]. Prior job is cancelled. */
    fun startHeartbeat(device: PrinterDevice)

    /** Stops the background heartbeat without affecting the connection. */
    fun stopHeartbeat()
}

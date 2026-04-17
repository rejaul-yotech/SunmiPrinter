package com.yotech.valtprinter.domain.repository

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
}

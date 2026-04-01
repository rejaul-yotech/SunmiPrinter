package com.yotech.valtprinter.domain.repository

import android.graphics.Bitmap
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.domain.model.PrintResult
import kotlinx.coroutines.flow.StateFlow

interface PrinterRepository {
    val printerState: StateFlow<PrinterState>
    val discoveredDevices: StateFlow<List<PrinterDevice>>

    fun startScan()
    fun stopScan()
    suspend fun connect(device: PrinterDevice)
    suspend fun autoConnectUsb(): Boolean
    suspend fun printReceipt(bitmap: Bitmap): PrintResult
    fun disconnect()
}
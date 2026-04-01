package com.yotech.valtprinter.domain.repository

import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.yotech.valtprinter.data.DiscoveredPrinter
import kotlinx.coroutines.flow.StateFlow

interface PrinterRepository {
    val status: StateFlow<String>
    val foundPrinters: StateFlow<List<DiscoveredPrinter>>

    fun startScan()
    fun stopScan()
    fun connect(printer: CloudPrinter)
    suspend fun printLabel(title: String, printedBy: String)
    fun release()
}
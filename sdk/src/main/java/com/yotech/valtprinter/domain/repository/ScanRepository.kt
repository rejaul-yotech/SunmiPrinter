package com.yotech.valtprinter.domain.repository

import com.yotech.valtprinter.domain.model.PrinterDevice
import kotlinx.coroutines.flow.StateFlow

/** Handles printer discovery. Host apps observe [discoveredDevices] after calling [startScan]. */
interface ScanRepository {
    val discoveredDevices: StateFlow<List<PrinterDevice>>
    fun startScan()
    fun stopScan()
}

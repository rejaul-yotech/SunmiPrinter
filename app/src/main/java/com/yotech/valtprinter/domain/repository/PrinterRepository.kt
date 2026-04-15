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
    suspend fun connectPairedDevice(device: PrinterDevice): Boolean
    suspend fun autoConnectUsb(): Boolean
    suspend fun printReceipt(bitmap: Bitmap): PrintResult
    suspend fun printChunk(bitmap: Bitmap, isLastChunk: Boolean): PrintResult
    suspend fun finalCut(): PrintResult

    /**
     * Initializes the SDK's internal transaction buffer before a multi-chunk print job.
     * Must be called ONCE before the first [printChunk] call in the QueueDispatcher loop.
     * No-op for LAN (which uses raw socket, not the SDK buffer).
     */
    suspend fun initPrintJob(): PrintResult
    
    fun getActiveCloudPrinter(): com.sunmi.externalprinterlibrary2.printer.CloudPrinter?
    fun getCaptureView(): android.view.View?
    fun setCaptureView(view: android.view.View)
    
    fun disconnect()

    /** Returns true if at least one USB device is currently attached via UsbManager. */
    fun isUsbPrinterPresent(): Boolean

    /**
     * Returns true if the Bluetooth device with the given MAC address is bonded at OS level.
     * A bonded device reconnects without showing a pair dialog.
     */
    fun isBtDeviceBonded(mac: String): Boolean

    /**
     * Android 12+ requires runtime permission to query bonded devices and connect over BT.
     * When false, the UI must request permission BEFORE attempting BT reconnect.
     */
    fun hasBtConnectPermission(): Boolean
}
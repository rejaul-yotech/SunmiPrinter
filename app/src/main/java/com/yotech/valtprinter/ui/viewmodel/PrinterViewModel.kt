package com.yotech.valtprinter.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yotech.valtprinter.domain.model.PrintResult
import com.yotech.valtprinter.domain.model.PrintStatus
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.domain.repository.PrinterRepository
import com.yotech.valtprinter.domain.usecase.AutoConnectUsbUseCase
import com.yotech.valtprinter.domain.usecase.ConnectToPrinterUseCase
import com.yotech.valtprinter.domain.usecase.DisconnectPrinterUseCase
import com.yotech.valtprinter.domain.usecase.PrintReceiptUseCase
import com.yotech.valtprinter.domain.usecase.StartScanUseCase
import com.yotech.valtprinter.domain.usecase.StopScanUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrinterViewModel @Inject constructor(
    private val startScanUseCase: StartScanUseCase,
    private val stopScanUseCase: StopScanUseCase,
    private val connectUseCase: ConnectToPrinterUseCase,
    private val disconnectUseCase: DisconnectPrinterUseCase,
    private val autoConnectUsbUseCase: AutoConnectUsbUseCase,
    private val printReceiptUseCase: PrintReceiptUseCase,
    repository: PrinterRepository
) : ViewModel() {

    val printerState: StateFlow<PrinterState> = repository.printerState
    val discoveredDevices: StateFlow<List<PrinterDevice>> = repository.discoveredDevices

    private val _printStatus = MutableSharedFlow<PrintStatus>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val printStatus: SharedFlow<PrintStatus> = _printStatus.asSharedFlow()

    init {
        _printStatus.tryEmit(PrintStatus.Idle)
        // Attempt to auto Connect USB immediately upon boot up
        onUsbAttached()
    }

    fun onUsbAttached() {
        viewModelScope.launch {
            val found = autoConnectUsbUseCase()
            // If nothing found, fall back to Idle so user can manually scan
            if (!found && printerState.value === PrinterState.AutoConnecting) {
                // Done internally by repo, but we can call off auto connect explicitly if needed
            }
        }
    }

    fun startDiscovery() {
        startScanUseCase()
    }

    fun stopDiscovery() {
        stopScanUseCase()
    }

    fun connectToDevice(device: PrinterDevice) {
        viewModelScope.launch {
            connectUseCase(device)
        }
    }

    fun printReceipt(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                _printStatus.tryEmit(PrintStatus.Sending)
                val result = printReceiptUseCase(bitmap)
                val newStatus = if (result is PrintResult.Success) {
                    PrintStatus.Success
                } else {
                    PrintStatus.Failure((result as PrintResult.Failure).reason)
                }
                _printStatus.tryEmit(newStatus)
            } catch (e: Exception) {
                _printStatus.tryEmit(PrintStatus.Failure("Unexpected Error: ${e.message}"))
            }
        }
    }

    fun resetPrintStatus() {
        _printStatus.tryEmit(PrintStatus.Idle)
    }

    fun disconnect() {
        disconnectUseCase()
    }

    override fun onCleared() {
        super.onCleared()
        disconnectUseCase()
    }
}
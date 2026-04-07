package com.yotech.valtprinter.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yotech.valtprinter.data.local.dao.PrintDao
import com.yotech.valtprinter.data.local.datastore.PrinterDataStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val repository: PrinterRepository,
    private val printDao: PrintDao,
    private val printerDataStore: PrinterDataStore
) : ViewModel() {

    val printerState: StateFlow<PrinterState> = repository.printerState
    val discoveredDevices: StateFlow<List<PrinterDevice>> = repository.discoveredDevices

    private val _printStatus = MutableSharedFlow<PrintStatus>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val printStatus: SharedFlow<PrintStatus> = _printStatus.asSharedFlow()

    val recentPrintJobs: StateFlow<List<com.yotech.valtprinter.data.local.entity.PrintJobEntity>> =
        printDao.getRecentJobsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isHardwareFault: StateFlow<Boolean> = printerState
        .map { it is PrinterState.Error || it is PrinterState.Reconnecting }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isAlarmAcknowledged = MutableStateFlow(false)
    val isAlarmAcknowledged: StateFlow<Boolean> = _isAlarmAcknowledged.asStateFlow()

    fun expandHardwareHub() {
        _isAlarmAcknowledged.value = false
    }


    init {
        _printStatus.tryEmit(PrintStatus.Idle)

        viewModelScope.launch {
            isHardwareFault.collect { fault ->
                if (fault) {
                    _isAlarmAcknowledged.value = false
                }
            }
        }

        // Attempt to auto Connect USB immediately upon boot up
        onUsbAttached()
    }

    fun acknowledgeAlarm() {
        _isAlarmAcknowledged.value = true
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

    fun reconnect() {
        // Restart the automatic scan/connect flow or force check if already in progress
        startDiscovery()
    }

    override fun onCleared() {
        super.onCleared()
        disconnectUseCase()
    }
}
package com.yotech.valtprinter.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yotech.valtprinter.data.local.dao.PairedDeviceDao
import com.yotech.valtprinter.data.local.dao.PrintDao
import com.yotech.valtprinter.data.local.entity.PairedDeviceEntity
import com.yotech.valtprinter.data.local.datastore.PrinterDataStore
import com.yotech.valtprinter.domain.model.ConnectionType
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
    private val pairedDeviceDao: PairedDeviceDao,
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

    val pairedDevices: StateFlow<List<PairedDeviceEntity>> = pairedDeviceDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isHardwareFault: StateFlow<Boolean> = printerState
        .map { it is PrinterState.Error || it is PrinterState.Reconnecting }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isAlarmAcknowledged = MutableStateFlow(false)
    val isAlarmAcknowledged: StateFlow<Boolean> = _isAlarmAcknowledged.asStateFlow()

    // True when a USB printer device is physically plugged in
    private val _usbPresent = MutableStateFlow(false)
    val usbPresent: StateFlow<Boolean> = _usbPresent.asStateFlow()

    // One-shot messages surfaced as a Snackbar in the UI
    private val _snackbarMessage = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

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

        viewModelScope.launch {
            printerState.collect { state ->
                when (state) {
                    is PrinterState.Connected -> {
                        // Determine BT bond status so we can warn the user if it was lost later
                        val isBonded = if (state.device.connectionType == ConnectionType.BLUETOOTH) {
                            val mac = state.device.id.removePrefix("BT-")
                            repository.isBtDeviceBonded(mac)
                        } else {
                            true
                        }
                        pairedDeviceDao.upsert(
                            PairedDeviceEntity(
                                id = state.device.id,
                                name = state.device.name,
                                address = state.device.address,
                                connectionType = state.device.connectionType.name,
                                model = state.device.model,
                                lastSeenAt = System.currentTimeMillis(),
                                isBonded = isBonded
                            )
                        )
                        _snackbarMessage.tryEmit("Connected to ${state.device.name}")
                    }
                    is PrinterState.Error -> {
                        if (state.message == "Connect Failed") {
                            _snackbarMessage.tryEmit("Could not connect — try scanning again")
                        }
                    }
                    else -> {}
                }
            }
        }

        // Attempt to auto-connect USB immediately on boot
        onUsbAttached()
        _usbPresent.value = repository.isUsbPrinterPresent()
    }

    fun acknowledgeAlarm() {
        _isAlarmAcknowledged.value = true
    }

    fun onUsbAttached() {
        _usbPresent.value = repository.isUsbPrinterPresent()
        viewModelScope.launch {
            autoConnectUsbUseCase()
            // Refresh again after detection attempt completes
            _usbPresent.value = repository.isUsbPrinterPresent()
        }
    }

    fun onUsbDetached() {
        _usbPresent.value = false
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

    fun connectToPairedDevice(device: PairedDeviceEntity) {
        viewModelScope.launch {
            // BT bond check: if the device is no longer bonded at OS level, guide the user
            // instead of letting an unexpected OS pair dialog appear mid-app.
            if (device.connectionType == "BLUETOOTH") {
                val mac = device.id.removePrefix("BT-")
                if (!repository.isBtDeviceBonded(mac)) {
                    _snackbarMessage.tryEmit("Pair '${device.name}' in Bluetooth settings first, then try again.")
                    return@launch
                }
            }

            _snackbarMessage.tryEmit("Connecting to ${device.name}…")

            val domainDevice = PrinterDevice(
                id = device.id,
                name = device.name,
                address = device.address,
                port = if (device.connectionType == "LAN") 9100 else 0,
                connectionType = runCatching {
                    ConnectionType.valueOf(device.connectionType)
                }.getOrDefault(ConnectionType.BLUETOOTH),
                model = device.model
            )
            val connected = repository.connectPairedDevice(domainDevice)
            if (!connected) {
                _snackbarMessage.tryEmit("'${device.name}' not found nearby — scanning…")
                startDiscovery()
            }
        }
    }

    fun unpairDevice(id: String) {
        viewModelScope.launch {
            pairedDeviceDao.deleteById(id)
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

    fun rescanForOthers() {
        viewModelScope.launch {
            disconnectUseCase()
            startScanUseCase()
        }
    }

    fun reconnect() {
        startDiscovery()
    }

    override fun onCleared() {
        super.onCleared()
        disconnectUseCase()
    }
}

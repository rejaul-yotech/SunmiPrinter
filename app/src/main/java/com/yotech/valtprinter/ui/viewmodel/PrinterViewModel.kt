package com.yotech.valtprinter.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yotech.valtprinter.data.local.dao.PairedDeviceDao
import com.yotech.valtprinter.data.local.dao.PrintDao
import com.yotech.valtprinter.data.local.datastore.PrinterDataStore
import com.yotech.valtprinter.data.local.entity.PairedDeviceEntity
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
import com.yotech.valtprinter.ui.model.HardwareHubUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
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

    sealed interface PrinterUiEvent {
        data class ShowMessage(val message: String) : PrinterUiEvent
        object OpenBluetoothSettings : PrinterUiEvent
        object RequestBluetoothConnectPermission : PrinterUiEvent
    }

    val printerState: StateFlow<PrinterState> = repository.printerState
    val discoveredDevices: StateFlow<List<PrinterDevice>> = repository.discoveredDevices

    private val _printStatus = MutableSharedFlow<PrintStatus>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val printStatus: SharedFlow<PrintStatus> = _printStatus.asSharedFlow()

    private val _uiEvents = MutableSharedFlow<PrinterUiEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEvents: SharedFlow<PrinterUiEvent> = _uiEvents.asSharedFlow()

    private var pendingBondedReconnectDeviceId: String? = null

    val recentPrintJobs: StateFlow<List<com.yotech.valtprinter.data.local.entity.PrintJobEntity>> =
        printDao.getRecentJobsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pairedDevices: StateFlow<List<PairedDeviceEntity>> = pairedDeviceDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isHardwareFault: StateFlow<Boolean> = printerState
        .map { it is PrinterState.Error || it is PrinterState.Reconnecting }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _hardwareHubUiState = MutableStateFlow<HardwareHubUiState>(HardwareHubUiState.Hidden)
    val hardwareHubUiState: StateFlow<HardwareHubUiState> = _hardwareHubUiState.asStateFlow()
    private var autoCollapseJob: Job? = null
    private var userInteractedWithHub = false

    // True when a USB printer device is physically plugged in
    private val _usbPresent = MutableStateFlow(false)
    val usbPresent: StateFlow<Boolean> = _usbPresent.asStateFlow()

    fun onHubExpand() {
        userInteractedWithHub = true
        setHardwareHubUiState(HardwareHubUiState.Expanded)
    }

    fun onHubCollapse() {
        userInteractedWithHub = true
        setHardwareHubUiState(HardwareHubUiState.Collapsed)
    }

    init {
        _printStatus.tryEmit(PrintStatus.Idle)

        viewModelScope.launch {
            isHardwareFault.collect { fault ->
                if (fault) {
                    // Hub state transitions are handled by the printerState collector below.
                }
            }
        }

        viewModelScope.launch {
            printerState.collect { state ->
                when (state) {
                    is PrinterState.Connected -> {
                        val wasFault = _hardwareHubUiState.value != HardwareHubUiState.Hidden
                        // Determine BT bond status so we can warn the user if it was lost later
                        val isBonded =
                            if (state.device.connectionType == ConnectionType.BLUETOOTH) {
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
                        printerDataStore.setPreferredPrinterId(state.device.id)
                        if (wasFault) {
                            _uiEvents.tryEmit(PrinterUiEvent.ShowMessage("Back online"))
                        }
                        setHardwareHubUiState(HardwareHubUiState.Hidden)
                    }

                    is PrinterState.Error, is PrinterState.Reconnecting -> {
                        if (_hardwareHubUiState.value == HardwareHubUiState.Hidden) {
                            userInteractedWithHub = false
                            setHardwareHubUiState(HardwareHubUiState.Expanded)
                            scheduleAutoCollapse()
                        }
                    }

                    else -> {}
                }
            }
        }

        // Attempt to auto-connect USB immediately on boot
        onUsbAttached()
        _usbPresent.value = repository.isUsbPrinterPresent()

        viewModelScope.launch {
            autoConnectPreferredPrinterIfPossible()
        }
    }

    private suspend fun autoConnectPreferredPrinterIfPossible() {
        val preferredId = printerDataStore.getPreferredPrinterId() ?: return
        val device = pairedDeviceDao.getById(preferredId) ?: return

        if (device.connectionType == "BLUETOOTH") {
            if (!repository.hasBtConnectPermission()) {
                pendingBondedReconnectDeviceId = device.id
                _uiEvents.tryEmit(PrinterUiEvent.RequestBluetoothConnectPermission)
                return
            }
            val mac = device.id.removePrefix("BT-")
            if (!repository.isBtDeviceBonded(mac)) {
                _uiEvents.tryEmit(
                    PrinterUiEvent.ShowMessage("Printer is not paired. Pair it in Bluetooth settings, then try again.")
                )
                _uiEvents.tryEmit(PrinterUiEvent.OpenBluetoothSettings)
                return
            }
        }

        connectToPairedDevice(device)
    }

    private fun scheduleAutoCollapse() {
        autoCollapseJob?.cancel()
        autoCollapseJob = viewModelScope.launch {
            delay(2_500)
            val stillFault = printerState.value is PrinterState.Error || printerState.value is PrinterState.Reconnecting
            if (stillFault && !userInteractedWithHub) {
                setHardwareHubUiState(HardwareHubUiState.Collapsed)
            }
        }
    }

    private fun setHardwareHubUiState(newState: HardwareHubUiState) {
        if (newState == HardwareHubUiState.Hidden) {
            autoCollapseJob?.cancel()
            userInteractedWithHub = false
        }
        _hardwareHubUiState.value = newState
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
                if (!repository.hasBtConnectPermission()) {
                    pendingBondedReconnectDeviceId = device.id
                    _uiEvents.tryEmit(PrinterUiEvent.RequestBluetoothConnectPermission)
                    return@launch
                }
                val mac = device.id.removePrefix("BT-")
                if (!repository.isBtDeviceBonded(mac)) {
                    _uiEvents.tryEmit(
                        PrinterUiEvent.ShowMessage("Printer is not paired. Pair it in Bluetooth settings, then connect.")
                    )
                    _uiEvents.tryEmit(PrinterUiEvent.OpenBluetoothSettings)
                    return@launch
                }
            }

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
                startDiscovery()
            }
        }
    }

    fun onBluetoothConnectPermissionResult(isGranted: Boolean) {
        if (!isGranted) {
            _uiEvents.tryEmit(
                PrinterUiEvent.ShowMessage("Bluetooth permission denied. Cannot reconnect to the paired printer.")
            )
            pendingBondedReconnectDeviceId = null
            return
        }

        val pendingId = pendingBondedReconnectDeviceId ?: return
        pendingBondedReconnectDeviceId = null
        viewModelScope.launch {
            val device = pairedDeviceDao.getById(pendingId) ?: return@launch
            connectToPairedDevice(device)
        }
    }

    fun unpairDevice(id: String) {
        viewModelScope.launch {
            pairedDeviceDao.deleteById(id)
            val preferredId = printerDataStore.getPreferredPrinterId()
            if (preferredId == id) {
                printerDataStore.setPreferredPrinterId(null)
            }
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

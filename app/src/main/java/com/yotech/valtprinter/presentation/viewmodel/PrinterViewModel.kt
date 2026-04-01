package com.yotech.valtprinter.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.yotech.valtprinter.data.DiscoveredPrinter
import com.yotech.valtprinter.domain.repository.PrinterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrinterViewModel @Inject constructor(
    private val repository: PrinterRepository
) : ViewModel() {

    // Status of the printer (Disconnected, Scanning, Connected: [Address])
    val printerStatus: StateFlow<String> = repository.status

    // The list of printers found during the Bluetooth scan
    val discoveredPrinters: StateFlow<List<DiscoveredPrinter>> = repository.foundPrinters

    /**
     * Triggers a Bluetooth scan for SUNMI Cloud Printers.
     */
    fun startDiscovery() {
        repository.startScan()
    }

    /**
     * Connects to a specific printer selected from the list.
     */
    fun connectToDevice(printer: CloudPrinter) {
        repository.connect(printer)
    }

    /**
     * Prints the specific label as requested.
     */
    fun printLabel() {
        viewModelScope.launch {
            repository.printLabel(
                title = "Jingalala Printer",
                printedBy = "Muhammad Rejaul Karim"
            )
        }
    }

    /**
     * Cleans up the connection when the user leaves the screen.
     */
    fun disconnect() {
        repository.release()
    }

    override fun onCleared() {
        super.onCleared()
        repository.release()
    }
}
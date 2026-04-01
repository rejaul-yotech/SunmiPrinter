package com.yotech.valtprinter.data

import android.content.Context
import com.sunmi.externalprinterlibrary2.ConnectCallback
import com.sunmi.externalprinterlibrary2.ResultCallback
import com.sunmi.externalprinterlibrary2.SearchCallback
import com.sunmi.externalprinterlibrary2.SearchMethod
import com.sunmi.externalprinterlibrary2.SunmiPrinterManager
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.sunmi.externalprinterlibrary2.style.CloudPrinterStatus
import com.yotech.valtprinter.domain.repository.PrinterRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class DiscoveryMode { USB, BLUETOOTH, LAN }

// Renamed 'type' to 'discoveryMode' to avoid SDK conflicts
data class DiscoveredPrinter(
    val printer: CloudPrinter,
    val discoveryMode: DiscoveryMode
)

@Singleton
class PrinterRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PrinterRepository {

    private var activePrinter: CloudPrinter? = null
    private val _status = MutableStateFlow("Disconnected")
    override val status = _status.asStateFlow()

    private val _foundPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())

    // ADDED 'override' HERE
    override val foundPrinters = _foundPrinters.asStateFlow()

    private var scanJob: Job? = null

    override fun startScan() {
        scanJob?.cancel()
        _foundPrinters.value = emptyList()
        _status.value = "Scanning..."

        // Scan all interfaces
        search(SearchMethod.USB, DiscoveryMode.USB)
        search(SearchMethod.BT, DiscoveryMode.BLUETOOTH)
        search(SearchMethod.LAN, DiscoveryMode.LAN)

        scanJob = CoroutineScope(Dispatchers.Main).launch {
            delay(15000L)
            if (_status.value == "Scanning...") _status.value = "Scan Finished"
        }
    }

    private fun search(method: Int, mode: DiscoveryMode) {
        SunmiPrinterManager.getInstance()
            .searchCloudPrinter(context, method, object : SearchCallback {
                override fun onFound(printer: CloudPrinter?) {
                    printer?.let { found ->
                        val currentList = _foundPrinters.value.toMutableList()
                        if (currentList.none { it.printer.cloudPrinterInfo.address == found.cloudPrinterInfo.address }) {
                            currentList.add(DiscoveredPrinter(found, mode))
                            _foundPrinters.value = currentList
                        }
                    }
                }
            })
    }

    override fun stopScan() {
        scanJob?.cancel()
        SunmiPrinterManager.getInstance().stopSearch(context, SearchMethod.USB)
        SunmiPrinterManager.getInstance().stopSearch(context, SearchMethod.BT)
        SunmiPrinterManager.getInstance().stopSearch(context, SearchMethod.LAN)
    }

    override fun connect(printer: CloudPrinter) {
        stopScan()
        _status.value = "Connecting..."
        printer.connect(context, object : ConnectCallback {
            override fun onConnect() {
                activePrinter = printer
                _status.value = "Connected: ${printer.cloudPrinterInfo.address}"
            }

            override fun onFailed(p0: String?) {
                _status.value = "Error: $p0"
            }

            override fun onDisConnect() {
                _status.value = "Disconnected"
                activePrinter = null
            }
        })
    }

    override suspend fun printLabel(title: String, printedBy: String) {
        val printer = activePrinter ?: return

        withContext(Dispatchers.IO) {
            try {
                printer.setPrintDensity(100)
                // Specific label content as requested
                printer.printText("$title\n")
                printer.printText("Printed by: $printedBy\n\n")

                printer.commitTransBuffer(object : ResultCallback {
                    override fun onComplete() {
                        // Successfully printed
                    }

                    override fun onFailed(p0: CloudPrinterStatus?) {
                        _status.value = "Print Error: ${p0?.name}"
                    }
                })
            } catch (e: Exception) {
                _status.value = "Exception: ${e.message}"
            }
        }
    }

    override fun release() {
        activePrinter?.release(context)
        activePrinter = null
        _status.value = "Disconnected"
    }
}
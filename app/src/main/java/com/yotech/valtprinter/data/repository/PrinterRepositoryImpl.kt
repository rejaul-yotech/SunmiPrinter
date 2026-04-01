package com.yotech.valtprinter.data.repository

import android.content.Context
import com.sunmi.externalprinterlibrary2.ConnectCallback
import com.sunmi.externalprinterlibrary2.ResultCallback
import com.sunmi.externalprinterlibrary2.SearchCallback
import com.sunmi.externalprinterlibrary2.SearchMethod
import com.sunmi.externalprinterlibrary2.SunmiPrinterManager
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.sunmi.externalprinterlibrary2.style.CloudPrinterStatus
import com.yotech.valtprinter.data.model.DiscoveredPrinter
import com.yotech.valtprinter.data.model.DiscoveryMode
import com.yotech.valtprinter.domain.repository.PrinterRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrinterRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PrinterRepository {

    private var activePrinter: CloudPrinter? = null
    private val printMutex = Mutex() // Gold standard: Ensures one print job at a time

    private val _status = MutableStateFlow("Disconnected")
    override val status = _status.asStateFlow()

    private val _foundPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    override val foundPrinters = _foundPrinters.asStateFlow()

    private var scanJob: Job? = null

    override fun startScan() {
        scanJob?.cancel()
        _foundPrinters.value = emptyList()
        _status.value = "Scanning..."

        // SUNMI SDK sometimes needs explicit calls to start specific managers
        val manager = SunmiPrinterManager.getInstance()

        search(manager, SearchMethod.USB, DiscoveryMode.USB)
        search(manager, SearchMethod.BT, DiscoveryMode.BLUETOOTH)
        search(manager, SearchMethod.LAN, DiscoveryMode.LAN)

        scanJob = CoroutineScope(Dispatchers.Main).launch {
            delay(15000L)
            if (_status.value == "Scanning...") _status.value = "Scan Finished"
        }
    }

    private fun search(manager: SunmiPrinterManager, method: Int, mode: DiscoveryMode) {
        manager.searchCloudPrinter(context, method, object : SearchCallback {
            override fun onFound(printer: CloudPrinter?) {
                printer?.let { found ->
                    val info = found.cloudPrinterInfo
                    val currentList = _foundPrinters.value.toMutableList()

                    // USB fix: USB devices often return 0/null for address.
                    // We use VID/PID + Name as a fallback unique ID.
                    val uniqueId = when (mode) {
                        DiscoveryMode.USB -> "USB-${info.vid}-${info.pid}-${info.name}"
                        DiscoveryMode.BLUETOOTH -> "BT-${info.mac ?: info.address}"
                        DiscoveryMode.LAN -> "LAN-${info.address}"
                    }

                    if (currentList.none { it.id == uniqueId }) {
                        currentList.add(DiscoveredPrinter(found, mode, uniqueId))
                        _foundPrinters.value = currentList
                    }
                }
            }
        })
    }

    override fun connect(printer: CloudPrinter) {
        stopScan()
        _status.value = "Connecting..."

        CoroutineScope(Dispatchers.Main).launch {
            delay(500) // Essential for hardware handshake
            printer.connect(context, object : ConnectCallback {
                override fun onConnect() {
                    activePrinter = printer
                    _status.value = "Connected: ${printer.cloudPrinterInfo.name}"
                }
                override fun onFailed(err: String?) { _status.value = "Connection Failed: $err" }
                override fun onDisConnect() {
                    activePrinter = null
                    _status.value = "Disconnected"
                }
            })
        }
    }

    override suspend fun printLabel(title: String, printedBy: String) {
        val printer = activePrinter ?: run {
            _status.value = "Error: No printer connected"
            return
        }

        // QUEUE MANAGEMENT: The Mutex ensures requests wait in line
        printMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    // FIX: SUNMI NT311 Paper Feeding/Blank issue
                    // 1. Initialize/Reset the buffer
                    printer.setPrintDensity(100)

                    // 2. Formatting (Ensure commands are sent before text)
                    printer.printText("$title\n")
                    printer.printText("Printed by: $printedBy\n")

                    // 3. Essential: Feed paper so it actually clears the cutter head
                    printer.lineFeed(3)

                    // 4. Commit and wait for result
                    val completable = CompletableDeferred<Boolean>()
                    printer.commitTransBuffer(object : ResultCallback {
                        override fun onComplete() { completable.complete(true) }
                        override fun onFailed(err: CloudPrinterStatus?) {
                            _status.value = "Print Error: ${err?.name}"
                            completable.complete(false)
                        }
                    })
                    completable.await()
                } catch (e: Exception) {
                    _status.value = "Exception: ${e.message}"
                }
            }
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        SunmiPrinterManager.getInstance().stopSearch(context, SearchMethod.USB)
        SunmiPrinterManager.getInstance().stopSearch(context, SearchMethod.BT)
        SunmiPrinterManager.getInstance().stopSearch(context, SearchMethod.LAN)
    }

    override fun release() {
        activePrinter?.release(context)
        activePrinter = null
        _status.value = "Disconnected"
    }
}
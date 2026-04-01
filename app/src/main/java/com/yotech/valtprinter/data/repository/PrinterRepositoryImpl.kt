package com.yotech.valtprinter.data.repository

import android.content.Context
import android.util.Log
import com.sunmi.externalprinterlibrary2.ConnectCallback
import com.sunmi.externalprinterlibrary2.ResultCallback
import com.sunmi.externalprinterlibrary2.SearchCallback
import com.sunmi.externalprinterlibrary2.SunmiPrinterManager
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.sunmi.externalprinterlibrary2.style.AlignStyle
import com.sunmi.externalprinterlibrary2.style.CloudPrinterStatus
import com.sunmi.externalprinterlibrary2.style.EncodeType
import com.yotech.valtprinter.data.model.DiscoveredPrinter
import com.yotech.valtprinter.data.model.DiscoveryMode
import com.yotech.valtprinter.domain.repository.PrinterRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrinterRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PrinterRepository {

    private val printMutex = Mutex()
    private var activePrinter: CloudPrinter? = null

    private val _foundPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    override val foundPrinters = _foundPrinters.asStateFlow()

    private val _status = MutableStateFlow("Disconnected")
    override val status = _status.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // SDK Constants: USB=1000, LAN=2000, BT=3000
    private val searchMethods = listOf(1000, 2000, 3000)

    override fun startScan() {
        _status.value = "Scanning..."
        stopAllSearches()
        _foundPrinters.value = emptyList()

        searchMethods.forEach { method ->
            try {
                SunmiPrinterManager.getInstance().searchCloudPrinter(context, method, object : SearchCallback {
                    override fun onFound(printer: CloudPrinter?) {
                        printer?.let { handlePrinterFound(it) }
                    }
                })
            } catch (e: Exception) {
                Log.e("VALT_SCAN", "Method $method failed", e)
            }
        }
    }

    private fun handlePrinterFound(printer: CloudPrinter) {
        val info = printer.cloudPrinterInfo ?: return

        val mode = when {
            info.vid > 0 -> DiscoveryMode.USB
            !info.address.isNullOrEmpty() -> DiscoveryMode.LAN
            !info.mac.isNullOrEmpty() -> DiscoveryMode.BLUETOOTH
            else -> DiscoveryMode.LAN
        }

        val uniqueId = when (mode) {
            DiscoveryMode.USB -> "USB-${info.vid}-${info.pid}"
            DiscoveryMode.BLUETOOTH -> "BT-${info.mac}"
            DiscoveryMode.LAN -> "LAN-${info.address}"
        }

        val currentList = _foundPrinters.value.toMutableList()
        if (currentList.none { it.id == uniqueId }) {
            currentList.add(DiscoveredPrinter(printer, mode, uniqueId))
            _foundPrinters.value = currentList
            Log.d("PRINTER_DEBUG", "Found $mode Device: $uniqueId | IP: ${info.address}")
        }
    }

    override fun connect(printer: CloudPrinter) {
        stopAllSearches()
        _status.value = "Connecting..."

        repositoryScope.launch {
            delay(500)
            printer.connect(context, object : ConnectCallback {
                override fun onConnect() {
                    activePrinter = printer
                    // CRITICAL: Explicitly set encoding to UTF_8 immediately after connection
                    try {
                        printer.setEncodeMode(EncodeType.UTF_8)
                    } catch (e: Exception) {
                        Log.e("PRINTER_DEBUG", "Encoding set failed", e)
                    }
                    _status.value = "Connected: ${printer.cloudPrinterInfo.name}"
                    Log.d("PRINTER_DEBUG", "Connection successful")
                }

                override fun onFailed(err: String?) {
                    _status.value = "Connect Failed: $err"
                }

                override fun onDisConnect() {
                    activePrinter = null
                    _status.value = "Disconnected"
                }
            })
        }
    }

    override suspend fun printLabel(title: String, printedBy: String) {
        val printer = activePrinter ?: return
        val info = printer.cloudPrinterInfo
        val ip = info.address

        printMutex.withLock {
            withContext(Dispatchers.IO) {
                // Use Socket Bypass for LAN to ensure text clarity
                if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                    sendRawToSocket(ip, info.port.takeIf { it > 0 } ?: 9100, title, printedBy)
                } else {
                    // Optimized SDK print for USB
                    performSdkPrint(printer, title, printedBy)
                }
            }
        }
    }

    private suspend fun sendRawToSocket(ip: String, port: Int, title: String, user: String) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 4000)
                val out = socket.getOutputStream()

                // ESC/POS Commands
                out.write(byteArrayOf(0x1B, 0x40))      // Init
                out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
                // Print Text with explicit newlines
                out.write("\n$title\n".toByteArray(Charsets.UTF_8))
                out.write("By: $user\n".toByteArray(Charsets.UTF_8))
                out.write("\n\n\n\n\n".toByteArray()) // Feed
                out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // Cut

                out.flush()
                _status.value = "Print Success"
            }
        } catch (e: Exception) {
            _status.value = "Socket Error: ${e.message}"
            Log.e("VALT_PRINT", "Socket failed", e)
        }
    }

    private suspend fun performSdkPrint(printer: CloudPrinter, title: String, user: String) {
        val completable = CompletableDeferred<Boolean>()
        try {
            // 1. Clear everything
            printer.clearTransBuffer()

            // 2. Setup Style
            printer.initStyle()
            printer.setAlignment(AlignStyle.CENTER)

            // 3. Queue Data - Adding explicit line breaks to ensure buffer fills
            printer.printText("\n")
            printer.printText(title)
            printer.printText("\nBy: $user\n")
            printer.printText("\n\n\n\n")

            // 4. Queue Cut
            printer.cutPaper(true)

            // 5. Commit with a small delay to ensure the thermal head is ready
            delay(100)
            printer.commitTransBuffer(object : ResultCallback {
                override fun onComplete() {
                    Log.d("PRINTER_DEBUG", "Print buffer committed")
                    completable.complete(true)
                }
                override fun onFailed(err: CloudPrinterStatus?) {
                    _status.value = "SDK Error: ${err?.name}"
                    completable.complete(false)
                }
            })
        } catch (e: Exception) {
            Log.e("PRINTER_DEBUG", "SDK Print Exception", e)
            completable.complete(false)
        }
        completable.await()
    }

    private fun stopAllSearches() {
        searchMethods.forEach { method ->
            try {
                SunmiPrinterManager.getInstance().stopSearch(context, method)
            } catch (e: Exception) {
                Log.e("VALT_STOP", "Error stopping $method", e)
            }
        }
    }

    override fun stopScan() = stopAllSearches()

    override fun release() {
        stopScan()
        activePrinter?.release(context)
        activePrinter = null
    }
}
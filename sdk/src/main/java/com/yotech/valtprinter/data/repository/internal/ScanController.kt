package com.yotech.valtprinter.data.repository.internal

import android.content.Context
import android.util.Log
import com.sunmi.externalprinterlibrary2.SunmiPrinterManager
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.yotech.valtprinter.core.util.SdkLogger
import com.yotech.valtprinter.data.mapper.toDomain
import com.yotech.valtprinter.data.model.DiscoveredPrinter
import com.yotech.valtprinter.data.model.DiscoveryMode
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Owns device discovery across USB / LAN / Bluetooth, and the fresh-instance
 * cache used by connect handshakes.
 *
 * Scans are fan-out per transport: a single `startScan()` call kicks off three
 * parallel `searchCloudPrinter` jobs (USB=1000, LAN=2000, BT=3000). Results
 * feed a shared map keyed by a deterministic `uniqueId`, which is then mapped
 * to domain objects and pushed through [discoveredDevicesFlow].
 */
internal class ScanController(
    private val context: Context,
    private val discoveredDevicesFlow: MutableStateFlow<List<PrinterDevice>>
) {
    // SDK Constants: USB=1000, LAN=2000, BT=3000
    private val searchMethods = listOf(USB_METHOD, LAN_METHOD, BT_METHOD)

    /** Internal SDK objects indexed by unique domain id. */
    val internalPrintersMap: MutableMap<String, DiscoveredPrinter> = mutableMapOf()

    fun startScan(onBeforeSearch: () -> Unit) {
        onBeforeSearch()
        stopAllSearches()
        internalPrintersMap.clear()
        discoveredDevicesFlow.value = emptyList()

        searchMethods.forEach { method ->
            try {
                SunmiPrinterManager.getInstance()
                    .searchCloudPrinter(context, method) { printer ->
                        printer?.let { handlePrinterFound(it) }
                    }
            } catch (e: Exception) {
                Log.e("VALT_SCAN", "Method $method failed", e)
            }
        }
    }

    fun stopAllSearches() {
        searchMethods.forEach { method ->
            try {
                SunmiPrinterManager.getInstance().stopSearch(context, method)
            } catch (e: Exception) {
                Log.e("VALT_STOP", "Error stopping $method", e)
            }
        }
    }

    fun clearDiscovered() {
        internalPrintersMap.clear()
        discoveredDevicesFlow.value = emptyList()
    }

    /**
     * Registers a freshly-discovered printer. Always refreshes with the latest
     * SDK instance — reusing stale [CloudPrinter] objects after reconnect can
     * cause transaction failures (e.g., CommitCut UNKNOWN).
     */
    fun handlePrinterFound(printer: CloudPrinter) {
        val info = printer.cloudPrinterInfo ?: return

        val mode = when {
            info.vid > 0 -> DiscoveryMode.USB
            !info.address.isNullOrEmpty() -> DiscoveryMode.LAN
            !info.mac.isNullOrEmpty() -> DiscoveryMode.BLUETOOTH
            else -> DiscoveryMode.LAN
        }

        val uniqueId = uniqueIdFor(mode, info.vid, info.pid, info.mac, info.address)
        internalPrintersMap[uniqueId] = DiscoveredPrinter(printer, mode, uniqueId)

        val domainList = internalPrintersMap.values.map { it.toDomain() }
        discoveredDevicesFlow.value = domainList

        SdkLogger.d(
            "PRINTER_DEBUG",
            "Found/Updated $mode Device: ${SdkLogger.redactDeviceId(uniqueId)} | IP: ${SdkLogger.redactIp(info.address)}"
        )
    }

    /**
     * Targeted search for a previously-paired device. Returns `true` if the
     * device was rediscovered and registered in [internalPrintersMap]. Caller
     * is responsible for invoking `connect()` afterwards.
     */
    suspend fun rediscoverPairedDevice(device: PrinterDevice): Boolean {
        if (internalPrintersMap.containsKey(device.id)) return true

        val method = when (device.connectionType) {
            ConnectionType.USB -> USB_METHOD
            ConnectionType.LAN -> LAN_METHOD
            ConnectionType.BLUETOOTH -> BT_METHOD
        }

        var found = false
        try {
            SunmiPrinterManager.getInstance()
                .searchCloudPrinter(context, method) { printer ->
                    if (printer == null || found) return@searchCloudPrinter
                    val info = printer.cloudPrinterInfo ?: return@searchCloudPrinter
                    val uniqueId = uniqueIdFor(method, info.vid, info.pid, info.mac, info.address)
                    if (uniqueId == device.id) {
                        found = true
                        handlePrinterFound(printer)
                    }
                }
            delay(1500)
            SunmiPrinterManager.getInstance().stopSearch(context, method)
        } catch (e: Exception) {
            Log.e("PRINTER_DEBUG", "rediscoverPairedDevice search failed", e)
        }
        return found && internalPrintersMap.containsKey(device.id)
    }

    private fun uniqueIdFor(
        mode: DiscoveryMode,
        vid: Int,
        pid: Int,
        mac: String?,
        address: String?
    ): String = when (mode) {
        DiscoveryMode.USB -> "USB-$vid-$pid"
        DiscoveryMode.BLUETOOTH -> "BT-$mac"
        DiscoveryMode.LAN -> "LAN-$address"
    }

    private fun uniqueIdFor(
        method: Int,
        vid: Int,
        pid: Int,
        mac: String?,
        address: String?
    ): String = when (method) {
        USB_METHOD -> "USB-$vid-$pid"
        BT_METHOD -> "BT-$mac"
        else -> "LAN-$address"
    }

    companion object {
        const val USB_METHOD = 1000
        const val LAN_METHOD = 2000
        const val BT_METHOD = 3000
    }
}

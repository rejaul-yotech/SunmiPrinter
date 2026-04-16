package com.yotech.valtprinter.data.mapper

import com.yotech.valtprinter.data.model.DiscoveredPrinter
import com.yotech.valtprinter.data.model.DiscoveryMode
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrinterDevice

/**
 * Maps the internal data-layer [DiscoveredPrinter] (which holds the SDK CloudPrinter reference)
 * to the domain-layer [PrinterDevice] (which is pure Kotlin with no SDK imports).
 * This mapper is the ONLY place where the SDK's CloudPrinterInfo is accessed.
 */
fun DiscoveredPrinter.toDomain(): PrinterDevice {
    val info = printer.cloudPrinterInfo
    return PrinterDevice(
        id = id,
        name = info?.name?.takeIf { it.isNotBlank() } ?: "SUNMI Printer",
        address = info?.address?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
            ?: info?.mac?.takeIf { it.isNotBlank() }
            ?: "",
        port = info?.port?.takeIf { it > 0 } ?: 9100,
        connectionType = when (discoveryMode) {
            DiscoveryMode.USB -> ConnectionType.USB
            DiscoveryMode.LAN -> ConnectionType.LAN
            DiscoveryMode.BLUETOOTH -> ConnectionType.BLUETOOTH
        },
        model = null
    )
}

package com.yotech.valtprinter.data.model

import com.sunmi.externalprinterlibrary2.printer.CloudPrinter

data class DiscoveredPrinter(
    val printer: CloudPrinter,
    val discoveryMode: DiscoveryMode,
    val id: String
)
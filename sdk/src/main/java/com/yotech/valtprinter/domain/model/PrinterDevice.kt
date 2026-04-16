package com.yotech.valtprinter.domain.model

/**
 * Pure domain model representing a discovered printer device.
 * Contains NO references to the Sunmi SDK — keeps domain fully decoupled.
 */
data class PrinterDevice(
    val id: String,
    val name: String,
    val address: String,     // IP for LAN, MAC for BT, empty for USB
    val port: Int,           // 9100 for LAN, 0 for USB/BT
    val connectionType: ConnectionType,
    val model: String? = null
)

enum class ConnectionType { USB, LAN, BLUETOOTH }

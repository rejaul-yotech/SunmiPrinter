package com.yotech.valtprinter.domain.model

data class ConnectionUiState(
    val status: String = "Disconnected",
    val deviceName: String = "No Device",
    val connectionType: String = "None",
    val isReady: Boolean = false
)
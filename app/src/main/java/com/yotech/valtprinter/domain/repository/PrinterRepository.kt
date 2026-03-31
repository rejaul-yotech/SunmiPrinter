package com.yotech.valtprinter.domain.repository

interface PrinterRepository {
    fun connect(address: String, onStatus: (String) -> Unit)
    fun printLabel(content: String, onComplete: (Boolean) -> Unit)
    fun release()
}
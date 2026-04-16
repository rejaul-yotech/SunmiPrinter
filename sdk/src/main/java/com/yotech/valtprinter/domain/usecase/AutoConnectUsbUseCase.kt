package com.yotech.valtprinter.domain.usecase

import com.yotech.valtprinter.domain.repository.PrinterRepository

class AutoConnectUsbUseCase constructor(private val repository: PrinterRepository) {
    suspend operator fun invoke(): Boolean = repository.autoConnectUsb()
}

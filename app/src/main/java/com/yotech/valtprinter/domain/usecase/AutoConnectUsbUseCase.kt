package com.yotech.valtprinter.domain.usecase

import com.yotech.valtprinter.domain.repository.PrinterRepository
import javax.inject.Inject

class AutoConnectUsbUseCase @Inject constructor(private val repository: PrinterRepository) {
    suspend operator fun invoke(): Boolean = repository.autoConnectUsb()
}

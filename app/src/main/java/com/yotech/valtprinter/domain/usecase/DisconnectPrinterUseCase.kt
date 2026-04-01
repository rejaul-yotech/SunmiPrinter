package com.yotech.valtprinter.domain.usecase

import com.yotech.valtprinter.domain.repository.PrinterRepository
import javax.inject.Inject

class DisconnectPrinterUseCase @Inject constructor(private val repository: PrinterRepository) {
    operator fun invoke() = repository.disconnect()
}

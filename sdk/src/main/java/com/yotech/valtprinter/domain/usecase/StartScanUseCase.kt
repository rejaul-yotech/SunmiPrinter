package com.yotech.valtprinter.domain.usecase

import com.yotech.valtprinter.domain.repository.PrinterRepository

class StartScanUseCase constructor(private val repository: PrinterRepository) {
    operator fun invoke() = repository.startScan()
}

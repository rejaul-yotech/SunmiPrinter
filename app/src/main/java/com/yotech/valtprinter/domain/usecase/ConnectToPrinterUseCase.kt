package com.yotech.valtprinter.domain.usecase

import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.repository.PrinterRepository
import javax.inject.Inject

class ConnectToPrinterUseCase @Inject constructor(private val repository: PrinterRepository) {
    suspend operator fun invoke(device: PrinterDevice) = repository.connect(device)
}

package com.yotech.valtprinter.domain.usecase

import android.graphics.Bitmap
import com.yotech.valtprinter.domain.model.PrintResult
import com.yotech.valtprinter.domain.repository.PrinterRepository
import javax.inject.Inject

class PrintReceiptUseCase @Inject constructor(private val repository: PrinterRepository) {
    suspend operator fun invoke(bitmap: Bitmap): PrintResult = repository.printReceipt(bitmap)
}

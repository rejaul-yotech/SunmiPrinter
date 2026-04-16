package com.yotech.valtprinter.domain.model

/** Result emitted after a print job completes. */
sealed class PrintResult {
    object Success : PrintResult()
    data class Failure(val reason: String) : PrintResult()
}

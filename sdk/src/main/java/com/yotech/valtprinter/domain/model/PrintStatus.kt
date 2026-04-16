package com.yotech.valtprinter.domain.model

/** 
 * Comprehensive printing status for UI state management.
 * Ensures the "Print" button is managed by a single source of truth.
 */
sealed class PrintStatus {
    object Idle : PrintStatus()
    object Processing : PrintStatus()
    object Sending : PrintStatus()
    object Success : PrintStatus()
    data class Failure(val reason: String) : PrintStatus()
}

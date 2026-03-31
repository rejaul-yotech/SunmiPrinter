package com.yotech.valtprinter.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yotech.valtprinter.domain.repository.PrinterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PrintViewModel(private val repository: PrinterRepository) : ViewModel() {
    private val _printStatus = MutableStateFlow("Idle")
    val printStatus = _printStatus.asStateFlow()

    fun startPrint(text: String) {
        viewModelScope.launch {
            _printStatus.value = "Printing..."
            repository.printLabel(text) { success ->
                _printStatus.value = if (success) "Print Successful" else "Print Failed"
            }
        }
    }
}
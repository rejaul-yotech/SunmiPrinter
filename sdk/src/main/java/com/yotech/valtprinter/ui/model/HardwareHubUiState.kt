package com.yotech.valtprinter.ui.model

import androidx.compose.runtime.Immutable

@Immutable
sealed interface HardwareHubUiState {
    data object Hidden : HardwareHubUiState
    data object Collapsed : HardwareHubUiState
    data object Expanded : HardwareHubUiState
}


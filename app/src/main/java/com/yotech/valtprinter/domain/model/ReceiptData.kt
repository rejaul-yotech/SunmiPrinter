package com.yotech.valtprinter.domain.model

import androidx.compose.ui.graphics.vector.ImageVector

data class ReceiptData(
    val title: String,
    val restaurantName: String,
    val staffName: String,
    val items: List<String> = emptyList(), // Room for expansion
    val logoResId: ImageVector
)
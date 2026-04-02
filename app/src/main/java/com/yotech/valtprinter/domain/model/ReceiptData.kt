package com.yotech.valtprinter.domain.model

data class ReceiptData(
    val title: String,
    val staffName: String,
    val items: List<String> = emptyList() // Room for expansion
)
package com.yotech.valtprinter.domain.model.orderdata

data class OrderItem(
    val id: String, // "item_101"
    val name: String, // "Chicken Ruby"
    val category: String, // "Mains"
    val unitPrice: Double, // 14.50
    val quantity: Int, // 2
    val unitLabel: String, // "portion"
    val subItems: List<SubOrderItem> = emptyList()
)
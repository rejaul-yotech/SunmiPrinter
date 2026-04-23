package com.yotech.valtprinter.domain.model.orderdata

/**
 * Compose stability: declared stable in `sdk/compose_stability.conf` and pinned
 * by `ComposeStabilityContractTest`. All properties MUST stay `val` and stably
 * typed; see the conf file's KDoc for the full rule set.
 */
data class OrderItem(
    val id: String, // "item_101"
    val name: String, // "Chicken Ruby"
    val category: String, // "Mains"
    val unitPrice: Double, // 14.50
    val quantity: Int, // 2
    val unitLabel: String, // "portion"
    val subItems: List<SubOrderItem> = emptyList()
)
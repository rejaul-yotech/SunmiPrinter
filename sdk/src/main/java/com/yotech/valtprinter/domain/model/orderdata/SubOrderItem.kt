package com.yotech.valtprinter.domain.model.orderdata

/**
 * Compose stability: declared stable in `sdk/compose_stability.conf` and pinned
 * by `ComposeStabilityContractTest`. All properties MUST stay `val` and stably
 * typed; see the conf file's KDoc for the full rule set.
 */
data class SubOrderItem(
    val id: String, // "mod_1"
    val name: String, // "Extra Spicy"
    val unitPrice: Double, // 0.0
    val quantity: Int, // 1
    val unitLabel: String // ""
)
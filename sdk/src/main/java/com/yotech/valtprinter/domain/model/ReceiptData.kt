package com.yotech.valtprinter.domain.model

/**
 * Kitchen-ticket payload. Pure data — no Compose-runtime types — because this
 * model is persisted as JSON to Room before rendering. See [orderdata.BillingData]
 * for the full rationale on why a logo field is intentionally absent.
 *
 * Compose stability: declared stable in `sdk/compose_stability.conf` and pinned
 * by `ComposeStabilityContractTest`. All properties MUST stay `val` and stably
 * typed; see the conf file's KDoc for the full rule set.
 */
data class ReceiptData(
    val title: String,
    val restaurantName: String,
    val staffName: String,
    val items: List<String> = emptyList()
)
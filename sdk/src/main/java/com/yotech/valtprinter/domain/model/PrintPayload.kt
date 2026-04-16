package com.yotech.valtprinter.domain.model

import com.yotech.valtprinter.domain.model.orderdata.BillingData

/**
 * A sealed class representing the polymorphic payload sent by external Link Apps.
 */
sealed class PrintPayload {
    data class Billing(val data: BillingData) : PrintPayload()
    data class RawText(val text: String) : PrintPayload()
    data class Unknown(val rawJson: String) : PrintPayload()
}

package com.yotech.valtprinter.domain.model.orderdata

/**
 * The primary data contract for the Valt KD billing and printing system.
 * Example: Dishoom Kensington, 4 Derry St, LONDON W8 5SE, UK.
 *
 * ## Why no logo field
 *
 * This model crosses a JSON serialization boundary (the print job is persisted to
 * Room as a JSON string before the dispatcher renders it). Compose-runtime types
 * such as `androidx.compose.ui.graphics.vector.ImageVector` cannot round-trip
 * through Gson — `ImageVector` has no public constructor, so `fromJson` either
 * throws or produces a half-built instance that NPEs at render time. A previous
 * `logoResId: ImageVector` field is removed for that reason.
 *
 * If a host needs a brand logo on the receipt, register it as a render-time
 * resource via the host's composable (e.g. an `Image(painterResource(R.drawable.logo))`
 * inside a custom screen) rather than encoding it into the persisted payload.
 */
data class BillingData(
    // --- Restaurant Identity ---
    val restaurantName: String, // "Dishoom Kensington"
    val restaurantPhone: String, // "+44 20 7420 9325"

    // --- Address Information (UK Standard) ---
    val addressLine1: String, // "4 Derry Street"
    val addressLine2: String? = null, // null
    val locality: String? = null, // "Kensington"
    val city: String, // "LONDON"
    val region: String? = null, // "Greater London"
    val postalCode: String, // "W8 5SE"
    val countryCode: String, // "GB"

    // --- Transaction Metadata ---
    val staffName: String, // "Md. Rejaul Karim"
    val deviceName: String, // "Sunmi V2 Pro"
    val orderDeviceName: String, // "Tablet-KDS-01"
    val timestamp: Long, // 1743602874000
    val orderId: String, // "DSH-9921"
    val orderTag: String, // "Table 14"
    val orderReference: String, // "CHK-55201"
    val orderType: String, // "Dine In"

    // --- Financials ---
    val currencyCode: String, // "GBP"
    val paymentStatus: String, // "Paid"
    val footerNote: String? = null, // "Optional 12.5% service charge added."

    val subtotal: Double = 0.0, // 44.00
    val serviceCharge: Double = 0.0, // 5.50
    val vatPercentage: Double = 0.0, // 20.0
    val isVatInclusive: Boolean = false, // true (standard for UK menus)
    val additionalCharge: Double = 0.0, // 0.0
    val bagFee: Double = 0.0, // 0.0
    val grandTotal: Double = 0.0, // 49.50

    val qrCodeContent: String? = null, // "https://feedback.dishoom.com"
    val items: List<OrderItem>
)
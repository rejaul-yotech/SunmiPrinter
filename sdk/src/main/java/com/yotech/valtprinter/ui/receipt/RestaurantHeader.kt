package com.yotech.valtprinter.ui.receipt

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotech.valtprinter.domain.model.orderdata.BillingData
import com.yotech.valtprinter.domain.model.orderdata.OrderItem
import com.yotech.valtprinter.domain.model.orderdata.SubOrderItem

@Composable
fun RestaurantHeader(
    modifier: Modifier = Modifier,
    data: BillingData
) {

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = data.restaurantName,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Spacer(Modifier.height(4.dp))

        // 2. Primary Address Line
        Text(
            text = data.addressLine1,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        // Optional Address Line 2 (Flat/Suite)
        data.addressLine2?.takeIf { it.isNotBlank() }?.let { line2 ->
            Text(
                text = line2,
                fontSize = 22.sp,
                color = Color.DarkGray
            )
        }

        // Locality & City (Commonly combined in UK receipts: "Kensington, LONDON")
        val cityLine = listOfNotNull(data.locality, data.city)
            .filter { it.isNotBlank() }
            .joinToString(", ")

        Text(
            text = cityLine,
            fontSize = 22.sp,
            color = Color.Black
        )

        // Postcode & Region
        val regionLine = listOfNotNull(data.region, data.postalCode)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        Text(
            text = regionLine,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = Color.Black
        )
    }
}

@Preview(showBackground = true)
@Composable
fun RestaurantHeaderPreview() {
    RestaurantHeader(
        data = BillingData(
            // Restaurant Identity (Dishoom Kensington)
            restaurantName = "Dishoom Kensington",
            restaurantPhone = "+44 20 7420 9325",

            // Address Information (Official UK Format)
            addressLine1 = "4 Derry Street",
            addressLine2 = null,               // No flat/suite needed for this building
            locality = "Kensington",           // The London Borough/Area
            city = "LONDON",                   // Post Town (Standardized to Uppercase)
            region = "Greater London",
            postalCode = "W8 5SE",
            countryCode = "GB",

            // Transaction Metadata
            staffName = "Md. Rejaul Karim",
            deviceName = "Sunmi V2 Pro",        // Common Android POS hardware
            orderDeviceName = "Tablet-KDS-01",
            timestamp = 1743602874000L,        // April 2, 2026
            orderId = "DSH-9921",
            orderTag = "Table 14",
            orderReference = "CHK-55201",
            orderType = "Dine In",

            // Financials
            currencyCode = "GBP",              // British Pound Sterling
            paymentStatus = "Paid",
            footerNote = "Optional 12.5% service charge added. Thank you!",

            subtotal = 44.0,
            serviceCharge = 5.50,              // 12.5% of 44.0
            vatPercentage = 20.0,              // Standard UK VAT rate
            isVatInclusive = true,             // Most UK restaurant menus are VAT inclusive
            additionalCharge = 0.0,
            bagFee = 0.0,
            grandTotal = 49.50,

            qrCodeContent = "https://www.dishoom.com/kensington/feedback",
            items = listOf(
                OrderItem(
                    id = "item_101",
                    name = "Chicken Ruby",
                    category = "Mains",
                    unitPrice = 14.50,
                    quantity = 2,
                    unitLabel = "portion",
                    subItems = listOf(
                        SubOrderItem(
                            id = "mod_1",
                            name = "Extra Spicy",
                            unitPrice = 0.0,
                            quantity = 1,
                            unitLabel = ""
                        )
                    )
                ),
                OrderItem(
                    id = "item_202",
                    name = "Garlic Naan",
                    category = "Sides",
                    unitPrice = 4.50,
                    quantity = 3,
                    unitLabel = "pcs"
                )
            )
        )
    )
}

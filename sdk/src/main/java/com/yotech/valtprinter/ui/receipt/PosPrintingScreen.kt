package com.yotech.valtprinter.ui.receipt

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotech.valtprinter.core.util.DateTimeFormat
import com.yotech.valtprinter.core.util.DateTimeUtils
import com.yotech.valtprinter.domain.model.orderdata.BillingData
import com.yotech.valtprinter.domain.model.orderdata.OrderItem
import com.yotech.valtprinter.domain.model.orderdata.SubOrderItem
import java.util.Locale

/**
 * Receipt composable rendered by the SDK's headless [BitmapRenderer]. Must NOT
 * apply `Modifier.verticalScroll` here — the renderer measures with
 * `MeasureSpec.UNSPECIFIED` height, and verticalScroll inside infinite height
 * constraints throws `IllegalStateException`. Hosts that want to preview the
 * receipt on screen should wrap this composable in their own scrolling
 * container at the call site.
 */
@Composable
fun PosPrintingScreen(
    data: BillingData
) {
    // Grouping items by category for the "Premium Receipt" look!
    val groupedItems = remember(data.items) {
        data.items.groupBy { it.category }
    }

    // Format current time using our new DateTimeUtils
    val printTime = remember {
        DateTimeUtils.getCurrentDateTime(
            format = DateTimeFormat.DATE_TIME_DISPLAY,
            locale = Locale.getDefault()
        )
    }

    Column(
        modifier = Modifier
            .width(576.dp)
            .background(Color.White)
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Header Section
        RestaurantHeader(data = data)

        HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = 2.dp, color = Color.Black)

        // 2. Transaction Metadata
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Order: ${data.orderId}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(data.orderType, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Table: ${data.orderTag}", fontSize = 20.sp, color = Color.Black)
            Text("Staff: ${data.staffName}", fontSize = 20.sp, color = Color.Gray)
        }

        Text(
            text = "Printed at: $printTime",
            fontSize = 18.sp,
            color = Color.Black,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Start
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = 1.dp, color = Color.Black)

        // 3. Categorized Order Items
        groupedItems.forEach { (category, items) ->
            // Category Header
            Text(
                text = "--- $category ---",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.Black,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            items.forEach { item ->
                BillItemRow(item)

                // Render Sub-items (Modifiers) if any
                item.subItems.forEach { sub ->
                    Text(
                        text = " + ${sub.quantity}x ${sub.name}",
                        fontSize = 22.sp,
                        color = Color.DarkGray,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, bottom = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        HorizontalDivider(
            Modifier.padding(top = 12.dp, bottom = 8.dp),
            thickness = 2.dp,
            color = Color.Black
        )

        // 4. Financial Summary
        FinancialSummaryRow(
            "Subtotal",
            "${data.currencyCode} ${String.format("%.2f", data.subtotal)}"
        )
        FinancialSummaryRow(
            "Service Charge",
            "${data.currencyCode} ${String.format("%.2f", data.serviceCharge)}"
        )
        FinancialSummaryRow(
            "Grand Total",
            "${data.currencyCode} ${String.format("%.2f", data.grandTotal)}",
            isBold = true
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = "*--- THANK YOU ---*",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        data.footerNote?.let { note ->
            Text(
                text = note,
                fontSize = 22.sp,
                color = Color.DarkGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@SuppressLint("DefaultLocale")
@Composable
fun BillItemRow(item: OrderItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${item.quantity}x ${item.name}",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format("%.2f", item.unitPrice * item.quantity),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun FinancialSummaryRow(label: String, value: String, isBold: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 22.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            value,
            fontSize = 22.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
        )
    }
}


@Preview(showBackground = true)
@Composable
fun PosPrintingScreenPreview() {
    PosPrintingScreen(
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
                ),
                OrderItem(
                    id = "item_303",
                    name = "Masala Chai",
                    category = "Drinks",
                    unitPrice = 3.50,
                    quantity = 2,
                    unitLabel = "cups"
                )
            )
        )
    )
}

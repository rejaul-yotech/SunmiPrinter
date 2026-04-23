package com.yotech.valtprinter.ui.receipt

import com.yotech.valtprinter.core.util.PRINTER_PAPER_WIDTH_DP
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotech.valtprinter.domain.model.ReceiptData

/**
 * Kitchen-ticket receipt composable, rendered headless by [BitmapRenderer].
 *
 * **Width contract:** locked to [PRINTER_PAPER_WIDTH_DP] — the same single
 * source of truth that [BitmapRenderer] uses for the off-screen capture and
 * that [PosPrintingScreen] / [RestaurantHeader] / [RawTextScreen] inherit.
 * Do NOT swap to a different literal — drift between this width and the
 * capture width silently corrupts the print output.
 */
@Composable
fun KitchenReceipt(data: ReceiptData) {
    Column(
        modifier = Modifier
            .width(PRINTER_PAPER_WIDTH_DP)
            .background(Color.White),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "VALT KITCHEN",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Spacer(Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.Print,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color.Black
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = 2.dp, color = Color.Black)
        Spacer(Modifier.height(8.dp))

        Text(
            text = data.title,
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.Black
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Staff: ${data.staffName}",
            fontSize = 18.sp,
            color = Color.Black
        )
        Spacer(Modifier.height(12.dp))

        data.items.forEach { item ->
            Text(
                text = item,
                fontSize = 24.sp,
                color = Color.Black,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "*--- END OF TICKET ---*",
            fontSize = 20.sp,
            color = Color.Black
        )
    }
}

@Preview(showBackground = true)
@Composable
fun KitchenReceiptPreview() {
    KitchenReceipt(
        data = ReceiptData(
            title = "ORDER #102",
            staffName = "Md. Rejaul Karim",
            restaurantName = "Dishoom Kensington",
            items = listOf("1x Burger", "2x Fries", "1x Coke")
        )
    )
}

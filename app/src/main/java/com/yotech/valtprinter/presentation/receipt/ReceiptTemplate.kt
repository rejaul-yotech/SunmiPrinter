package com.yotech.valtprinter.presentation.receipt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ReceiptData(
    val title: String,
    val staffName: String,
    val items: List<String> = emptyList() // Room for expansion
)

@Composable
fun ReceiptTemplate(data: ReceiptData) {
    // This Column is the actual "Paper" area. Width must be exactly 384 pixels for 80mm printer.
    // In Compose, dp is usually translated to pixels based on density.
    // However, our BitmapRenderer forces the view to be exactly 384 pixels wide.
    // So the width modifier here should just match what looks good in preview.
    Column(
        modifier = Modifier
            .width(384.dp)
            .background(Color.White)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "VALT KITCHEN",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = 2.dp, color = Color.Black)
        Spacer(Modifier.height(24.dp))

        Text(
            text = data.title,
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.Black
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Staff: ${data.staffName}",
            fontSize = 18.sp,
            color = Color.Black
        )
        Spacer(Modifier.height(40.dp))

        data.items.forEach { item ->
            Text(
                text = item,
                fontSize = 16.sp,
                color = Color.Black,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(32.dp))
        Text(
            text = "*--- END OF TICKET ---*",
            fontSize = 14.sp,
            color = Color.Black
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ReceiptTemplatePreview() {
    ReceiptTemplate(
        data = ReceiptData(
            title = "ORDER #102",
            staffName = "Md. Rejaul Karim",
            items = listOf("1x Burger", "2x Fries", "1x Coke")
        )
    )
}

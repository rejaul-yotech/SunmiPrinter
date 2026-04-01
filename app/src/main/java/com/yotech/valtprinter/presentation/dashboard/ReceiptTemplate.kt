package com.yotech.valtprinter.presentation.dashboard

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

@Composable
fun ReceiptTemplate(title: String, user: String) {
    // This Column is the actual "Paper" area
    Column(
        modifier = Modifier
            .width(384.dp) // Standard 80mm Sunmi width
            .background(Color.White)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "VALT KITCHEN SYSTEM",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = 2.dp, color = Color.Black)

        Spacer(Modifier.height(24.dp))

        Text(
            text = title,
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.Black
        )
        Text(
            text = "Staff: $user",
            fontSize = 16.sp,
            color = Color.Black
        )

        Spacer(Modifier.height(40.dp))

        Text(
            text = "*--- KITCHEN TICKET ---*",
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ReceiptTemplatePreview() {
    ReceiptTemplate(
        title = "ORDER #102",
        user = "Md. Rejaul Karim"
    )
}
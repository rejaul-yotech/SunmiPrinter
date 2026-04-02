package com.yotech.valtprinter.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotech.valtprinter.domain.model.orderdata.OrderItem

@Composable
fun BillItemRow(item: OrderItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${item.quantity}x ${item.name}",
            fontSize = 22.sp, // Increased for visibility
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format("%.2f", item.unitPrice * item.quantity),
            fontSize = 22.sp, // Increased for visibility
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
            fontSize = if (isBold) 28.sp else 22.sp, // Much larger for Grand Total
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            value,
            fontSize = if (isBold) 28.sp else 22.sp, // Much larger for Grand Total
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

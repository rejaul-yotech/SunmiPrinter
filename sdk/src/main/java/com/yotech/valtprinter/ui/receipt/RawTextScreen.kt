package com.yotech.valtprinter.ui.receipt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Raw-text fallback receipt. Inherits its width from the parent ComposeView
 * that [com.yotech.valtprinter.core.util.BitmapRenderer] installs at
 * [com.yotech.valtprinter.core.util.PRINTER_PAPER_WIDTH_DP]. Do NOT
 * reintroduce a hardcoded `.width(...)` here — fillMaxWidth() is the
 * intentional contract.
 */
@Composable
fun RawTextScreen(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        // We use a monospace font for raw raw-receipt text to mimic physical printer alignment
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 24.sp,
            fontWeight = FontWeight.Normal,
            color = Color.Black
        )
    }
}

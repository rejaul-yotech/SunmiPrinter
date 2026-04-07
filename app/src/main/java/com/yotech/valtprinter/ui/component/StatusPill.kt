package com.yotech.valtprinter.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotech.valtprinter.domain.model.PrinterState

@Composable
fun StatusPill(
    state: PrinterState,
    modifier: Modifier = Modifier
) {
    val (color, text) = when (state) {
        is PrinterState.Connected -> Color.Green to "Connected"
        is PrinterState.Reconnecting -> Color(0xFFFFA500) to "Reconnecting..."
        is PrinterState.Connecting -> Color.Cyan to "Connecting..."
        is PrinterState.Scanning -> Color.Cyan to "Scanning..."
        is PrinterState.Error -> Color.Red to "Offline"
        else -> Color.Gray to "Idle"
    }

    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = 500),
        label = "PillColor"
    )

    // Pulsing effect for non-stable states
    val infiniteTransition = rememberInfiniteTransition(label = "PillPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state is PrinterState.Connected || state is PrinterState.Idle) 1f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AlphaPulse"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(animatedColor.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(animatedColor.copy(alpha = alpha))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                fontSize = 11.sp
            ),
            color = animatedColor
        )
    }
}

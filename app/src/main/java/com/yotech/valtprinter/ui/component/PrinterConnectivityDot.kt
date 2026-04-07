package com.yotech.valtprinter.ui.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotech.valtprinter.domain.model.PrinterState
import kotlinx.coroutines.delay

@Composable
fun PrinterConnectivityDot(state: PrinterState) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    var retryingText by remember { mutableStateOf("Retrying") }
    
    // Cycle "Retrying..." text
    LaunchedEffect(state) {
        if (state is PrinterState.Reconnecting) {
            while (true) {
                retryingText = "Retrying"
                delay(500)
                retryingText = "Retrying."
                delay(500)
                retryingText = "Retrying.."
                delay(500)
                retryingText = "Retrying..."
                delay(500)
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(8.dp)
    ) {
        val (dotColor, dotAlpha, statusText) = when (state) {
            is PrinterState.Connected -> Triple(Color(0xFF4CAF50), 1f, "Connected")
            is PrinterState.Reconnecting -> Triple(Color(0xFFF44336), alpha, retryingText)
            is PrinterState.Connecting -> Triple(Color(0xFFFFEB3B), 1f, "Connecting")
            is PrinterState.Scanning -> Triple(Color(0xFFFFEB3B), alpha, "Scanning")
            is PrinterState.Error -> Triple(Color(0xFFF44336), 1f, "Offline")
            else -> Triple(Color.Gray, 1f, "Offline")
        }

        // The Dot
        Box(
            modifier = Modifier
                .size(12.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(dotColor)
        )

        // The Text
        Text(
            text = statusText,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

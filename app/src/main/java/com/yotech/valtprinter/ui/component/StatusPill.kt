package com.yotech.valtprinter.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
    val text = when (state) {
        is PrinterState.Connected -> "Connected"
        is PrinterState.Reconnecting -> "Recovering: ${state.secondsRemaining}s"
        is PrinterState.Connecting -> "Connecting..."
        is PrinterState.Scanning -> "Scanning..."
        is PrinterState.Error -> "Offline"
        else -> "Idle"
    }

    val targetColor = when (state) {
        is PrinterState.Connected -> Color.Green
        is PrinterState.Reconnecting -> Color(0xFFFFA500)
        is PrinterState.Connecting -> Color.Cyan
        is PrinterState.Scanning -> Color.Cyan
        is PrinterState.Error -> Color.Red
        else -> Color.Gray
    }

    val animatedColor by animateColorAsState(
        targetValue = targetColor,
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

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(animatedColor.copy(alpha = 0.15f))
                // Elite Neon Glow Effect
                .drawBehind {
                    if (state !is PrinterState.Idle) {
                        drawCircle(
                            color = animatedColor.copy(alpha = alpha * 0.2f),
                            radius = size.maxDimension * 0.8f
                        )
                    }
                }
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
        
        // Internal Micro-Progress for Reconnecting state
        if (state is PrinterState.Reconnecting) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { state.secondsRemaining / 60f },
                modifier = Modifier
                    .width(80.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(100.dp)),
                color = animatedColor,
                trackColor = animatedColor.copy(alpha = 0.05f)
            )
        }
    }
}

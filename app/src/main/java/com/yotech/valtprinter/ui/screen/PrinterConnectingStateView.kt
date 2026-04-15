package com.yotech.valtprinter.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yotech.valtprinter.ui.theme.CyanElectric
import com.yotech.valtprinter.ui.theme.NavySurface

@Composable
fun ConnectingStateView(deviceName: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "Handshake")

    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "P1"
    )
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "A1"
    )

    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            tween(2000, delayMillis = 1000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "P2"
    )
    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween(2000, delayMillis = 1000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "A2"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(80.dp)
                    .graphicsLayer(scaleX = pulse1, scaleY = pulse1, alpha = alpha1)
                    .background(CyanElectric, CircleShape)
            )
            Box(
                Modifier
                    .size(80.dp)
                    .graphicsLayer(scaleX = pulse2, scaleY = pulse2, alpha = alpha2)
                    .background(CyanElectric, CircleShape)
            )
            Box(
                Modifier
                    .size(80.dp)
                    .background(NavySurface, CircleShape)
                    .border(2.dp, CyanElectric, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Print,
                    null,
                    tint = CyanElectric,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(Modifier.height(48.dp))

        Text(
            "Securing Handshake...",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            "Establishing physical link with $deviceName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}


package com.yotech.valtprinter.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yotech.valtprinter.data.local.entity.PrintJobEntity
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.ui.component.StatusPill
import com.yotech.valtprinter.ui.theme.CyanElectric
import com.yotech.valtprinter.ui.theme.NavySurface

@Composable
fun HardwareDashboard(
    state: PrinterState,
    recentJobs: List<PrintJobEntity>,
    showHandshakeSuccess: Boolean,
    onPreviewClick: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onScanOthers: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "AuraTransition")
    val auraAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AuraAlpha"
    )

    val borderColor by animateColorAsState(
        targetValue = when (state) {
            is PrinterState.Error -> Color.Red
            is PrinterState.Reconnecting -> Color(0xFFFFA500)
            else -> Color.Transparent
        },
        label = "BorderColor"
    )

    val shakeOffset by infiniteTransition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ShakeOffset"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = NavySurface),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (state !is PrinterState.Connected) {
                        drawRoundRect(
                            color = borderColor.copy(alpha = auraAlpha * 0.3f),
                            cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                            size = size.copy(
                                width = size.width + 12.dp.toPx(),
                                height = size.height + 12.dp.toPx()
                            ),
                            topLeft = Offset(-6.dp.toPx(), -6.dp.toPx())
                        )
                    }
                },
            border = if (state !is PrinterState.Connected)
                BorderStroke(2.dp, borderColor.copy(alpha = auraAlpha)) else null
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val iconColor by animateColorAsState(
                        if (state is PrinterState.Error || state is PrinterState.Reconnecting) Color.Red else CyanElectric,
                        label = "IconTint"
                    )

                    if (state is PrinterState.Reconnecting) {
                        CircularProgressIndicator(
                            progress = { state.secondsRemaining / 60f },
                            modifier = Modifier.size(95.dp),
                            color = Color.Red.copy(alpha = 0.4f),
                            strokeWidth = 3.dp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .offset(x = if (state !is PrinterState.Connected) shakeOffset.dp else 0.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (showHandshakeSuccess) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Success",
                                modifier = Modifier.size(40.dp),
                                tint = Color.Green
                            )
                        } else {
                            Icon(
                                imageVector = when (state) {
                                    is PrinterState.Error -> Icons.Default.LinkOff
                                    else -> when (state) {
                                        is PrinterState.Connected -> when (state.device.connectionType) {
                                            ConnectionType.USB -> Icons.Default.Usb
                                            ConnectionType.BLUETOOTH -> Icons.Default.Bluetooth
                                            ConnectionType.LAN -> Icons.Default.Wifi
                                        }
                                        else -> Icons.Default.Print
                                    }
                                },
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = iconColor
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                StatusPill(state = state)

                Spacer(Modifier.height(16.dp))

                val name = when (state) {
                    is PrinterState.Connected -> state.device.name
                    is PrinterState.Reconnecting -> state.deviceName
                    else -> "Connection Lost"
                }

                Text(
                    text = name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                AnimatedContent(targetState = state, label = "MicroInfo") { s ->
                    when (s) {
                        is PrinterState.Reconnecting -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(s.microState, color = CyanElectric, fontWeight = FontWeight.Bold)
                                Text("Recovery active...", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        is PrinterState.Error -> {
                            Text(
                                "Hardware Fault Detected",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        is PrinterState.Connected -> {
                            Text(
                                s.device.address,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        else -> {}
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        HardwareDashboardBody(
            state = state,
            recentJobs = recentJobs
        )

        HardwareDashboardActions(
            state = state,
            onPreviewClick = onPreviewClick,
            onDisconnect = onDisconnect,
            onRetry = onRetry,
            onScanOthers = onScanOthers
        )
    }
}


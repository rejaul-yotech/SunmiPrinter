package com.yotech.valtprinter.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yotech.valtprinter.core.util.AlarmHelper
import com.yotech.valtprinter.data.local.entity.PrintJobEntity
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.ui.component.StatusPill
import com.yotech.valtprinter.ui.theme.CyanElectric
import com.yotech.valtprinter.ui.theme.NavySurface
import com.yotech.valtprinter.ui.theme.VioletElectric
import com.yotech.valtprinter.ui.viewmodel.PrinterViewModel
import kotlinx.coroutines.delay

@Composable
fun PrinterScreen(
    viewModel: PrinterViewModel,
    onNavigateToPreview: () -> Unit
) {
    val state by viewModel.printerState.collectAsStateWithLifecycle()
    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle()

    val isHardwareFault by viewModel.isHardwareFault.collectAsStateWithLifecycle()
    val isAlarmAcknowledged by viewModel.isAlarmAcknowledged.collectAsStateWithLifecycle()
    val recentJobs by viewModel.recentPrintJobs.collectAsStateWithLifecycle()

    val context = LocalContext.current

    LaunchedEffect(isHardwareFault, isAlarmAcknowledged) {
        if (isHardwareFault && !isAlarmAcknowledged) {
            AlarmHelper.startAlarmAndVibration(context)
        } else {
            AlarmHelper.stopAlarmAndVibration(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Valt Printer",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                val subtitle = when (state) {
                    is PrinterState.Scanning -> "Searching for nearby devices..."
                    is PrinterState.Connected -> "Hardware Online"
                    is PrinterState.Error -> "Connection Interrupted"
                    else -> "Cloud Print Server"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            var showHandshakeSuccess by remember { mutableStateOf(false) }

            // Elite: Handshake Success Animation Trigger
            LaunchedEffect(state) {
                if (state is PrinterState.Connected) {
                    showHandshakeSuccess = true
                    delay(1200)
                    showHandshakeSuccess = false
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            AnimatedContent(targetState = state, label = "PrinterState") { currentState ->
                when (currentState) {
                    is PrinterState.Idle -> IdleStateView(
                        onScan = viewModel::startDiscovery
                    )

                    is PrinterState.AutoConnecting -> AutoConnectingView()
                    is PrinterState.Scanning -> ScanningStateView(
                        devices = devices,
                        onDeviceSelected = viewModel::connectToDevice,
                        onStopScan = viewModel::stopDiscovery
                    )

                    is PrinterState.Connecting -> ConnectingStateView(
                        deviceName = currentState.deviceName
                    )

                    else -> {
                        // Contextual Hardware Dashboard (Morphs between Connected, Reconnecting, and Error)
                        HardwareDashboard(
                            state = currentState,
                            recentJobs = recentJobs,
                            showHandshakeSuccess = showHandshakeSuccess,
                            onPreviewClick = onNavigateToPreview,
                            onDisconnect = viewModel::disconnect,
                            onRetry = viewModel::reconnect,
                            onScanOthers = viewModel::rescanForOthers
                        )
                    }
                }
            } // End AnimatedContent
        } // End Column

        // Top-level Box layer: ELITE RESILIENCE HUB
        AnimatedVisibility(
            visible = isHardwareFault,
            enter = fadeIn(tween(1000)),
            exit = fadeOut(tween(800))
        ) {
            ResilienceHubOverlay(
                state = state,
                isMinimized = isAlarmAcknowledged,
                onDismiss = { viewModel.acknowledgeAlarm() },
                onExpand = { viewModel.expandHardwareHub() },
                onRescan = { viewModel.rescanForOthers() }
            )
        }
    } // End Box
} // End fun

@Composable
fun ResilienceHubOverlay(
    state: PrinterState,
    isMinimized: Boolean,
    onDismiss: () -> Unit,
    onExpand: () -> Unit,
    onRescan: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isMinimized) Color.Transparent else Color.Black.copy(alpha = 0.6f))
            .clickable(enabled = true) { 
                if (isMinimized) onExpand() else { /* Do nothing, click inside card handles buttons */ }
            },
        contentAlignment = if (isMinimized) Alignment.BottomCenter else Alignment.Center
    ) {
        val containerModifier = if (isMinimized) {
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
        } else {
            Modifier
                .padding(24.dp)
                .fillMaxWidth(0.9f)
        }

        Card(
            modifier = containerModifier,
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!isMinimized) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFFFFA500) // Calm warning orange
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "HARDWARE HUB",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        "Physical link interrupted. Entering self-healing mode.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                }

                // Resilience Checklist
                Surface(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = CyanElectric
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            val statusMsg = if (state is PrinterState.Reconnecting) state.microState else "Hardware offline"
                            Text(statusMsg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("System is securing existing jobs...", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (!isMinimized) {
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanElectric),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("MINIMIZE TO HUB", color = NavySurface, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRescan) {
                         Text("RESCAN FOR OTHER PRINTERS", color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

@Composable
fun IdleStateView(onScan: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))
        Text("No printer connected", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onScan,
            colors = ButtonDefaults.buttonColors(containerColor = CyanElectric)
        ) {
            Text("SCAN FOR DEVICES", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun AutoConnectingView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = VioletElectric)
        Spacer(Modifier.height(16.dp))
        Text("Checking for USB devices...", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ScanningStateView(
    devices: List<PrinterDevice>,
    onDeviceSelected: (PrinterDevice) -> Unit,
    onStopScan: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
        ) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = CyanElectric,
                trackColor = Color.Transparent
            )
        }
        RowHeader(title = "Discovered Devices", onAction = onStopScan, actionText = "Stop Scan")

        if (devices.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyanElectric)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(devices, key = { it.id }) { device ->
                    DeviceListItem(device, onClick = { onDeviceSelected(device) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                }
            }
        }
    }
}

@Composable
fun ConnectingStateView(deviceName: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "Handshake")
    
    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "P1"
    )
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "A1"
    )

    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(2000, delayMillis = 1000, easing = LinearEasing), RepeatMode.Restart), label = "P2"
    )
    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2000, delayMillis = 1000, easing = LinearEasing), RepeatMode.Restart), label = "A2"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Ripple 1
            Box(Modifier.size(80.dp).graphicsLayer(scaleX = pulse1, scaleY = pulse1, alpha = alpha1).clip(CircleShape).background(CyanElectric))
            // Ripple 2
            Box(Modifier.size(80.dp).graphicsLayer(scaleX = pulse2, scaleY = pulse2, alpha = alpha2).clip(CircleShape).background(CyanElectric))
            
            // Core
            Box(
                Modifier.size(80.dp).clip(CircleShape).background(NavySurface).border(2.dp, CyanElectric, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Print, null, tint = CyanElectric, modifier = Modifier.size(32.dp))
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
    // Breathing Aura Animation
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

    // Icon Shaking Animation
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
                // Elite Neon Glow Aura
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
                // Persistent Icon Area with Morphing Elements
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
                        // Handshake Success Overlay
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
                                Text(
                                    s.microState,
                                    color = CyanElectric,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Recovery active...",
                                    style = MaterialTheme.typography.labelSmall
                                )
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

        // Queue Assurance Message (Reassurance during downtime)
        if (state is PrinterState.Reconnecting || state is PrinterState.Error) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CyanElectric.copy(alpha = 0.05f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Safe State: ${recentJobs.size} order(s) secured. Continue taking orders normally.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = CyanElectric,
                    modifier = Modifier.padding(12.dp),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // Action Area
        Column(modifier = Modifier.weight(1f)) {
            if (recentJobs.isNotEmpty() && state is PrinterState.Connected) {
                Text(
                    "Recent Activity",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(recentJobs, key = { it.id }) { job ->
                        ListItem(
                            headlineContent = { Text("Job ${job.externalJobId}") },
                            supportingContent = { Text("Status: ${job.status}") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                        )
                    }
                }
            } else if (state is PrinterState.Error) {
                // Diagnostic Card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(
                            alpha = 0.1f
                        )
                    )
                ) {
                    Text(
                        state.diagnosticMessage,
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Sticky Adaptive Buttons
        Column(modifier = Modifier.fillMaxWidth()) {
            if (state is PrinterState.Connected) {
                Button(
                    onClick = onPreviewClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanElectric)
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, tint = NavySurface)
                    Spacer(Modifier.width(8.dp))
                    Text("PREVIEW TICKET", color = NavySurface, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        // Elite: Manual disconnect resets everything
                        onDisconnect()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        Icons.Default.LinkOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("DISCONNECT PRINTER", color = MaterialTheme.colorScheme.error)
                }
            } else if (state is PrinterState.Reconnecting || state is PrinterState.Error) {
                // Elite Adaptive Button for Downtime
                Button(
                    onClick = {
                        // Stop recovery loop and go to manual scan
                        onDisconnect()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.9f))
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("STOP RECOVERY", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onScanOthers, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state is PrinterState.Error) "SCAN FOR OTHERS" else "CHANGE PRINTER")
                }
            } else {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanElectric)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = NavySurface)
                    Spacer(Modifier.width(8.dp))
                    Text("TRY RECONNECTING", color = NavySurface, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onScanOthers, modifier = Modifier.fillMaxWidth()) {
                    Text("SCAN FOR OTHERS")
                }
            }
        }
    }
}

@Composable
fun RowHeader(title: String, onAction: () -> Unit, actionText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = CyanElectric)
        TextButton(onClick = onAction) {
            Text(actionText)
        }
    }
}

@Composable
fun DeviceListItem(device: PrinterDevice, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        leadingContent = {
            val icon = when (device.connectionType) {
                ConnectionType.USB -> Icons.Default.Usb
                ConnectionType.BLUETOOTH -> Icons.Default.Bluetooth
                ConnectionType.LAN -> Icons.Default.Wifi
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(NavySurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = CyanElectric)
            }
        },
        headlineContent = { Text(device.name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            val subText =
                if (device.connectionType == ConnectionType.USB) "USB Connection" else device.address
            Text(
                subText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        },
        modifier = Modifier.clickable { onClick() }
    )
}

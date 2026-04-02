package com.yotech.valtprinter.presentation.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yotech.valtprinter.domain.model.ConnectionType
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.presentation.viewmodel.PrinterViewModel
import com.yotech.valtprinter.ui.theme.CyanElectric
import com.yotech.valtprinter.ui.theme.NavySurface
import com.yotech.valtprinter.ui.theme.VioletElectric

@Composable
fun PrinterScreen(
    viewModel: PrinterViewModel,
    onNavigateToPreview: () -> Unit
) {
    val state by viewModel.printerState.collectAsStateWithLifecycle()
    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Sunmi Cloud Printer",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

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

                is PrinterState.Connected -> ConnectedStateView(
                    device = currentState.device,
                    onPreviewClick = onNavigateToPreview,
                    onDisconnect = viewModel::disconnect
                )

                is PrinterState.Error -> ErrorStateView(
                    message = currentState.message,
                    onRetry = viewModel::startDiscovery
                )
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
        RowHeader(title = "Scanning...", onAction = onStopScan, actionText = "Stop")

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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = CyanElectric)
        Spacer(Modifier.height(16.dp))
        Text("Connecting to $deviceName...", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ConnectedStateView(
    device: PrinterDevice,
    onPreviewClick: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            colors = CardDefaults.cardColors(containerColor = NavySurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val connectionIcon = when (device.connectionType) {
                    ConnectionType.USB -> Icons.Default.Usb
                    ConnectionType.BLUETOOTH -> Icons.Default.Bluetooth
                    ConnectionType.LAN -> Icons.Default.Wifi
                }

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = connectionIcon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    // Simple "Online" pulse/indicator at the bottom right of the icon
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color.Green)
                    )
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "PRINTER CONNECTED",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanElectric,
                    letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified
                )

                Text(
                    text = device.name,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )

                if (device.address.isNotEmpty()) {
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Spacer(Modifier.height(40.dp))

        Button(
            onClick = onPreviewClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CyanElectric)
        ) {
            Icon(Icons.Default.Print, contentDescription = null, tint = NavySurface)
            Spacer(Modifier.width(8.dp))
            Text(
                "PREVIEW TICKET",
                style = MaterialTheme.typography.titleMedium,
                color = NavySurface
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            )
        ) {
            Icon(Icons.Default.LinkOff, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Disconnect Device")
        }
    }
}

@Composable
fun ErrorStateView(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Retry Scan")
        }
    }
}

@Composable
fun RowHeader(title: String, onAction: () -> Unit, actionText: String) {
    androidx.compose.foundation.layout.Row(
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
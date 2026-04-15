package com.yotech.valtprinter.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yotech.valtprinter.data.local.entity.PairedDeviceEntity
import com.yotech.valtprinter.domain.model.PrinterDevice
import com.yotech.valtprinter.ui.theme.CyanElectric
import com.yotech.valtprinter.ui.theme.NavySurface
import com.yotech.valtprinter.ui.theme.VioletElectric

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
    pairedDevices: List<PairedDeviceEntity>,
    devices: List<PrinterDevice>,
    usbPresent: Boolean,
    onPairedConnect: (PairedDeviceEntity) -> Unit,
    onPairedDetails: (PairedDeviceEntity) -> Unit,
    onDeviceSelected: (PrinterDevice) -> Unit,
    onStopScan: () -> Unit,
    onUsbConnect: () -> Unit
) {
    var showAllPaired by remember { mutableStateOf(false) }
    val visiblePaired = if (showAllPaired) pairedDevices else pairedDevices.take(2)

    val pairedIds = remember(pairedDevices) { pairedDevices.map { it.id }.toSet() }
    val nearbyIds = remember(devices) { devices.map { it.id }.toSet() }
    val unpaired = remember(devices, pairedIds) { devices.filter { it.id !in pairedIds } }

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

        Spacer(Modifier.height(12.dp))

        if (pairedDevices.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Text(
                    "Paired Devices",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyanElectric
                )
            }

            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    visiblePaired.forEachIndexed { index, paired ->
                        PairedDeviceItem(
                            device = paired,
                            isLastConnected = index == 0,
                            isNearby = paired.id in nearbyIds,
                            onConnect = { onPairedConnect(paired) },
                            onDetails = { onPairedDetails(paired) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                    if (pairedDevices.size > 2) {
                        TextButton(
                            onClick = { showAllPaired = !showAllPaired },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (showAllPaired) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (showAllPaired) "See Less" else "See More")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Available Devices",
                style = MaterialTheme.typography.titleMedium,
                color = CyanElectric
            )
            TextButton(onClick = onStopScan) {
                Text("Stop Scan")
            }
        }

        if (unpaired.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyanElectric)
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(unpaired, key = { it.id }) { device ->
                        DeviceListItem(device, onClick = { onDeviceSelected(device) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    }
                }
            }
        }
    }
}


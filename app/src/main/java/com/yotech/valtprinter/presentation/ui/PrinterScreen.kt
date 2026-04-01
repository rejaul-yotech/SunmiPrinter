package com.yotech.valtprinter.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yotech.valtprinter.data.DiscoveryMode
import com.yotech.valtprinter.presentation.viewmodel.PrinterViewModel

@Composable
fun PrinterScreen(viewModel: PrinterViewModel = hiltViewModel()) {
    val status by viewModel.printerStatus.collectAsStateWithLifecycle()
    // This now collects the wrapped DiscoveredPrinter objects
    val printers by viewModel.discoveredPrinters.collectAsStateWithLifecycle()

    val isConnected = status.startsWith("Connected")

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Valt Printer Discovery",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
            )
        ) {
            Text(
                text = "Status: $status",
                modifier = Modifier.padding(16.dp),
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.startDiscovery() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scan for Printers")
        }

        Text(
            text = "Select a device to connect:",
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall
        )

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(printers) { discovered ->

                val info = discovered.printer.cloudPrinterInfo

                ListItem(
                    leadingContent = {
                        // Dynamically pick the icon based on how we found it
                        val icon = when (discovered.discoveryMode) {
                            DiscoveryMode.USB -> Icons.Default.Usb
                            DiscoveryMode.BLUETOOTH -> Icons.Default.Bluetooth
                            else -> Icons.Default.Wifi
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = discovered.discoveryMode.name,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    },
                    headlineContent = {
                        // This will display "NT311" or the specific model name
                        Text(info.name ?: "SUNMI Printer", fontWeight = FontWeight.Bold)
                    },
                    supportingContent = {
                        val info = discovered.printer.cloudPrinterInfo
                        val displayAddress = when (discovered.discoveryMode) {
                            DiscoveryMode.USB -> "USB Device (VID: ${info.vid})"
                            DiscoveryMode.BLUETOOTH -> "MAC: ${info.mac ?: "N/A"}"
                            DiscoveryMode.LAN -> "IP: ${info.address ?: "Searching..."}"
                        }
                        Text(displayAddress)
                    },
                    modifier = Modifier.clickable {
                        // Pass the raw CloudPrinter object back to the ViewModel to connect
                        viewModel.connectToDevice(discovered.printer)
                    }
                )
                HorizontalDivider()
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.printLabel() },
            enabled = isConnected,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
        ) {
            Text("Print Label", color = Color.White)
        }

        TextButton(onClick = { viewModel.disconnect() }) {
            Text("Disconnect & Reset")
        }
    }
}
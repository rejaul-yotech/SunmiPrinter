package com.yotech.valtprinter.presentation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yotech.valtprinter.presentation.viewmodel.PrinterViewModel

@Composable
fun PrinterScreen(viewModel: PrinterViewModel = hiltViewModel()) {
    val status by viewModel.printerStatus.collectAsStateWithLifecycle()
    val printers by viewModel.discoveredPrinters.collectAsStateWithLifecycle()

    // Determine if the "Print" button should be active
    val isConnected = status.startsWith("Connected")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Valt Printer Discovery",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Status Indicator
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

        // List of found printers
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(printers) { printer ->
                ListItem(
                    headlineContent = {
                        Text(
                            printer.cloudPrinterInfo.address ?: "Unknown Address"
                        )
                    },
                    supportingContent = { Text("Click to Connect") },
                    modifier = Modifier.clickable { viewModel.connectToDevice(printer) }
                )
                HorizontalDivider()
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons
        Button(
            onClick = { viewModel.printLabel() },
            enabled = isConnected, // Enabled only when status is "Connected"
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
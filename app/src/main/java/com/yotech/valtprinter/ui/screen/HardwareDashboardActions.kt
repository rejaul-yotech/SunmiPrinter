package com.yotech.valtprinter.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.ui.theme.CyanElectric
import com.yotech.valtprinter.ui.theme.NavySurface

@Composable
fun HardwareDashboardActions(
    state: PrinterState,
    onPreviewClick: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onScanOthers: () -> Unit
) {
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
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("DISCONNECT PRINTER", color = MaterialTheme.colorScheme.error)
            }
        } else if (state is PrinterState.Reconnecting || state is PrinterState.Error) {
            Button(
                onClick = onDisconnect,
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


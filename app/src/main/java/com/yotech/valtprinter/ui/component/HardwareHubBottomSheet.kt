package com.yotech.valtprinter.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.ui.theme.CyanElectric

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareHubBottomSheet(
    state: PrinterState,
    securedJobsLabel: String,
    onCollapse: () -> Unit,
    onStopRecovery: () -> Unit,
    onChangePrinter: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onCollapse,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = CyanElectric
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Hardware recovery",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )

            val headline = when (state) {
                is PrinterState.Reconnecting -> "Reconnecting to ${state.deviceName}"
                is PrinterState.Error -> "Printer disconnected"
                else -> "Hardware state"
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )

            val micro = when (state) {
                is PrinterState.Reconnecting -> state.microState
                is PrinterState.Error -> state.diagnosticMessage
                else -> ""
            }
            if (micro.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = micro,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            if (securedJobsLabel.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = securedJobsLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = CyanElectric,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onStopRecovery,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.92f))
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Stop recovery", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onChangePrinter,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                border = ButtonDefaults.outlinedButtonBorder
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Change / scan printers", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(18.dp))
        }
    }
}


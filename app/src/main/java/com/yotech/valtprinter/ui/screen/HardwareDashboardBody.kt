package com.yotech.valtprinter.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yotech.valtprinter.data.local.entity.PrintJobEntity
import com.yotech.valtprinter.domain.model.PrinterState
import com.yotech.valtprinter.ui.theme.CyanElectric

@Composable
fun ColumnScope.HardwareDashboardBody(
    state: PrinterState,
    recentJobs: List<PrintJobEntity>
) {
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
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
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
}


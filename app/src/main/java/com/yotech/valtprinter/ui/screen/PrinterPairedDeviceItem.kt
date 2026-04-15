package com.yotech.valtprinter.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yotech.valtprinter.data.local.entity.PairedDeviceEntity
import com.yotech.valtprinter.ui.theme.CyanElectric

@Composable
fun PairedDeviceItem(
    device: PairedDeviceEntity,
    isLastConnected: Boolean,
    isNearby: Boolean,
    onConnect: () -> Unit,
    onDetails: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onConnect),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                imageVector = when (device.connectionType) {
                    "USB" -> Icons.Default.Usb
                    "LAN" -> Icons.Default.Wifi
                    else -> Icons.Default.Bluetooth
                },
                contentDescription = null,
                tint = CyanElectric
            )
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                if (isLastConnected) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = CyanElectric.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "LAST USED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanElectric,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    device.model ?: device.address.ifBlank { device.connectionType },
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatLastSeen(device.lastSeenAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                    if (isNearby) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = Color(0xFF1DB954).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "NEARBY",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1DB954),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Device details",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onDetails)
            )
        }
    )
}

private fun formatLastSeen(lastSeenAt: Long): String {
    val diff = System.currentTimeMillis() - lastSeenAt
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 604_800_000L -> "${diff / 86_400_000L}d ago"
        else -> "${diff / 604_800_000L}w ago"
    }
}


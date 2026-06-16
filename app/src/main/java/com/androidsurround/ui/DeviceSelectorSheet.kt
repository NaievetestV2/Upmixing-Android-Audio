package com.androidsurround.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidsurround.model.AudioDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectorSheet(
    devices: List<AudioDevice>,
    selectedDevices: Map<String, AudioDevice>,
    onDeviceToggle: (AudioDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Select Output Devices",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Selected: ${selectedDevices.size}/8 devices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No audio output devices found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(devices, key = { it.uniqueId }) { device ->
                        DeviceItem(
                            device = device,
                            isSelected = device.uniqueId in selectedDevices,
                            onClick = { onDeviceToggle(device) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DeviceItem(
    device: AudioDevice,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val icon: ImageVector = when {
        device.isUsb -> Icons.Filled.Usb
        device.address.startsWith("bluez") || device.address.contains(":") -> Icons.Filled.Bluetooth
        else -> Icons.Filled.Speaker
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    buildString {
                        append(device.productName.ifEmpty { "Unknown" })
                        if (device.channelCounts.isNotEmpty()) {
                            append(" · ${device.channelCounts.max()}ch")
                        }
                        if (device.sampleRates.isNotEmpty()) {
                            append(" · ${device.sampleRates.max() / 1000}kHz")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.Circle,
                contentDescription = if (isSelected) "Selected" else "Not selected",
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

package com.androidsurround.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidsurround.model.AudioDevice
import com.androidsurround.model.ChannelLayout
import com.androidsurround.model.ChannelPosition
import com.androidsurround.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceMappingDialog(
    devices: List<AudioDevice>,
    layout: ChannelLayout,
    currentMappings: Map<String, List<ChannelPosition>>,
    onMappingChanged: (String, List<ChannelPosition>) -> Unit,
    onDismiss: () -> Unit,
) {
    var hasConflict by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map Devices to Channels", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Select which channels each device plays.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    layout.displayName + " — ${layout.channelCount} channels",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(devices, key = { it.uniqueId }) { device ->
                        val assigned = currentMappings[device.uniqueId] ?: emptyList()
                        DeviceChannelSelector(
                            device = device,
                            layout = layout,
                            assigned = assigned,
                            onAssign = { chs -> onMappingChanged(device.uniqueId, chs) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                val assignedAll = currentMappings.values.flatten().toSet()
                val allChannels = layout.channels.toSet()
                hasConflict = assignedAll != allChannels
                if (hasConflict) {
                    Spacer(Modifier.height(8.dp))
                    val missing = allChannels - assignedAll
                    val extra = assignedAll - allChannels
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            buildString {
                                if (missing.isNotEmpty()) {
                                    append("Unassigned: ${missing.joinToString(" ") { it.shortLabel }}")
                                }
                                if (extra.isNotEmpty()) {
                                    if (missing.isNotEmpty()) append("\n")
                                    append("Extra: ${extra.joinToString(" ") { it.shortLabel }}")
                                }
                            },
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !hasConflict,
            ) { Text("Done") }
        },
    )
}

@Composable
private fun DeviceChannelSelector(
    device: AudioDevice,
    layout: ChannelLayout,
    assigned: List<ChannelPosition>,
    onAssign: (List<ChannelPosition>) -> Unit,
) {
    val toggled = remember(assigned) { mutableSetOf<ChannelPosition>().also { it.addAll(assigned) } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(device.displayName, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                layout.channels.forEach { pos ->
                    val selected = pos in toggled
                    val color = when (pos) {
                        ChannelPosition.FL -> ChannelColorFL
                        ChannelPosition.FR -> ChannelColorFR
                        ChannelPosition.FC -> ChannelColorFC
                        ChannelPosition.LFE -> ChannelColorLFE
                        ChannelPosition.RL -> ChannelColorRL
                        ChannelPosition.RR -> ChannelColorRR
                        ChannelPosition.SL -> ChannelColorSL
                        ChannelPosition.SR -> ChannelColorSR
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (selected) color else color.copy(alpha = 0.2f))
                                .border(
                                    2.dp, if (selected) color else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                                .clickable {
                                    if (selected) toggled.remove(pos)
                                    else toggled.add(pos)
                                    onAssign(toggled.toList())
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(Icons.Filled.Check, null,
                                    tint = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(pos.shortLabel, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (assigned.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("${assigned.size} channel(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

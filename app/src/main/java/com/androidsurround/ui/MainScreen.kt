package com.androidsurround.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidsurround.model.AudioDevice
import com.androidsurround.model.ChannelLayout
import com.androidsurround.model.UpmixConfig
import com.androidsurround.playback.PlaybackState
import com.androidsurround.root.RootShell.RootStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    playbackState: PlaybackState,
    currentLayout: ChannelLayout,
    upmixConfig: UpmixConfig,
    availableDevices: List<AudioDevice>,
    selectedDevices: Map<String, AudioDevice>,
    isEngineActive: Boolean,
    rootStatus: RootStatus,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenUrlAction: () -> Unit,
    onOpenFileAction: () -> Unit,
    onLayoutSelected: (ChannelLayout) -> Unit,
    onUpmixConfigChanged: (UpmixConfig) -> Unit,
    onDeviceToggle: (AudioDevice) -> Unit,
    onRefreshDevices: () -> Unit,
    onToggleEngine: () -> Unit,
) {
    var showDeviceSheet by remember { mutableStateOf(false) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var showUpmixDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AndroidSurround", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                actions = {
                    if (rootStatus.available) {
                        Icon(
                            Icons.Filled.VerifiedUser,
                            contentDescription = "Root available",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onToggleEngine,
                containerColor = if (isEngineActive)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    if (isEngineActive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (isEngineActive) "Stop" else "Start",
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isEngineActive) "Stop Engine" else "Start Engine")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MediaPlayerBar(
                state = playbackState,
                onTogglePlayPause = onTogglePlayPause,
                onSeek = onSeek,
                onOpenUrl = onOpenUrlAction,
                onOpenFile = onOpenFileAction,
            )

            DeviceSection(
                selectedDevices = selectedDevices,
                availableCount = availableDevices.size,
                onSelectDevices = { showDeviceSheet = true },
                onRefreshDevices = onRefreshDevices,
            )

            ChannelSection(
                currentLayout = currentLayout,
                onClick = { showChannelDialog = true },
            )

            UpmixSection(
                config = upmixConfig,
                onClick = { showUpmixDialog = true },
            )

            if (selectedDevices.isNotEmpty()) {
                DeviceChannelMap(
                    selectedDevices = selectedDevices.values.toList(),
                    layout = currentLayout,
                )
            }
        }
    }

    if (showDeviceSheet) {
        DeviceSelectorSheet(
            devices = availableDevices,
            selectedDevices = selectedDevices,
            onDeviceToggle = onDeviceToggle,
            onDismiss = { showDeviceSheet = false },
        )
    }

    if (showChannelDialog) {
        ChannelConfigDialog(
            currentLayout = currentLayout,
            onLayoutSelected = {
                onLayoutSelected(it)
                showChannelDialog = false
            },
            onDismiss = { showChannelDialog = false },
        )
    }

    if (showUpmixDialog) {
        UpmixSettingsDialog(
            config = upmixConfig,
            onConfigChanged = {
                onUpmixConfigChanged(it)
                showUpmixDialog = false
            },
            onDismiss = { showUpmixDialog = false },
        )
    }
}

@Composable
private fun DeviceSection(
    selectedDevices: Map<String, AudioDevice>,
    availableCount: Int,
    onSelectDevices: () -> Unit,
    onRefreshDevices: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Audio Devices", fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefreshDevices) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${selectedDevices.size} selected · $availableCount available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSelectDevices,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Configure Devices")
            }
        }
    }
}

@Composable
private fun ChannelSection(
    currentLayout: ChannelLayout,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.SurroundSound,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Channel Layout", fontWeight = FontWeight.Bold)
                Text(
                    currentLayout.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = "Change")
        }
    }
}

@Composable
private fun UpmixSection(
    config: UpmixConfig,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = if (config.enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Upmixing", fontWeight = FontWeight.Bold)
                Text(
                    if (config.enabled) "${config.method.displayName} · ${config.lfeCutoffHz}Hz LFE cutoff"
                    else "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (config.enabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = "Configure")
        }
    }
}

@Composable
private fun DeviceChannelMap(
    selectedDevices: List<AudioDevice>,
    layout: ChannelLayout,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Device → Channel Mapping", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            if (selectedDevices.size == 1) {
                Text(
                    "All ${layout.channelCount} channels → ${selectedDevices.first().displayName}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val chsPerDevice = (layout.channelCount - 1) / selectedDevices.size
                selectedDevices.forEachIndexed { idx, device ->
                    val nonLfe = layout.channels.filter { it != com.androidsurround.model.ChannelPosition.LFE }
                    val start = idx * chsPerDevice
                    val end = if (idx == selectedDevices.lastIndex) nonLfe.size
                             else (start + chsPerDevice).coerceAtMost(nonLfe.size)
                    val chs = nonLfe.subList(start, end).toMutableList()
                    if (idx == 0 && com.androidsurround.model.ChannelPosition.LFE in layout.channels) {
                        chs.add(com.androidsurround.model.ChannelPosition.LFE)
                    }
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(
                                device.productName.take(12),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            chs.joinToString(" ") { it.shortLabel },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

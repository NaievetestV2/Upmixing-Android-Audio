package com.androidsurround.ui

import androidx.compose.foundation.layout.*
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
import com.androidsurround.model.ChannelPosition
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
    onOpenUrl: (String) -> Unit,
    onOpenFileAction: () -> Unit,
    onOpenBrowser: () -> Unit,
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
                        Icon(Icons.Filled.VerifiedUser, contentDescription = "Root",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onToggleEngine,
                containerColor = if (isEngineActive) MaterialTheme.colorScheme.errorContainer
                               else MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(if (isEngineActive) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MediaPlayerBar(
                state = playbackState,
                onTogglePlayPause = onTogglePlayPause,
                onSeek = onSeek,
                onOpenUrl = onOpenUrl,
                onOpenFile = onOpenFileAction,
                onOpenBrowser = onOpenBrowser,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Audio Devices", fontWeight = FontWeight.Bold)
                        IconButton(onClick = onRefreshDevices) {
                            Icon(Icons.Filled.Refresh, "Refresh")
                        }
                    }
                    Text("${selectedDevices.size} selected · $availableDevices available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showDeviceSheet = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.List, null); Spacer(Modifier.width(8.dp)); Text("Configure Devices")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showChannelDialog = true },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SurroundSound, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Channel Layout", fontWeight = FontWeight.Bold)
                        Text(currentLayout.displayName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Icon(Icons.Filled.ChevronRight, "Change")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showUpmixDialog = true },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, null, tint = if (upmixConfig.enabled)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Upmixing", fontWeight = FontWeight.Bold)
                        Text(if (upmixConfig.enabled) "${upmixConfig.method.displayName} · ${upmixConfig.lfeCutoffHz}Hz"
                             else "Disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (upmixConfig.enabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ChevronRight, "Configure")
                }
            }

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
            onLayoutSelected = { onLayoutSelected(it); showChannelDialog = false },
            onDismiss = { showChannelDialog = false },
        )
    }
    if (showUpmixDialog) {
        UpmixSettingsDialog(
            config = upmixConfig,
            onConfigChanged = { onUpmixConfigChanged(it); showUpmixDialog = false },
            onDismiss = { showUpmixDialog = false },
        )
    }
}

@Composable
private fun DeviceChannelMap(
    selectedDevices: List<AudioDevice>,
    layout: ChannelLayout,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Device → Channel Mapping", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (selectedDevices.size == 1) {
                Text("All ${layout.channelCount} channels → ${selectedDevices.first().displayName}",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                val nonLfe = layout.channels.filter { it != ChannelPosition.LFE }
                val chsPerDevice = (nonLfe.size / selectedDevices.size).coerceAtLeast(1)
                selectedDevices.forEachIndexed { idx, device ->
                    val start = idx * chsPerDevice
                    val end = if (idx == selectedDevices.lastIndex) nonLfe.size
                             else (start + chsPerDevice).coerceAtMost(nonLfe.size)
                    val chs = nonLfe.subList(start, end).toMutableList()
                    if (idx == 0 && ChannelPosition.LFE in layout.channels) chs.add(ChannelPosition.LFE)
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(device.productName.take(12),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(chs.joinToString(" ") { it.shortLabel }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

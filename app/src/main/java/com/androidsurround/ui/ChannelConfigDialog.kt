package com.androidsurround.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.androidsurround.model.ChannelLayout
import com.androidsurround.model.ChannelPosition
import com.androidsurround.ui.theme.*

@Composable
fun ChannelConfigDialog(
    currentLayout: ChannelLayout,
    onLayoutSelected: (ChannelLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Channel Layout", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Select your speaker configuration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                ChannelLayout.ALL.forEach { layout ->
                    LayoutOption(
                        layout = layout,
                        isSelected = layout == currentLayout,
                        onClick = { onLayoutSelected(layout) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun LayoutOption(
    layout: ChannelLayout,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                           else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLayoutPreview(layout, modifier = Modifier.size(64.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(layout.displayName, fontWeight = FontWeight.Bold)
                Text(
                    "${layout.channelCount} channels: ${layout.channels.joinToString(" ") { it.shortLabel }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Filled.Check, contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ChannelLayoutPreview(layout: ChannelLayout, modifier: Modifier = Modifier) {
    val positions = layout.channels.associateWith { pos ->
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
        color to when (pos) {
            ChannelPosition.FL -> Pair(0.25f, 0.3f)
            ChannelPosition.FR -> Pair(0.75f, 0.3f)
            ChannelPosition.FC -> Pair(0.5f, 0.2f)
            ChannelPosition.LFE -> Pair(0.5f, 0.5f)
            ChannelPosition.RL -> Pair(0.2f, 0.8f)
            ChannelPosition.RR -> Pair(0.8f, 0.8f)
            ChannelPosition.SL -> Pair(0.15f, 0.55f)
            ChannelPosition.SR -> Pair(0.85f, 0.55f)
        }
    }

    Box(modifier = modifier) {
        for ((pos, info) in positions) {
            val (color, pair) = info
            val (x, y) = pair
            Box(
                modifier = Modifier
                    .offset(x = (x * 48).dp, y = (y * 48).dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

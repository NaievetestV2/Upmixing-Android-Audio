package com.androidsurround.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidsurround.playback.PlaybackState

@Composable
fun MediaPlayerBar(
    state: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenUrl: () -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showUrlDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenFile) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = "Open file")
                }
                IconButton(onClick = { showUrlDialog = true }) {
                    Icon(Icons.Filled.Link, contentDescription = "Open URL")
                }
            }

            if (state.currentTitle.isNotEmpty()) {
                Text(
                    text = state.currentTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "No media loaded",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            if (state.durationMs > 0) {
                Slider(
                    value = state.positionMs.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..state.durationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatTime(state.positionMs), style = MaterialTheme.typography.bodySmall)
                    Text(formatTime(state.durationMs), style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.PauseCircleFilled
                                     else Icons.Filled.PlayCircleFilled,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showUrlDialog) {
        UrlInputDialog(
            onDismiss = { showUrlDialog = false },
            onSubmit = {
                showUrlDialog = false
                onOpenUrl()
            },
        )
    }
}

@Composable
private fun UrlInputDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stream Audio URL") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                placeholder = { Text("https://example.com/audio.mp3") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(url) },
                enabled = url.isNotBlank(),
            ) { Text("Play") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

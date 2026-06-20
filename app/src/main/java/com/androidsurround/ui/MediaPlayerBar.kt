package com.androidsurround.ui

import android.view.Surface
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.androidsurround.playback.PlaybackState

@Composable
fun MediaPlayerBar(
    state: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenFile: () -> Unit,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
    onSurfaceChanged: ((Surface?) -> Unit)? = null,
    hasVideoAvailable: Boolean = false,
) {
    var showUrlDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenFile) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = "Open file")
                }
                IconButton(onClick = onOpenBrowser) {
                    Icon(Icons.Filled.Language, contentDescription = "Web browser")
                }
                IconButton(onClick = { showUrlDialog = true }) {
                    Icon(Icons.Filled.Link, contentDescription = "Stream URL")
                }
            }

            Text(
                text = state.currentTitle.ifEmpty { "No media loaded" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (state.currentTitle.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
            )

            if (hasVideoAvailable) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).also { sv ->
                                sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                        onSurfaceChanged?.invoke(holder.surface)
                                    }
                                    override fun surfaceChanged(
                                        holder: android.view.SurfaceHolder,
                                        format: Int, w: Int, h: Int,
                                    ) {}
                                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                        onSurfaceChanged?.invoke(null)
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Slider(
                value = state.positionMs.toFloat()
                    .coerceIn(0f, state.durationMs.toFloat().coerceAtLeast(1f)),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f),
                modifier = Modifier.fillMaxWidth(),
                enabled = state.durationMs > 0,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(state.positionMs), style = MaterialTheme.typography.bodySmall)
                Text(formatTime(state.durationMs), style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(56.dp)) {
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
                Text(error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showUrlDialog) {
        var urlInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Stream URL") },
            text = {
                OutlinedTextField(
                    value = urlInput, onValueChange = { urlInput = it },
                    label = { Text("URL") }, placeholder = { Text("https://...") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showUrlDialog = false; onOpenUrl(urlInput) },
                    enabled = urlInput.isNotBlank(),
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") } },
        )
    }
}

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

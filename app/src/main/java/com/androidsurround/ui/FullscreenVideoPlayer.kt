package com.androidsurround.ui

import android.view.Surface
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidsurround.playback.PlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FullscreenVideoPlayer(
    playbackState: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
    onSurfaceChanged: ((Surface?) -> Unit)? = null,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    val hideJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    fun scheduleHide() {
        hideJob.value?.cancel()
        hideJob.value = scope.launch {
            delay(3000)
            controlsVisible = false
        }
    }

    LaunchedEffect(Unit) { scheduleHide() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        awaitPointerEvent()
                        controlsVisible = true
                        scheduleHide()
                    }
                },
            contentAlignment = Alignment.Center,
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

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(Modifier.fillMaxSize().background(Color(0x80000000))) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    ) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White)
                    }

                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(64.dp).align(Alignment.Center),
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Filled.PauseCircleFilled
                                         else Icons.Filled.PlayCircleFilled,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(56.dp),
                            tint = Color.White,
                        )
                    }

                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Slider(
                            value = playbackState.positionMs.toFloat()
                                .coerceIn(0f, playbackState.durationMs.toFloat().coerceAtLeast(1f)),
                            onValueChange = { onSeek(it.toLong()) },
                            valueRange = 0f..playbackState.durationMs.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color(0x80FFFFFF),
                            ),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                formatTime(playbackState.positionMs),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                formatTime(playbackState.durationMs),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

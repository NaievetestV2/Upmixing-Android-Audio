package com.androidsurround.playback

import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val currentTitle: String = "",
    val currentUri: Uri? = null,
    val error: String? = null,
    val hasVideo: Boolean = false,
)

class MediaPlayerManager(private val context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var scope: CoroutineScope? = null
    private var positionPollJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { updateState(); startStopPolling() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updateState(); startStopPolling() }
            override fun onPlayerError(error: PlaybackException) {
                _playbackState.value = _playbackState.value.copy(
                    error = error.localizedMessage ?: "Playback error"
                )
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updateState() }
        })
    }

    private fun updateState() {
        val meta = player.mediaMetadata
        val videoSize = player.videoSize
        _playbackState.value = PlaybackState(
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it >= 0 } ?: 0,
            currentTitle = meta.title?.toString() ?: meta.displayTitle?.toString()
                ?: player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "",
            currentUri = player.currentMediaItem?.localConfiguration?.uri,
            error = null,
            hasVideo = videoSize != null && (videoSize.width > 0 || videoSize.height > 0),
        )
    }

    private fun startStopPolling() {
        if (player.isPlaying) {
            if (positionPollJob == null) {
                scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
                positionPollJob = scope?.launch {
                    while (isActive) {
                        _playbackState.value = _playbackState.value.copy(
                            positionMs = player.currentPosition,
                            isPlaying = player.isPlaying,
                        )
                        delay(250)
                    }
                }
            }
        } else {
            positionPollJob?.cancel()
            positionPollJob = null
        }
    }

    fun setSurfaceHolder(holder: SurfaceHolder?) {
        player.setVideoSurfaceHolder(holder)
    }

    fun playUri(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
    }

    fun playUrl(url: String) { playUri(Uri.parse(url)) }
    fun playFile(path: String) { playUri(Uri.parse("file://$path")) }

    val currentUri: Uri? get() = _playbackState.value.currentUri

    fun stopPlayback() { player.stop(); player.clearMediaItems() }
    fun pausePlayback() { player.pause() }
    fun resumePlayback() { player.play() }
    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) { player.seekTo(positionMs) }
    fun setVolume(volume: Float) { player.volume = volume }
    fun release() { positionPollJob?.cancel(); scope?.cancel(); player.release() }
}

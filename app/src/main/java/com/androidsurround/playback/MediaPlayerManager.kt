package com.androidsurround.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
)

class MediaPlayerManager(private val context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue.asStateFlow()

    private var pcmCallback: ((FloatArray, Int) -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                updateState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState()
            }

            override fun onPlayerError(error: PlaybackException) {
                _playbackState.value = _playbackState.value.copy(
                    error = error.localizedMessage ?: "Playback error"
                )
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateState()
            }
        })
    }

    fun onPcmData(callback: (FloatArray, Int) -> Unit) {
        pcmCallback = callback
    }

    private fun updateState() {
        val meta = player.mediaMetadata
        _playbackState.value = PlaybackState(
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it >= 0 } ?: 0,
            currentTitle = meta.title?.toString()
                ?: meta.displayTitle?.toString()
                ?: player.currentMediaItem?.mediaMetadata?.title?.toString()
                ?: "",
            currentUri = player.currentMediaItem?.localConfiguration?.uri,
            error = null,
        )
    }

    fun playUri(uri: Uri) {
        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun playUrl(url: String) {
        playUri(Uri.parse(url))
    }

    fun playFile(path: String) {
        playUri(Uri.parse("file://$path"))
    }

    fun play(mediaItems: List<MediaItem>, startIndex: Int = 0) {
        player.setMediaItems(mediaItems, startIndex, 0)
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun setVolume(volume: Float) {
        player.volume = volume
    }

    fun release() {
        player.release()
    }
}

package com.androidsurround

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Surface as AndroidSurface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidsurround.audio.AudioEngine
import com.androidsurround.audio.DeviceManager
import com.androidsurround.model.AudioDevice
import com.androidsurround.model.PlaylistItem
import com.androidsurround.playback.MediaPlayerManager
import com.androidsurround.playback.MediaThumbnailExtractor
import com.androidsurround.playback.PcmDecoder
import com.androidsurround.playback.PlaylistManager
import com.androidsurround.root.RootShell
import com.androidsurround.ui.BrowserSheet
import com.androidsurround.ui.MainScreen
import com.androidsurround.ui.theme.AndroidSurroundTheme

class MainActivity : ComponentActivity() {

    private lateinit var deviceManager: DeviceManager
    private lateinit var audioEngine: AudioEngine
    private lateinit var mediaPlayer: MediaPlayerManager
    private val pcmDecoder = PcmDecoder(this)
    private lateinit var playlistManager: PlaylistManager
    private var isFullscreen = false
    private var inlineSurface: AndroidSurface? = null

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) playUris(uris) }

    private var playlistAddTarget: String? = null
    private val playlistFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val target = playlistAddTarget ?: return@registerForActivityResult
        playlistAddTarget = null
        uris.forEach { uri ->
            val item = PlaylistItem(uri = uri.toString())
            playlistManager.addToPlaylist(target, item)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceManager = DeviceManager(this)
        audioEngine = AudioEngine(this)
        mediaPlayer = MediaPlayerManager(this)
        playlistManager = PlaylistManager(this)

        requestAudioPermissions()
        deviceManager.refreshDevices()

        setContent {
            AndroidSurroundTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val playbackState by mediaPlayer.playbackState.collectAsStateWithLifecycle()
                    val decoderState by pcmDecoder.playbackState.collectAsStateWithLifecycle()
                    val currentLayout by audioEngine.currentLayout.collectAsStateWithLifecycle()
                    val upmixConfig by audioEngine.upmixConfig.collectAsStateWithLifecycle()
                    val availableDevices by deviceManager.availableSinks.collectAsStateWithLifecycle()
                    val selectedDevices by deviceManager.selectedDevices.collectAsStateWithLifecycle()
                    val deviceChannelMappings by audioEngine.deviceChannelMappings.collectAsStateWithLifecycle()
                    val isEngineActive by audioEngine.isActive.collectAsStateWithLifecycle()
                    val rootStatusState = remember { mutableStateOf(RootShell.RootStatus()) }
                    var rootStatus: RootShell.RootStatus by rootStatusState
                    var showBrowser by remember { mutableStateOf(false) }

                    val playlists by playlistManager.playlists.collectAsStateWithLifecycle()
                    val queueItems by playlistManager.queueItems.collectAsStateWithLifecycle()
                    val queueIndex by playlistManager.currentIndex.collectAsStateWithLifecycle()
                    var playlistPickerTarget by remember { mutableStateOf<String?>(null) }

                    var albumArt by remember { mutableStateOf<Bitmap?>(null) }
                    val thumbnailExtractor = remember { MediaThumbnailExtractor(this@MainActivity) }

                    val activeState = if (isEngineActive) decoderState else playbackState
                    LaunchedEffect(activeState.currentUri) {
                        albumArt = null
                        val uri = activeState.currentUri ?: return@LaunchedEffect
                        if (!activeState.hasVideo) {
                            albumArt = thumbnailExtractor.getAlbumArt(uri)
                        }
                    }

                    LaunchedEffect(Unit) {
                        rootStatus = RootShell.checkRoot()
                    }

                    MainScreen(
                        playbackState = activeState,
                        currentLayout = currentLayout,
                        upmixConfig = upmixConfig,
                        availableDevices = availableDevices,
                        selectedDevices = selectedDevices,
                        deviceChannelMappings = deviceChannelMappings,
                        isEngineActive = isEngineActive,
                        rootStatus = rootStatus,
                        isFullscreen = isFullscreen,
                        onTogglePlayPause = { togglePlayPause() },
                        onSeek = { if (isEngineActive) pcmDecoder.seekTo(it) else mediaPlayer.seekTo(it) },
                        onOpenUrl = { url -> playUrl(url) },
                        onOpenFileAction = { openFilePicker() },
                        onOpenBrowser = { showBrowser = true },
                        onLayoutSelected = { audioEngine.setLayout(it) },
                        onUpmixConfigChanged = { audioEngine.setUpmixConfig(it) },
                        onDeviceToggle = { toggleDevice(it) },
                        onRefreshDevices = { deviceManager.refreshDevices() },
                        onDeviceMappingChanged = { deviceId, channels ->
                            audioEngine.setDeviceChannelMapping(deviceId, channels)
                        },
                        onToggleEngine = { toggleEngine() },
                        onSurfaceChanged = { surface ->
                            inlineSurface = surface
                            if (!isFullscreen) pcmDecoder.outputSurface = surface
                        },
                        onToggleFullscreen = { toggleFullscreen() },
                        onFullscreenSurface = { surface ->
                            if (isFullscreen || surface == null) pcmDecoder.updateOutputSurface(surface)
                        },
                        playlists = playlists,
                        queueItems = queueItems,
                        queueIndex = queueIndex,
                        isShuffled = playlistManager.isShuffled,
                        repeatMode = playlistManager.repeatMode.name,
                        albumArt = albumArt,
                        onOpenPlaylist = {},
                        onNext = { playQueueItem(playlistManager.next()) },
                        onPrevious = { playQueueItem(playlistManager.previous()) },
                        onToggleShuffle = { playlistManager.toggleShuffle() },
                        onCycleRepeat = { playlistManager.cycleRepeatMode() },
                        onCreatePlaylist = { name -> playlistManager.createPlaylist(name) },
                        onRenamePlaylist = { id, name -> playlistManager.renamePlaylist(id, name) },
                        onDeletePlaylist = { id -> playlistManager.deletePlaylist(id) },
                        onLoadPlaylist = { id ->
                            playlistManager.loadPlaylistIntoQueue(id)
                            playQueueItem(playlistManager.currentItem)
                        },
                        onPlaylistPlayItem = { index ->
                            playlistManager.playItem(index)
                            playQueueItem(playlistManager.currentItem)
                        },
                        onAddCurrentToPlaylist = { id ->
                            val item = currentPlaylistItem()
                            if (item != null) playlistManager.addToPlaylist(id, item)
                        },
                        onSaveQueueAsPlaylist = { name ->
                            playlistManager.saveQueueAsPlaylist(name)
                        },
                        onAddFilesToPlaylist = { id ->
                            playlistAddTarget = id
                            try {
                                playlistFileLauncher.launch(arrayOf("audio/*", "video/*"))
                            } catch (_: Exception) {}
                        },
                        onClearQueue = { playlistManager.clearQueue() },
                        onRemoveQueueItem = { index -> playlistManager.removeFromQueue(index) },
                    )

                    if (showBrowser) {
                        BrowserSheet(
                            onDismiss = { showBrowser = false },
                            onUrlSelected = { url ->
                                showBrowser = false
                                playUrl(url)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun currentPlaylistItem(): PlaylistItem? {
        val state = if (audioEngine.isActive.value) pcmDecoder.playbackState.value
                    else mediaPlayer.playbackState.value
        val uri = state.currentUri ?: return null
        return PlaylistItem(
            uri = uri.toString(),
            title = state.currentTitle,
            durationMs = state.durationMs,
            isVideo = state.hasVideo,
        )
    }

    private fun playQueueItem(item: PlaylistItem?) {
        if (item == null) return
        playUriInternal(Uri.parse(item.uri))
    }

    private fun playUriInternal(uri: Uri) {
        mediaPlayer.stopPlayback()
        pcmDecoder.stop()
        mediaPlayer.playUri(uri)
        if (audioEngine.isActive.value) startDecoder(uri)
    }

    private fun playUri(uri: Uri) {
        Log.i("Pipeline", "playUri: $uri")
        playUriInternal(uri)
    }

    private fun playUris(uris: List<Uri>) {
        Log.i("Pipeline", "playUris: ${uris.size} files")
        val items = uris.map { uri ->
            PlaylistItem(uri = uri.toString(), isVideo = false)
        }
        if (playlistManager.queueItems.value.isNotEmpty()) {
            playlistManager.appendToQueue(items)
        } else {
            playlistManager.loadIntoQueue(items, 0)
        }
        playUriInternal(uris.first())
    }

    private fun playUrl(url: String) {
        val isVideo = url.let { uri ->
            val path = Uri.parse(uri).path?.lowercase() ?: ""
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") ||
            path.endsWith(".avi") || path.endsWith(".mov") || path.endsWith(".3gp")
        }
        val item = PlaylistItem(uri = url, isVideo = isVideo)
        if (playlistManager.queueItems.value.isNotEmpty()) {
            playlistManager.appendToQueue(listOf(item))
        } else {
            playlistManager.loadIntoQueue(listOf(item), 0)
        }
        playUriInternal(Uri.parse(url))
    }

    private fun togglePlayPause() {
        if (audioEngine.isActive.value) {
            if (pcmDecoder.isRunning()) {
                if (pcmDecoder.isPaused()) {
                    pcmDecoder.resumeDecoder()
                } else {
                    pcmDecoder.pauseDecoder()
                }
            } else {
                val uri = mediaPlayer.currentUri
                if (uri != null) startDecoder(uri)
            }
        } else {
            mediaPlayer.togglePlayPause()
        }
    }

    private fun startDecoder(uri: Uri) {
        Log.i("Pipeline", "startDecoder: $uri")
        pcmDecoder.stop()
        mediaPlayer.pausePlayback()
        pcmDecoder.onPcmData = { data, ch, sr ->
            val frameData = audioEngine.processAudioFrame(data, ch)
            audioEngine.pushFrames(frameData)
        }
        pcmDecoder.onEnded = {
            Log.i("Pipeline", "Decoder ended naturally")
            runOnUiThread { stopDecoder() }
        }
        pcmDecoder.onError = { err ->
            Log.e("Pipeline", "Decoder error: $err")
        }
        pcmDecoder.start(uri)
    }

    private fun stopDecoder() {
        Log.i("Pipeline", "stopDecoder")
        pcmDecoder.stop()
        if (!audioEngine.isActive.value) mediaPlayer.resumePlayback()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (!isFullscreen) {
            pcmDecoder.updateOutputSurface(inlineSurface)
        }
    }

    private fun toggleDevice(device: AudioDevice) {
        if (deviceManager.isSelected(device)) deviceManager.deselectDevice(device)
        else deviceManager.selectDevice(device)
        audioEngine.setDevices(deviceManager.selectedDevices.value.values.toList())
    }

    private fun toggleEngine() {
        if (audioEngine.isActive.value) {
            Log.i("Pipeline", "Engine OFF: stopping decoder + pipeline")
            pcmDecoder.stop()
            audioEngine.stopPipeline()
            mediaPlayer.resumePlayback()
        } else {
            Log.i("Pipeline", "Engine ON: starting pipeline")
            audioEngine.startPipeline()
            if (!audioEngine.isActive.value) {
                Log.w("Pipeline", "Engine failed to start")
                return
            }
            val uri = mediaPlayer.currentUri
            mediaPlayer.pausePlayback()
            Log.i("Pipeline", "currentUri=$uri")
            if (uri != null) startDecoder(uri)
        }
    }

    private fun openFilePicker() {
        try { openFileLauncher.launch(arrayOf("audio/*", "video/*")) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri ->
            playlistManager.loadIntoQueue(listOf(
                PlaylistItem(uri = uri.toString())
            ), 0)
            playUriInternal(uri)
        }
    }

    private fun requestAudioPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) permissions.add(Manifest.permission.RECORD_AUDIO)
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 100)
    }

    override fun onDestroy() {
        pcmDecoder.stop(); audioEngine.release(); mediaPlayer.release(); super.onDestroy()
    }
}

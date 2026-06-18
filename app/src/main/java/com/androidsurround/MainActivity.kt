package com.androidsurround

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceHolder
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
import com.androidsurround.playback.MediaPlayerManager
import com.androidsurround.playback.PcmDecoder
import com.androidsurround.root.RootShell
import com.androidsurround.ui.BrowserSheet
import com.androidsurround.ui.MainScreen
import com.androidsurround.ui.theme.AndroidSurroundTheme

class MainActivity : ComponentActivity() {

    private lateinit var deviceManager: DeviceManager
    private lateinit var audioEngine: AudioEngine
    private lateinit var mediaPlayer: MediaPlayerManager
    private val pcmDecoder = PcmDecoder(this)
    private var surfaceHolder: SurfaceHolder? = null

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { playUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceManager = DeviceManager(this)
        audioEngine = AudioEngine(this)
        mediaPlayer = MediaPlayerManager(this)

        requestAudioPermissions()
        deviceManager.refreshDevices()

        setContent {
            AndroidSurroundTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val playbackState by mediaPlayer.playbackState.collectAsStateWithLifecycle()
                    val currentLayout by audioEngine.currentLayout.collectAsStateWithLifecycle()
                    val upmixConfig by audioEngine.upmixConfig.collectAsStateWithLifecycle()
                    val availableDevices by deviceManager.availableSinks.collectAsStateWithLifecycle()
                    val selectedDevices by deviceManager.selectedDevices.collectAsStateWithLifecycle()
                    val isEngineActive by audioEngine.isActive.collectAsStateWithLifecycle()
                    val rootStatusState = remember { mutableStateOf(RootShell.RootStatus()) }
                    var rootStatus: RootShell.RootStatus by rootStatusState
                    var showBrowser by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        rootStatus = RootShell.checkRoot()
                    }

                    MainScreen(
                        playbackState = playbackState,
                        currentLayout = currentLayout,
                        upmixConfig = upmixConfig,
                        availableDevices = availableDevices,
                        selectedDevices = selectedDevices,
                        isEngineActive = isEngineActive,
                        rootStatus = rootStatus,
                        onTogglePlayPause = { mediaPlayer.togglePlayPause() },
                        onSeek = { mediaPlayer.seekTo(it) },
                        onOpenUrl = { url -> playUrl(url) },
                        onOpenFileAction = { openFilePicker() },
                        onOpenBrowser = { showBrowser = true },
                        onLayoutSelected = { audioEngine.setLayout(it) },
                        onUpmixConfigChanged = { audioEngine.setUpmixConfig(it) },
                        onDeviceToggle = { toggleDevice(it) },
                        onRefreshDevices = { deviceManager.refreshDevices() },
                        onToggleEngine = { toggleEngine() },
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

    private fun playUri(uri: Uri) {
        mediaPlayer.playUri(uri)
        if (audioEngine.isActive.value) startDecoder(uri)
    }

    private fun playUrl(url: String) {
        mediaPlayer.playUrl(url)
        if (audioEngine.isActive.value) startDecoder(Uri.parse(url))
    }

    private fun startDecoder(uri: Uri) {
        pcmDecoder.stop()
        pcmDecoder.onPcmData = { data, ch, sr ->
            android.util.Log.d("Pipeline", "PCM data: ${data.size} floats, $ch ch, $sr Hz")
            val frameData = audioEngine.processAudioFrame(data, ch)
            audioEngine.pushFrames(frameData)
        }
        pcmDecoder.onEnded = {
            android.util.Log.d("Pipeline", "Decoder ended")
        }
        pcmDecoder.onError = { err ->
            android.util.Log.e("Pipeline", "Decoder error: $err")
            runOnUiThread {
                mediaPlayer.setVolume(1f)
            }
        }
        pcmDecoder.start(uri)
    }

    private fun toggleDevice(device: AudioDevice) {
        if (deviceManager.isSelected(device)) deviceManager.deselectDevice(device)
        else deviceManager.selectDevice(device)
        audioEngine.setDevices(deviceManager.selectedDevices.value.values.toList())
    }

    private fun toggleEngine() {
        if (audioEngine.isActive.value) {
            audioEngine.stopPipeline()
            pcmDecoder.stop()
            mediaPlayer.setVolume(1f)
        } else {
            audioEngine.startPipeline()
            mediaPlayer.setVolume(0f)
            val uri = mediaPlayer.playbackState.value.currentUri
            if (uri != null) startDecoder(uri)
        }
    }

    private fun openFilePicker() {
        try { openFileLauncher.launch(arrayOf("audio/*", "video/*")) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { playUri(it) }
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

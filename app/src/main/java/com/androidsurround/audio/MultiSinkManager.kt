package com.androidsurround.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.androidsurround.model.AudioDevice
import com.androidsurround.model.ChannelLayout
import com.androidsurround.model.ChannelPosition
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MultiSinkManager(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val tracks = mutableMapOf<String, AudioTrack>()
    private var sampleRate = 48000
    private var bufferSize = 8192
    private var scope: CoroutineScope? = null
    private var writeJob: Job? = null
    private var frameData = mutableMapOf<String, FloatArray>()
    private val lock = Any()

    data class DeviceChannelAssignment(
        val device: AudioDevice,
        val channels: List<ChannelPosition>,
    )

    private var assignments = listOf<DeviceChannelAssignment>()

    fun configure(
        devices: List<AudioDevice>,
        layout: ChannelLayout,
        rate: Int,
    ) {
        sampleRate = rate
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        bufferSize = (minBuf * 8).coerceIn(16384, 131072)

        assignments = if (devices.size == 1) {
            listOf(DeviceChannelAssignment(devices.first(), layout.channels))
        } else {
            distributeChannels(devices, layout)
        }
    }

    private fun distributeChannels(
        devices: List<AudioDevice>,
        layout: ChannelLayout,
    ): List<DeviceChannelAssignment> {
        if (devices.isEmpty()) return emptyList()
        val nonLfe = layout.channels.filter { it != ChannelPosition.LFE }
        val hasLfe = ChannelPosition.LFE in layout.channels
        val result = mutableListOf<DeviceChannelAssignment>()
        var idx = 0
        for ((i, d) in devices.withIndex()) {
            val cnt = if (i == devices.lastIndex) nonLfe.size - idx
                     else (nonLfe.size / devices.size).coerceAtLeast(1)
            val chs = nonLfe.subList(idx, (idx + cnt).coerceAtMost(nonLfe.size))
            result.add(DeviceChannelAssignment(d, chs.toList()))
            idx += cnt
        }
        if (hasLfe) {
            val first = result.firstOrNull() ?: return result
            result[0] = first.copy(channels = first.channels + ChannelPosition.LFE)
        }
        return result
    }

    fun start() {
        if (_isRunning.value) return
        stop()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var startedOk = true
        scope?.launch {
            for (a in assignments) {
                val t = createAudioTrack(a)
                if (t == null) { startedOk = false; break }
                val silentBuf = FloatArray(bufferSize / 4)
                t.write(silentBuf, 0, silentBuf.size, AudioTrack.WRITE_BLOCKING)
                t.play()
                synchronized(lock) { tracks[a.device.uniqueId] = t }
            }
            if (!startedOk) { stop(); return@launch }
            _isRunning.value = true

            writeJob = launch {
                val tempBuf = FloatArray(4096)
                while (isActive) {
                    if (!_isRunning.value) break
                    val snapshot: Map<String, FloatArray>
                    synchronized(lock) {
                        snapshot = frameData.toMap()
                        frameData.clear()
                    }
                    val trackSnapshot: Map<String, AudioTrack>
                    synchronized(lock) { trackSnapshot = tracks.toMap() }

                    for ((id, track) in trackSnapshot) {
                        if (!isActive) break
                        val data = snapshot[id]
                        if (data != null && data.isNotEmpty()) {
                            try {
                                var written = 0
                                while (written < data.size) {
                                    val chunk = minOf(data.size - written, tempBuf.size)
                                    System.arraycopy(data, written, tempBuf, 0, chunk)
                                    track.write(tempBuf, 0, chunk, AudioTrack.WRITE_BLOCKING)
                                    written += chunk
                                }
                            } catch (_: Exception) {}
                        } else {
                            try {
                                track.write(silentBuf, 0,
                                    minOf(silentBuf.size, 1024), AudioTrack.WRITE_BLOCKING)
                            } catch (_: Exception) {}
                        }
                    }
                    if (snapshot.isEmpty()) delay(10)
                }
            }
        }
    }

    private val silentBuf by lazy { FloatArray(4096) }

    private fun createAudioTrack(assignment: DeviceChannelAssignment): AudioTrack? {
        val deviceInfo = findDeviceInfo(assignment.device)
        val chMask = when (assignment.channels.size) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate).setChannelMask(chMask).build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (deviceInfo != null) track.setPreferredDevice(deviceInfo)
            track
        } catch (_: Exception) { null }
    }

    private fun findDeviceInfo(device: AudioDevice): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .find { it.id == device.id }
    }

    fun pushFrames(data: Map<String, FloatArray>) {
        if (!_isRunning.value) return
        synchronized(lock) {
            for ((id, arr) in data) {
                frameData[id] = arr
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        writeJob?.cancel()
        synchronized(lock) {
            for ((_, t) in tracks) { try { t.stop(); t.release() } catch (_: Exception) {} }
            tracks.clear()
            frameData.clear()
        }
        scope?.cancel()
        scope = null
        writeJob = null
    }
}

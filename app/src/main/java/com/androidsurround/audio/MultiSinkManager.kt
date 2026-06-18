package com.androidsurround.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
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

    private val _actualChannels = MutableStateFlow(2)
    val actualChannels: StateFlow<Int> = _actualChannels.asStateFlow()

    private val tracks = mutableMapOf<String, AudioTrack>()
    private var deviceChCount = mutableMapOf<String, Int>()
    private var sampleRate = 48000
    private var bufferSize = 16384
    private var scope: CoroutineScope? = null
    private var writeJob: Job? = null
    private var frameData = mutableMapOf<String, FloatArray>()
    private val lock = Any()

    data class DeviceChannelAssignment(
        val device: AudioDevice,
        val channels: List<ChannelPosition>,
        val actualChannelCount: Int,
    )

    private var assignments = listOf<DeviceChannelAssignment>()

    private val channelMaskMap = mapOf(
        1 to AudioFormat.CHANNEL_OUT_MONO,
        2 to AudioFormat.CHANNEL_OUT_STEREO,
        4 to AudioFormat.CHANNEL_OUT_QUAD,
        6 to AudioFormat.CHANNEL_OUT_5POINT1,
        8 to AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
    )

    fun configure(
        devices: List<AudioDevice>,
        layout: ChannelLayout,
        rate: Int,
    ) {
        sampleRate = rate
        assignments = if (devices.size == 1) {
            val devInfo = findDeviceInfo(devices.first())
            val maxCh = maxSupportedChannels(devInfo)
            val actualCh = minOf(layout.channelCount, maxCh)
            listOf(DeviceChannelAssignment(devices.first(), layout.channels, actualCh))
        } else {
            distributeChannels(devices, layout)
        }
        bufferSize = assignments.maxOf { computeBufferSize(it.actualChannelCount) }
    }

    private fun computeBufferSize(channelCount: Int): Int {
        val chMask = channelMaskMap[channelCount] ?: AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, chMask, AudioFormat.ENCODING_PCM_FLOAT
        )
        return (minBuf * 6).coerceIn(16384, 131072)
    }

    private fun maxSupportedChannels(info: AudioDeviceInfo?): Int {
        if (info == null) return 2
        val masks = info.channelMasks ?: return 2
        return masks.maxOfOrNull { mask ->
            when (mask) {
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> 8
                AudioFormat.CHANNEL_OUT_5POINT1 -> 6
                AudioFormat.CHANNEL_OUT_QUAD -> 4
                AudioFormat.CHANNEL_OUT_STEREO -> 2
                AudioFormat.CHANNEL_OUT_MONO -> 1
                else -> 0
            }
        }?.coerceAtLeast(2) ?: 2
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
            val devInfo = findDeviceInfo(d)
            val maxCh = maxSupportedChannels(devInfo)
            val cnt = if (i == devices.lastIndex) nonLfe.size - idx
                     else (nonLfe.size / devices.size).coerceAtLeast(1)
            val chs = nonLfe.subList(idx, (idx + cnt).coerceAtMost(nonLfe.size))
            val actualCh = minOf(cnt, maxCh)
            result.add(DeviceChannelAssignment(d, chs.take(actualCh), actualCh))
            idx += cnt
        }
        if (hasLfe) {
            val first = result.firstOrNull() ?: return result
            val devInfo = findDeviceInfo(first.device)
            val maxCh = maxSupportedChannels(devInfo)
            if (first.channels.size + 1 <= maxCh) {
                result[0] = first.copy(
                    channels = first.channels + ChannelPosition.LFE,
                    actualChannelCount = first.actualChannelCount + 1,
                )
            }
        }
        return result
    }

    fun start() {
        if (_isRunning.value) return
        stop()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var startedOk = true
        var totalActualCh = 0
        scope?.launch {
            for (a in assignments) {
                val t = createAudioTrack(a)
                if (t == null) { startedOk = false; break }
                val silentBuf = FloatArray(bufferSize / 4)
                t.write(silentBuf, 0, silentBuf.size, AudioTrack.WRITE_BLOCKING)
                t.play()
                synchronized(lock) {
                    tracks[a.device.uniqueId] = t
                    deviceChCount[a.device.uniqueId] = a.actualChannelCount
                }
                totalActualCh += a.actualChannelCount
            }
            if (!startedOk) { stop(); return@launch }
            _actualChannels.value = totalActualCh.coerceAtLeast(2)
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
                    val chSnapshot: Map<String, Int>
                    synchronized(lock) {
                        trackSnapshot = tracks.toMap()
                        chSnapshot = deviceChCount.toMap()
                    }

                    for ((id, track) in trackSnapshot) {
                        if (!isActive) break
                        val chCount = chSnapshot[id] ?: 2
                        val data = snapshot[id]
                        if (data != null && data.isNotEmpty()) {
                            try {
                                val frameSize = chCount
                                var written = 0
                                while (written < data.size) {
                                    val framesLeft = (data.size - written) / frameSize
                                    val framesChunk = minOf(framesLeft, tempBuf.size / frameSize)
                                    val byteChunk = framesChunk * frameSize
                                    System.arraycopy(data, written, tempBuf, 0, byteChunk)
                                    track.write(tempBuf, 0, byteChunk, AudioTrack.WRITE_BLOCKING)
                                    written += byteChunk
                                }
                            } catch (_: Exception) {}
                        } else {
                            try {
                                val len = minOf(1024, silentBuf.size)
                                track.write(silentBuf, 0, len, AudioTrack.WRITE_BLOCKING)
                            } catch (_: Exception) {}
                        }
                    }
                    if (snapshot.isEmpty()) delay(10)
                }
            }
        }
    }

    private val silentBuf by lazy { FloatArray(4096) }

    private fun findDeviceMask(device: AudioDeviceInfo?): Int {
        val chMasks = device?.channelMasks ?: return AudioFormat.CHANNEL_OUT_STEREO
        val preferred = listOf(
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
            AudioFormat.CHANNEL_OUT_5POINT1,
            AudioFormat.CHANNEL_OUT_QUAD,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.CHANNEL_OUT_MONO,
        )
        for (mask in preferred) {
            if (mask in chMasks) return mask
        }
        return AudioFormat.CHANNEL_OUT_STEREO
    }

    private fun createAudioTrack(assignment: DeviceChannelAssignment): AudioTrack? {
        val deviceInfo = findDeviceInfo(assignment.device)
        val chCount = assignment.actualChannelCount
        val chMask = channelMaskMap[chCount] ?: AudioFormat.CHANNEL_OUT_STEREO
        val bufSize = computeBufferSize(chCount)
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate).setChannelMask(chMask).build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (deviceInfo != null) {
                track.setPreferredDevice(deviceInfo)
            }
            Log.d("MultiSink", "AudioTrack created: ch=$chCount, mask=$chMask, buf=$bufSize")
            track
        } catch (e: Exception) {
            Log.e("MultiSink", "Failed to create AudioTrack: ${e.message}")
            null
        }
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
            deviceChCount.clear()
            frameData.clear()
        }
        scope?.cancel()
        scope = null
        writeJob = null
    }
}

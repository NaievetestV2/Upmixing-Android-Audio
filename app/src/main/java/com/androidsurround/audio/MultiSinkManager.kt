package com.androidsurround.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.androidsurround.model.AudioDevice
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

    private val standardMaskMap = mapOf(
        1 to AudioFormat.CHANNEL_OUT_MONO,
        2 to AudioFormat.CHANNEL_OUT_STEREO,
        4 to AudioFormat.CHANNEL_OUT_QUAD,
        6 to AudioFormat.CHANNEL_OUT_5POINT1,
        8 to AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
    )

    private fun channelMaskFor(count: Int): Pair<Int, Int?> {
        val stdMask = standardMaskMap[count]
        if (stdMask != null) return stdMask to null
        val nextStd = standardMaskMap.entries
            .filter { it.key > count }
            .minByOrNull { it.key }
        if (nextStd != null) return nextStd.value to ((1 shl count) - 1)
        return AudioFormat.CHANNEL_OUT_STEREO to null
    }

    fun configure(
        devices: List<AudioDevice>,
        deviceChannels: Map<String, List<ChannelPosition>>,
        rate: Int,
    ) {
        sampleRate = rate
        assignments = devices.map { d ->
            val chs = deviceChannels[d.uniqueId] ?: emptyList()
            DeviceChannelAssignment(d, chs, chs.size)
        }
        bufferSize = assignments.maxOfOrNull { computeBufferSize(it.actualChannelCount) }
            ?: 16384
    }

    private fun computeBufferSize(channelCount: Int): Int {
        val (mask, _) = channelMaskFor(channelCount)
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        return (minBuf * 6).coerceIn(16384, 131072)
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

                    if (snapshot.isEmpty()) { delay(5); continue }

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
                        }
                    }
                }
            }
        }
    }

    private fun createAudioTrack(assignment: DeviceChannelAssignment): AudioTrack? {
        val deviceInfo = findDeviceInfo(assignment.device)
        val chCount = assignment.actualChannelCount
        val (chMask, indexMask) = channelMaskFor(chCount)
        val bufSize = computeBufferSize(chCount)
        return try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate).setChannelMask(chMask).apply {
                            if (indexMask != null) setChannelIndexMask(indexMask)
                        }.build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            val track = builder.build()
            if (deviceInfo != null) {
                val ok = track.setPreferredDevice(deviceInfo)
                                Log.i("MultiSink", "setPreferredDevice(${deviceInfo.productName}): $ok")
            }
                        Log.i("MultiSink", "AudioTrack created: ch=$chCount, mask=$chMask, buf=$bufSize")
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

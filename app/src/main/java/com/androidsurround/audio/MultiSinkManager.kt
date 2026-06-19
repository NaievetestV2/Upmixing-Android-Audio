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
import java.util.concurrent.ConcurrentLinkedQueue

class MultiSinkManager(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _actualChannels = MutableStateFlow(2)
    val actualChannels: StateFlow<Int> = _actualChannels.asStateFlow()

    private var sampleRate = 48000
    private var scope: CoroutineScope? = null
    private var writeJobs = mutableListOf<Job>()

    private data class SinkInfo(
        val track: AudioTrack,
        val queue: ConcurrentLinkedQueue<ByteArray>,
        val assignedCh: Int,
        val paddedCh: Int,
    )
    private val sinks = mutableMapOf<String, SinkInfo>()
    private val sinkLock = Any()

    data class DeviceChannelAssignment(
        val device: AudioDevice,
        val channels: List<ChannelPosition>,
        val actualChannelCount: Int,
    )
    private var assignments = listOf<DeviceChannelAssignment>()

    private fun paddedCount(assigned: Int): Int = when {
        assigned <= 1 -> 1
        assigned <= 2 -> 2
        assigned <= 4 -> 4
        assigned <= 6 -> 6
        else -> 8
    }

    private fun paddedMask(count: Int): Int = when {
        count <= 1 -> AudioFormat.CHANNEL_OUT_MONO
        count <= 2 -> AudioFormat.CHANNEL_OUT_STEREO
        count <= 4 -> AudioFormat.CHANNEL_OUT_QUAD
        count <= 6 -> AudioFormat.CHANNEL_OUT_5POINT1
        else -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    }

    private fun bufferSizeBytes(chCount: Int): Int {
        val mask = paddedMask(chCount)
        val minBuf = try {
            AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        } catch (e: Exception) {
            Log.w("MultiSink", "getMinBufferSize failed: ${e.message}, using 16384")
            4096
        }
        return (minBuf * 6).coerceIn(16384, 131072)
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
    }

    fun start() {
        if (_isRunning.value) return
        stop()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var startedOk = true
        var totalCh = 0
        scope?.launch {
            for (a in assignments) {
                val ch = a.actualChannelCount
                val pch = paddedCount(ch)
                val mask = paddedMask(ch)
                val bufSize = bufferSizeBytes(ch)
                val deviceInfo = findDeviceInfo(a.device)
                val track = try {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(mask).build())
                        .setBufferSizeInBytes(bufSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } catch (e: Exception) {
                    Log.e("MultiSink", "Failed to create AudioTrack: ${e.message}")
                    null
                }
                if (track == null) { startedOk = false; break }

                if (deviceInfo != null) {
                    val ok = track.setPreferredDevice(deviceInfo)
                    Log.i("MultiSink", "setPreferredDevice(${deviceInfo.productName}): $ok")
                }
                Log.i("MultiSink", "AudioTrack: id=${a.device.uniqueId} ch=$ch/$pch mask=$mask buf=$bufSize")

                val silent = ByteArray(bufSize)
                var silOff = 0
                while (silOff < silent.size) {
                    val ret = track.write(silent, silOff, silent.size - silOff, AudioTrack.WRITE_BLOCKING)
                    if (ret <= 0) { Log.e("MultiSink", "Silence write error: $ret"); break }
                    silOff += ret
                }
                track.play()

                synchronized(sinkLock) {
                    sinks[a.device.uniqueId] = SinkInfo(track, ConcurrentLinkedQueue(), ch, pch)
                }
                totalCh += ch
            }
            if (!startedOk) { stop(); return@launch }
            _actualChannels.value = totalCh.coerceAtLeast(2)
            _isRunning.value = true

            val snap: Map<String, SinkInfo>
            synchronized(sinkLock) { snap = sinks.toMap() }
            for ((id, si) in snap) {
                val wJob = launch {
                    val tempBuf = ByteArray(32768)
                    while (isActive) {
                        if (!_isRunning.value) break
                        val data = si.queue.poll()
                        if (data == null) { delay(5); continue }
                        var offset = 0
                        while (offset < data.size) {
                            val chunk = minOf(data.size - offset, tempBuf.size)
                            System.arraycopy(data, offset, tempBuf, 0, chunk)
                            val ret = si.track.write(tempBuf, 0, chunk, AudioTrack.WRITE_BLOCKING)
                            if (ret <= 0) {
                                Log.e("MultiSink", "Write error $ret for $id")
                                break
                            }
                            offset += ret
                        }
                    }
                }
                writeJobs.add(wJob)
            }
        }
    }

    fun pushFrames(data: Map<String, FloatArray>) {
        if (!_isRunning.value) return
        val snap: Map<String, SinkInfo>
        synchronized(sinkLock) { snap = sinks.toMap() }
        for ((id, arr) in data) {
            val si = snap[id] ?: continue
            val frames = arr.size / si.assignedCh
            val bytes = ByteArray(frames * si.paddedCh * 2)
            var inPos = 0
            var outPos = 0
            for (f in 0 until frames) {
                for (c in 0 until si.assignedCh) {
                    val s = (arr[inPos++].coerceIn(-1f, 1f) * 32767f).toInt()
                        .coerceIn(-32768, 32767).toShort()
                    bytes[outPos++] = (s.toInt() and 0xFF).toByte()
                    bytes[outPos++] = ((s.toInt() shr 8) and 0xFF).toByte()
                }
                outPos += (si.paddedCh - si.assignedCh) * 2
            }
            si.queue.add(bytes)
        }
    }

    private fun findDeviceInfo(device: AudioDevice): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .find { it.id == device.id }
    }

    fun stop() {
        _isRunning.value = false
        writeJobs.forEach { it.cancel() }
        writeJobs.clear()
        synchronized(sinkLock) {
            for ((_, si) in sinks) {
                try { si.track.stop(); si.track.release() } catch (_: Exception) {}
                si.queue.clear()
            }
            sinks.clear()
        }
        scope?.cancel()
        scope = null
    }
}

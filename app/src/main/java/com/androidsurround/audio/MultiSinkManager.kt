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

    private var sampleRate = 48000
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e("MultiSink", "Uncaught coroutine exception", e)
    }
    private var scope: CoroutineScope? = null
    private var writeJobs = mutableListOf<Job>()

    private data class SinkInfo(
        val track: AudioTrack,
        val queue: ConcurrentLinkedQueue<ByteArray>,
        val effectiveCh: Int,
        val paddedCh: Int,
    )
    private val sinks = mutableMapOf<String, SinkInfo>()
    private val sinkLock = Any()

    data class DeviceChannelAssignment(
        val device: AudioDevice,
        val channels: List<ChannelPosition>,
        val assignedCh: Int,
        val effectiveCh: Int,
    )
    private var assignments = listOf<DeviceChannelAssignment>()

    private fun paddedCount(ch: Int): Int = when {
        ch <= 1 -> 1
        ch <= 2 -> 2
        ch <= 4 -> 4
        ch <= 6 -> 6
        else -> 8
    }

    private fun paddedMask(ch: Int): Int = when {
        ch <= 1 -> AudioFormat.CHANNEL_OUT_MONO
        ch <= 2 -> AudioFormat.CHANNEL_OUT_STEREO
        ch <= 4 -> AudioFormat.CHANNEL_OUT_QUAD
        ch <= 6 -> AudioFormat.CHANNEL_OUT_5POINT1
        else -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    }

    private fun deviceMaxChannels(device: AudioDevice): Int {
        if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
            return 2
        }
        val max = device.channelCounts.maxOrNull()
        if (max != null) return max
        return 2
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
            val assigned = chs.size
            val maxDev = deviceMaxChannels(d)
            val effective = minOf(assigned, maxDev).coerceAtLeast(1)
            Log.i("MultiSink", "configure ${d.uniqueId}: assigned=$assigned maxDev=$maxDev effective=$effective")
            DeviceChannelAssignment(d, chs, assigned, effective)
        }
    }

    fun start() {
        if (_isRunning.value) return
        stop()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
        var startedOk = true
        scope?.launch {
            for (a in assignments) {
                val eff = a.effectiveCh
                val pch = paddedCount(eff)
                val mask = paddedMask(eff)
                val bufSize = bufferSizeBytes(eff)
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
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
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
                Log.i("MultiSink", "AudioTrack: id=${a.device.uniqueId} eff=$eff/$pch mask=$mask buf=$bufSize")

                track.play()

                val fillSize = (bufSize / 4).coerceAtMost(16384)
                val silent = ByteArray(fillSize)
                var off = 0
                while (off < fillSize) {
                    val ret = track.write(silent, off, fillSize - off, AudioTrack.WRITE_BLOCKING)
                    if (ret <= 0) break
                    off += ret
                }

                synchronized(sinkLock) {
                    sinks[a.device.uniqueId] = SinkInfo(track, ConcurrentLinkedQueue(), eff, pch)
                }
            }
            if (!startedOk) { stop(); return@launch }
            _isRunning.value = true

            val snap: Map<String, SinkInfo>
            synchronized(sinkLock) { snap = sinks.toMap() }
            for ((id, si) in snap) {
                val wJob = launch {
                    val tempBuf = ByteArray(32768)
                    while (isActive) {
                        if (!_isRunning.value) break
                        try {
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
                        } catch (e: Exception) {
                            Log.e("MultiSink", "Write exception for $id: ${e.message}")
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
            val a = assignments.find { it.device.uniqueId == id }
            val assignedCh = a?.assignedCh ?: si.effectiveCh
            val frames = arr.size / assignedCh
            if (frames == 0) continue
            val bytes = ByteArray(frames * si.paddedCh * 2)
            var inPos = 0
            var outPos = 0
            for (f in 0 until frames) {
                for (c in 0 until si.effectiveCh) {
                    val s = (arr[inPos++].coerceIn(-1f, 1f) * 32767f).toInt()
                        .coerceIn(-32768, 32767).toShort()
                    bytes[outPos++] = (s.toInt() and 0xFF).toByte()
                    bytes[outPos++] = ((s.toInt() shr 8) and 0xFF).toByte()
                }
                inPos += (assignedCh - si.effectiveCh)
                outPos += (si.paddedCh - si.effectiveCh) * 2
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

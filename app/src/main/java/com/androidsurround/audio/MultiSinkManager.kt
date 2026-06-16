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
    private var bufferSize = 4096
    private var scope: CoroutineScope? = null

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
        bufferSize = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

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

        val nonLfeChannels = layout.channels.filter { it != ChannelPosition.LFE }
        val hasLfe = ChannelPosition.LFE in layout.channels

        val assignments = mutableListOf<DeviceChannelAssignment>()
        var chIdx = 0

        for ((i, device) in devices.withIndex()) {
            val chCount = if (i == devices.lastIndex) {
                nonLfeChannels.size - chIdx
            } else {
                (nonLfeChannels.size / devices.size)
                    .coerceAtLeast(1)
                    .coerceAtMost(2)
            }
            val deviceChs = nonLfeChannels.subList(
                chIdx, (chIdx + chCount).coerceAtMost(nonLfeChannels.size)
            )
            assignments.add(DeviceChannelAssignment(device, deviceChs))
            chIdx += chCount
        }

        if (hasLfe) {
            assignments.firstOrNull()?.let {
                assignments[assignments.indexOf(it)] = it.copy(
                    channels = it.channels + ChannelPosition.LFE
                )
            }
        }

        return assignments
    }

    fun start() {
        if (_isRunning.value) return
        stop()

        val deferred = CompletableDeferred<Unit>()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope?.launch {
            for (assignment in assignments) {
                val track = createAudioTrack(assignment)
                if (track != null) {
                    track.play()
                    tracks[assignment.device.uniqueId] = track
                }
            }
            _isRunning.value = true
            deferred.complete(Unit)
        }
        runBlocking { deferred.await() }
    }

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

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(chMask)
            .build()

        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (deviceInfo != null) {
                track.setPreferredDevice(deviceInfo)
            }
            track
        } catch (e: Exception) {
            null
        }
    }

    private fun findDeviceInfo(device: AudioDevice): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .find { it.id == device.id }
    }

    fun writeFrames(frameData: Map<String, FloatArray>) {
        if (!_isRunning.value) return
        for ((deviceId, track) in tracks) {
            val data = frameData[deviceId] ?: continue
            try {
                track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        _isRunning.value = false
        for ((_, track) in tracks) {
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }
        tracks.clear()
        scope?.cancel()
        scope = null
    }
}

package com.androidsurround.audio

import android.content.Context
import androidx.media3.common.C
import com.androidsurround.model.AudioDevice
import com.androidsurround.model.ChannelLayout
import com.androidsurround.model.ChannelPosition
import com.androidsurround.model.UpmixConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioEngine(private val context: Context) {

    private val upmixProcessor = UpmixProcessor()

    init {
        NativeEngine.load()
    }
    private val multiSink = MultiSinkManager(context)

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _currentLayout = MutableStateFlow(ChannelLayout.SURROUND_7_1)
    val currentLayout: StateFlow<ChannelLayout> = _currentLayout.asStateFlow()

    private val _upmixConfig = MutableStateFlow(UpmixConfig())
    val upmixConfig: StateFlow<UpmixConfig> = _upmixConfig.asStateFlow()

    private val _selectedDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    val selectedDevices: StateFlow<List<AudioDevice>> = _selectedDevices.asStateFlow()

    private val _sampleRate = MutableStateFlow(48000)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    private var scope: CoroutineScope? = null

    fun setLayout(layout: ChannelLayout) {
        _currentLayout.value = layout
        reconfigure()
    }

    fun setUpmixConfig(config: UpmixConfig) {
        _upmixConfig.value = config
        reconfigure()
    }

    fun setDevices(devices: List<AudioDevice>) {
        _selectedDevices.value = devices
        reconfigure()
    }

    private fun reconfigure() {
        val devs = _selectedDevices.value
        val layout = _currentLayout.value
        val config = _upmixConfig.value
        val rate = _sampleRate.value

        upmixProcessor.configure(config, layout, rate)
        multiSink.configure(devs, layout, rate)
    }

    fun startPipeline() {
        if (_isActive.value) return
        stopPipeline()

        val devs = _selectedDevices.value
        if (devs.isEmpty()) return

        reconfigure()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        _isActive.value = true
    }

    fun processAudioFrame(
        input: FloatArray,
        inputChannels: Int,
    ): Map<String, FloatArray> {
        val devices = _selectedDevices.value
        val layout = _currentLayout.value
        val config = _upmixConfig.value

        if (devices.isEmpty()) return emptyMap()

        val upmixed = upmixProcessor.process(input, inputChannels, layout, config)
        val frameSize = layout.channelCount

        return if (devices.size == 1) {
            mapOf(devices.first().uniqueId to upmixed)
        } else {
            distributeToDevices(upmixed, frameSize, devices, layout)
        }
    }

    private fun distributeToDevices(
        upmixed: FloatArray,
        frameSize: Int,
        devices: List<AudioDevice>,
        layout: ChannelLayout,
    ): Map<String, FloatArray> {
        val totalFrames = upmixed.size / frameSize
        val result = mutableMapOf<String, FloatArray>()

        val nonLfeIndices = layout.channels
            .mapIndexedNotNull { idx, pos -> if (pos != ChannelPosition.LFE) idx else null }

        if (devices.size <= 1) {
            result[devices.first().uniqueId] = upmixed
            return result
        }

        val chsPerDevice = nonLfeIndices.size / devices.size
        var chOffset = 0

        for ((i, device) in devices.withIndex()) {
            val isLast = i == devices.lastIndex
            val end = if (isLast) nonLfeIndices.size else (chOffset + chsPerDevice).coerceAtMost(nonLfeIndices.size)
            val devChIndices = nonLfeIndices.subList(chOffset, end).toMutableList()

            if (i == 0 && ChannelPosition.LFE in layout.channels) {
                val lfeIdx = layout.channels.indexOf(ChannelPosition.LFE)
                devChIndices.add(lfeIdx)
            }

            val devOutput = FloatArray(totalFrames * devChIndices.size)
            for (f in 0 until totalFrames) {
                for ((dc, chIdx) in devChIndices.withIndex()) {
                    devOutput[f * devChIndices.size + dc] = upmixed[f * frameSize + chIdx]
                }
            }
            result[device.uniqueId] = devOutput
            chOffset = end
        }

        return result
    }

    fun pushFrames(frameData: Map<String, FloatArray>) {
        multiSink.pushFrames(frameData)
    }

    fun onPcmData(buffer: ByteBuffer, channelCount: Int, sampleRate: Int, encoding: Int) {
        if (!_isActive.value) return
        val devs = _selectedDevices.value
        if (devs.isEmpty()) return

        val floats = pcmToFloat(buffer, encoding)
        if (floats.isEmpty()) return

        val frameData = processAudioFrame(floats, channelCount)
        multiSink.pushFrames(frameData)
    }

    private fun pcmToFloat(buffer: ByteBuffer, encoding: Int): FloatArray {
        val pos = buffer.position()
        val limit = buffer.limit()
        val remaining = limit - pos
        if (remaining <= 0) return FloatArray(0)

        return when (encoding) {
            C.ENCODING_PCM_FLOAT -> {
                val fb = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val arr = FloatArray(fb.remaining())
                fb.get(arr)
                arr
            }
            C.ENCODING_PCM_16BIT -> {
                val total = remaining / 2
                val arr = FloatArray(total)
                val buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until total) {
                    arr[i] = buf.getShort(pos + i * 2).toFloat() / 32768f
                }
                arr
            }
            C.ENCODING_PCM_32BIT -> {
                val total = remaining / 4
                val arr = FloatArray(total)
                val buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until total) {
                    arr[i] = buf.getInt(pos + i * 4).toFloat() / 2147483648f
                }
                arr
            }
            C.ENCODING_PCM_8BIT -> {
                val total = remaining
                val arr = FloatArray(total)
                for (i in 0 until total) {
                    arr[i] = (buffer.get(pos + i).toInt() and 0xFF - 128) / 128f
                }
                arr
            }
            else -> FloatArray(0)
        }
    }

    fun stopPipeline() {
        _isActive.value = false
        multiSink.stop()
        scope?.cancel()
        scope = null
    }

    fun release() {
        stopPipeline()
    }
}

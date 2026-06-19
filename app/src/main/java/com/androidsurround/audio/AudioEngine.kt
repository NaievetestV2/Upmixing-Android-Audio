package com.androidsurround.audio

import android.content.Context
import android.util.Log
import com.androidsurround.model.AudioDevice
import com.androidsurround.model.ChannelLayout
import com.androidsurround.model.ChannelPosition
import com.androidsurround.model.UpmixConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        if (devs.isEmpty()) {
            Log.i("AudioEngine", "startPipeline: no devices selected")
            return
        }

        reconfigure()
        multiSink.start()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        _isActive.value = true
        Log.i("AudioEngine", "Pipeline started with ${devs.size} device(s), layout=${_currentLayout.value}")
    }

    fun processAudioFrame(
        input: FloatArray,
        inputChannels: Int,
    ): Map<String, FloatArray> {
        val devices = _selectedDevices.value
        val layout = _currentLayout.value
        val config = _upmixConfig.value

        if (devices.isEmpty()) {
            Log.w("AudioEngine", "processAudioFrame: no devices")
            return emptyMap()
        }

        val upmixed = upmixProcessor.process(input, inputChannels, layout, config)
        val upmixFrameSize = layout.channelCount
        val totalFrames = upmixed.size / upmixFrameSize
        Log.i("AudioEngine", "processAudioFrame: in=${input.size}/${inputChannels}ch upmixed=${upmixed.size} frames=$totalFrames devices=${devices.size}")

        return if (devices.size == 1) {
            val actualCh = multiSink.actualChannels.value
            if (actualCh >= upmixFrameSize) {
                mapOf(devices.first().uniqueId to upmixed)
            } else {
                val trimmed = FloatArray(totalFrames * actualCh)
                for (f in 0 until totalFrames) {
                    for (c in 0 until actualCh) {
                        trimmed[f * actualCh + c] = upmixed[f * upmixFrameSize + c]
                    }
                }
                mapOf(devices.first().uniqueId to trimmed)
            }
        } else {
            distributeToDevices(upmixed, upmixFrameSize, devices, layout)
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

    fun stopPipeline() {
        Log.i("AudioEngine", "Stopping pipeline")
        _isActive.value = false
        multiSink.stop()
        scope?.cancel()
        scope = null
    }

    fun release() {
        stopPipeline()
    }
}

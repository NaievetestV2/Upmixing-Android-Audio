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

    private val _deviceChannelMappings = MutableStateFlow<Map<String, List<ChannelPosition>>>(emptyMap())
    val deviceChannelMappings: StateFlow<Map<String, List<ChannelPosition>>> = _deviceChannelMappings.asStateFlow()

    private val _sampleRate = MutableStateFlow(48000)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    private var scope: CoroutineScope? = null

    fun setLayout(layout: ChannelLayout) {
        _currentLayout.value = layout
        rebuildDefaultMappings()
        reconfigure()
    }

    fun setUpmixConfig(config: UpmixConfig) {
        _upmixConfig.value = config
        reconfigure()
    }

    fun setDevices(devices: List<AudioDevice>) {
        _selectedDevices.value = devices
        rebuildDefaultMappings()
        reconfigure()
    }

    fun setDeviceChannelMapping(deviceId: String, channels: List<ChannelPosition>) {
        val updated = _deviceChannelMappings.value.toMutableMap()
        updated[deviceId] = channels
        _deviceChannelMappings.value = updated
        reconfigure()
    }

    private fun rebuildDefaultMappings() {
        val devs = _selectedDevices.value
        val layout = _currentLayout.value
        if (devs.isEmpty()) {
            _deviceChannelMappings.value = emptyMap()
            return
        }
        val validPositions = layout.channels.toSet()
        val mapping = _deviceChannelMappings.value
            .filterKeys { id -> devs.any { it.uniqueId == id } }
            .mapValues { (_, chs) -> chs.filter { it in validPositions } }
            .toMutableMap()
        for (d in devs) {
            if (d.uniqueId !in mapping) {
                mapping[d.uniqueId] = emptyList()
            }
        }
        val assigned = mapping.values.flatten().toSet()
        if (assigned.size < layout.channels.size && devs.size == 1) {
            mapping[devs.first().uniqueId] = layout.channels
        }
        _deviceChannelMappings.value = mapping
    }

    private fun reconfigure() {
        val devs = _selectedDevices.value
        val layout = _currentLayout.value
        val config = _upmixConfig.value
        val rate = _sampleRate.value
        val mappings = _deviceChannelMappings.value

        upmixProcessor.configure(config, layout, rate)
        multiSink.configure(devs, mappings, rate)
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
        val mappings = _deviceChannelMappings.value

        if (devices.isEmpty()) {
            Log.w("AudioEngine", "processAudioFrame: no devices")
            return emptyMap()
        }

        val upmixed = upmixProcessor.process(input, inputChannels, layout, config)
        val upmixFrameSize = layout.channelCount
        val totalFrames = upmixed.size / upmixFrameSize
        Log.i("AudioEngine", "processAudioFrame: in=${input.size}/${inputChannels}ch upmixed=${upmixed.size} frames=$totalFrames devices=${devices.size}")

        val result = mutableMapOf<String, FloatArray>()
        for (d in devices) {
            val chs = mappings[d.uniqueId] ?: emptyList()
            if (chs.isEmpty()) continue
            val devChIndices = chs.mapNotNull { pos ->
                val idx = layout.channels.indexOf(pos)
                if (idx >= 0) idx else null
            }
            if (devChIndices.isEmpty()) continue
            val devOut = FloatArray(totalFrames * devChIndices.size)
            for (f in 0 until totalFrames) {
                for ((dc, chIdx) in devChIndices.withIndex()) {
                    devOut[f * devChIndices.size + dc] = upmixed[f * upmixFrameSize + chIdx]
                }
            }
            result[d.uniqueId] = devOut
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

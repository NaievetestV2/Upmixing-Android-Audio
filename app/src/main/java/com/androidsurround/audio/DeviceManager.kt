package com.androidsurround.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.androidsurround.model.AudioDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceManager(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _availableSinks = MutableStateFlow<List<AudioDevice>>(emptyList())
    val availableSinks: StateFlow<List<AudioDevice>> = _availableSinks.asStateFlow()

    private val _selectedDevices = MutableStateFlow<Map<String, AudioDevice>>(emptyMap())
    val selectedDevices: StateFlow<Map<String, AudioDevice>> = _selectedDevices.asStateFlow()

    fun refreshDevices() {
        val allDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val devices = allDevices
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            .map { AudioDevice.fromDeviceInfo(it) }
            .sortedByDescending { it.isUsb }
        _availableSinks.value = devices
    }

    fun selectDevice(device: AudioDevice): Boolean {
        val current = _selectedDevices.value.toMutableMap()
        if (current.size >= 8) return false
        current[device.uniqueId] = device
        _selectedDevices.value = current
        return true
    }

    fun deselectDevice(device: AudioDevice) {
        val current = _selectedDevices.value.toMutableMap()
        current.remove(device.uniqueId)
        _selectedDevices.value = current
    }

    fun clearSelection() {
        _selectedDevices.value = emptyMap()
    }

    fun isSelected(device: AudioDevice): Boolean =
        device.uniqueId in _selectedDevices.value
}

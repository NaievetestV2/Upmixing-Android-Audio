package com.androidsurround.model

import android.media.AudioDeviceInfo

data class AudioDevice(
    val id: Int,
    val productName: String,
    val address: String,
    val isUsb: Boolean,
    val channelCounts: List<Int> = emptyList(),
    val sampleRates: List<Int> = emptyList(),
    val type: Int = AudioDeviceInfo.TYPE_UNKNOWN,
    val isSource: Boolean = false,
    val isSink: Boolean = false,
) {
    val displayName: String
        get() = buildString {
            append(productName.ifEmpty { "Unknown Device" })
            if (isUsb) append(" (USB)")
            if (channelCounts.isNotEmpty()) {
                append(" [${channelCounts.max()}ch]")
            }
        }

    val uniqueId: String get() = "$id:$address"

    companion object {
        fun fromDeviceInfo(info: AudioDeviceInfo): AudioDevice {
            val name = info.productName?.toString().orEmpty()
            return AudioDevice(
                id = info.id,
                productName = name,
                address = info.address.orEmpty(),
                isUsb = info.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    info.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    info.type == AudioDeviceInfo.TYPE_DOCK,
                channelCounts = info.channelCounts?.toList().orEmpty(),
                sampleRates = info.sampleRates?.toList().orEmpty(),
                type = info.type,
                isSource = info.isSource,
                isSink = info.isSink,
            )
        }
    }
}

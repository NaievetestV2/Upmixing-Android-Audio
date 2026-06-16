package com.androidsurround.audio

object NativeEngine {
    private var loaded = false

    fun isAvailable(): Boolean = loaded

    fun load() {
        if (!loaded) {
            try {
                System.loadLibrary("androidsurround_native")
                loaded = true
            } catch (_: UnsatisfiedLinkError) {
                loaded = false
            }
        }
    }

    external fun openStream(
        deviceId: Int, sampleRate: Int, channelCount: Int
    ): Boolean

    external fun startStream(index: Int): Boolean
    external fun writeFrames(index: Int, data: FloatArray): Int
    external fun closeAllStreams()
    external fun processUpmix(
        input: FloatArray,
        inputChannels: Int,
        outputChannels: Int,
        mixLfe: Boolean,
        lfeCutoff: Float,
        fcCutoff: Float,
        rearDelayMs: Float,
        sampleRate: Int,
        method: Int,
    ): FloatArray

    external fun resetFilters()
}

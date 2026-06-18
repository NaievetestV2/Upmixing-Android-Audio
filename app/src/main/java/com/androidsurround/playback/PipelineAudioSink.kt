package com.androidsurround.playback

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

typealias PcmCallback = (
    buffer: ByteBuffer,
    channelCount: Int,
    sampleRate: Int,
    pcmEncoding: Int,
) -> Unit

class PipelineAudioSink(context: Context) : AudioSink {

    private val defaultSink = DefaultAudioSink.Builder(context).build()

    @Volatile
    var captureOnly: Boolean = false

    @Volatile
    var onPcmData: PcmCallback? = null

    private var lastFormat: Format? = null

    override fun setListener(listener: AudioSink.Listener) {
        defaultSink.setListener(listener)
    }

    override fun supportsFormat(format: Format): Boolean = true

    override fun getFormatSupport(format: Format): Int = AudioSink.FORMAT_HANDLED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        defaultSink.getCurrentPositionUs(sourceEnded)

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        lastFormat = format
        try { defaultSink.configure(format, specifiedBufferSize, outputChannels) } catch (_: Exception) {}
    }

    override fun play() { defaultSink.play() }
    override fun handleDiscontinuity() { defaultSink.handleDiscontinuity() }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encoderDelay: Int,
    ): Boolean {
        if (!buffer.hasRemaining()) return true

        if (captureOnly) {
            val fmt = lastFormat ?: return false
            val callback = onPcmData
            if (callback != null) {
                try {
                    callback(buffer, fmt.channelCount, fmt.sampleRate, fmt.pcmEncoding)
                } catch (e: Exception) {
                    Log.e("PipelineSink", "PCM callback failed", e)
                }
            }
            buffer.position(buffer.limit())
            return true
        }
        return defaultSink.handleBuffer(buffer, presentationTimeUs, encoderDelay)
    }

    override fun playToEndOfStream() { defaultSink.playToEndOfStream() }
    override fun isEnded(): Boolean = captureOnly || defaultSink.isEnded()
    override fun hasPendingData(): Boolean = defaultSink.hasPendingData()
    override fun flush() { defaultSink.flush() }
    override fun reset() { defaultSink.reset() }
    override fun release() { defaultSink.release() }
}

package com.androidsurround.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

typealias PcmCallback = (
    buffer: ByteBuffer,
    channelCount: Int,
    sampleRate: Int,
    encoding: Int,
) -> Unit

class PipelineAudioSink(
    private val onPcmData: PcmCallback,
) : AudioSink {

    private var audioFormat: Format? = null
    private var currentPositionUs: Long = 0
    private var playbackSpeed: Float = 1f
    private var playing: Boolean = false
    private var ended: Boolean = false
    private var audioListener: Listener? = null
    private var framesWritten: Long = 0
    private var configuredChannels: Int = 2
    private var configuredSampleRate: Int = 48000

    override fun setListener(listener: Listener) { audioListener = listener }

    override fun supportsFormat(format: Format): Boolean = true

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = currentPositionUs

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        audioFormat = format
        configuredChannels = format.channelCount
        configuredSampleRate = format.sampleRate
        Log.d("PipelineSink", "configure: ch=$configuredChannels sr=$configuredSampleRate enc=${format.encoding} buf=$specifiedBufferSize")
    }

    override fun play() { playing = true }

    override fun handleDiscontinuity() {}

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encoderDelay: Int,
        encoderPadding: Int,
    ): Boolean {
        if (!buffer.hasRemaining()) return true
        val fmt = audioFormat ?: return false

        val savedLimit = buffer.limit()
        val savedPos = buffer.position()
        val remaining = savedLimit - savedPos

        try {
            onPcmData(buffer, fmt.channelCount, fmt.sampleRate, fmt.encoding)
        } catch (e: Exception) {
            Log.e("PipelineSink", "PCM callback failed", e)
        }

        currentPositionUs = presentationTimeUs
        framesWritten += remaining / (fmt.channelCount * bytesPerSample(fmt.encoding))
        buffer.position(savedLimit)
        return true
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        Format.NO_VALUE -> 2
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT -> 4
        C.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    override fun playToEndOfStream() {
        ended = true
        playing = false
    }

    override fun isEnded(): Boolean = ended

    override fun hasPendingData(): Boolean = false

    override fun setPlaybackSpeed(speed: Float) { playbackSpeed = speed }

    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setSkipSilenceEnabled(enabled: Boolean) {}
    override fun flush() { ended = false; framesWritten = 0 }
    override fun reset() { audioFormat = null; ended = false; playing = false; framesWritten = 0 }
    override fun release() {}
}

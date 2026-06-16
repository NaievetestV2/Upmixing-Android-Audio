package com.androidsurround.audio

import com.androidsurround.model.ChannelLayout
import com.androidsurround.model.ChannelPosition
import com.androidsurround.model.UpmixConfig
import com.androidsurround.model.UpmixMethod

class UpmixProcessor {

    private var lfeFilter: IIRFilter = IIRFilter()
    private var fcFilter: IIRFilter = IIRFilter()
    private var rearDelayBuffer: DelayLine = DelayLine()
    private var sampleRate: Int = 48000

    fun configure(config: UpmixConfig, layout: ChannelLayout, rate: Int) {
        sampleRate = rate
        if (config.enabled) {
            lfeFilter.configureLowPass(config.lfeCutoffHz, rate)
            fcFilter.configureLowPass(config.fcCutoffHz, rate)
            rearDelayBuffer.configure((config.rearDelayMs * rate / 1000).toInt(), layout.channels.size)
        }
    }

    fun process(
        input: FloatArray,
        inputChannels: Int,
        outputLayout: ChannelLayout,
        config: UpmixConfig,
    ): FloatArray {
        if (!config.enabled || inputChannels == outputLayout.channelCount) {
            return input.copyOf()
        }

        val outCh = outputLayout.channelCount
        val frameCount = input.size / inputChannels
        val output = FloatArray(frameCount * outCh)
        val inputIsStereo = inputChannels == 2

        for (frame in 0 until frameCount) {
            val inIdx = frame * inputChannels
            val outIdx = frame * outCh

            val l = input[inIdx]
            val r = if (inputChannels > 1) input[inIdx + 1] else l
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f

            for (ch in outputLayout.channels.indices) {
                val pos = outputLayout.channels[ch]
                output[outIdx + ch] = when (config.method) {
                    UpmixMethod.SIMPLE -> simpleUpmix(l, r, mid, side, pos, config)
                    UpmixMethod.PSD -> psdUpmix(l, r, mid, side, frame, pos, config)
                    UpmixMethod.DOLBY -> dolbyUpmix(l, r, mid, side, pos, config)
                }
            }
        }

        return output
    }

    private fun simpleUpmix(
        l: Float, r: Float, mid: Float, side: Float,
        pos: ChannelPosition, config: UpmixConfig,
    ): Float {
        return when (pos) {
            ChannelPosition.FL -> l
            ChannelPosition.FR -> r
            ChannelPosition.FC -> mid
            ChannelPosition.LFE -> if (config.mixLfe) lfeFilter.process(mid) else 0f
            ChannelPosition.SL -> side * 0.7f
            ChannelPosition.SR -> -side * 0.7f
            ChannelPosition.RL -> side * 0.5f
            ChannelPosition.RR -> -side * 0.5f
        }
    }

    private fun psdUpmix(
        l: Float, r: Float, mid: Float, side: Float,
        frame: Int, pos: ChannelPosition, config: UpmixConfig,
    ): Float {
        val allPassGain = 0.6f
        val allPassOut = allPassFilter(mid, frame)
        return when (pos) {
            ChannelPosition.FL -> l * 0.9f + allPassOut * 0.1f
            ChannelPosition.FR -> r * 0.9f + allPassOut * 0.1f
            ChannelPosition.FC -> mid * 0.8f + allPassOut * 0.2f
            ChannelPosition.LFE -> if (config.mixLfe) lfeFilter.process(mid) else 0f
            ChannelPosition.SL -> side * 0.85f + allPassOut * 0.15f
            ChannelPosition.SR -> -side * 0.85f + allPassOut * 0.15f
            ChannelPosition.RL -> side * 0.6f + allPassOut * 0.4f
            ChannelPosition.RR -> -side * 0.6f + allPassOut * 0.4f
        }
    }

    private fun dolbyUpmix(
        l: Float, r: Float, mid: Float, side: Float,
        pos: ChannelPosition, config: UpmixConfig,
    ): Float {
        val steeredMid = mid * 1.1f
        val steeredSide = side * 1.2f
        return when (pos) {
            ChannelPosition.FL -> l
            ChannelPosition.FR -> r
            ChannelPosition.FC -> steeredMid.coerceIn(-1f, 1f)
            ChannelPosition.LFE -> if (config.mixLfe) lfeFilter.process(mid) else 0f
            ChannelPosition.SL -> steeredSide * 0.9f
            ChannelPosition.SR -> -steeredSide * 0.9f
            ChannelPosition.RL -> steeredSide * 0.5f
            ChannelPosition.RR -> -steeredSide * 0.5f
        }
    }

    private var allPassState = 0f
    private fun allPassFilter(input: Float, frame: Int): Float {
        val coeff = 0.7f
        val filtered = -coeff * input + allPassState
        allPassState = input + coeff * filtered
        return filtered
    }

    fun reset() {
        lfeFilter.reset()
        fcFilter.reset()
        rearDelayBuffer.reset()
        allPassState = 0f
    }
}

class IIRFilter {
    private var a1 = 0f
    private var b0 = 0f
    private var b1 = 0f
    private var x1 = 0f
    private var y1 = 0f

    fun configureLowPass(cutoffHz: Int, sampleRate: Int) {
        val fc = cutoffHz.toFloat() / sampleRate
        if (fc <= 0f || fc >= 0.5f) {
            b0 = 1f; b1 = 0f; a1 = 0f; return
        }
        val K = kotlin.math.tan(Math.PI.toFloat() * fc)
        val norm = 1f / (1f + K)
        b0 = K * norm
        b1 = b0
        a1 = (K - 1f) * norm
    }

    fun process(input: Float): Float {
        val output = b0 * input + b1 * x1 - a1 * y1
        x1 = input
        y1 = output
        return output
    }

    fun reset() { x1 = 0f; y1 = 0f }
}

class DelayLine {
    private var buffer = FloatArray(0)
    private var writePos = 0
    private var channels = 2

    fun configure(delaySamples: Int, numChannels: Int) {
        channels = numChannels
        buffer = FloatArray(delaySamples * numChannels)
        writePos = 0
    }

    fun process(frame: FloatArray, offset: Int): FloatArray {
        val delayed = FloatArray(channels)
        val bufSize = buffer.size / channels

        for (ch in 0 until channels) {
            val readPos = (writePos - 1 + bufSize) % bufSize
            delayed[ch] = buffer[readPos * channels + ch]
            buffer[writePos * channels + ch] = frame[offset + ch]
        }
        writePos = (writePos + 1) % bufSize
        return delayed
    }

    fun reset() {
        buffer.fill(0f)
        writePos = 0
    }
}

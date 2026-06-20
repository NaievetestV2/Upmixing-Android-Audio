package com.androidsurround.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.AudioFormat
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmDecoder(private val context: Context) {

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    @Volatile private var running: Boolean = false
    @Volatile private var paused: Boolean = false
    @Volatile private var seekTargetMs: Long = -1L

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    var onPcmData: ((FloatArray, Int, Int) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var outputSurface: Surface? = null

    private var playbackStartRealMs = 0L
    private var totalPauseMs = 0L
    private var pauseStartMs = 0L

    fun isRunning(): Boolean = running
    fun isPaused(): Boolean = paused

    fun start(uri: Uri) {
        stop()
        running = true
        paused = false
        seekTargetMs = -1L
        playbackStartRealMs = 0L
        totalPauseMs = 0L
        Log.i("PcmDecoder", "Starting decode: $uri")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        job = scope?.launch {
            try {
                decode(uri)
            } catch (e: Exception) {
                Log.e("PcmDecoder", "Decode failed", e)
                onError?.invoke(e.message ?: "Decode error")
            }
        }
        scope?.launch {
            while (isActive && running) {
                if (!paused && playbackStartRealMs > 0) {
                    val elapsed = System.currentTimeMillis() - playbackStartRealMs - totalPauseMs
                    val pos = elapsed.coerceAtLeast(0)
                    val dur = _playbackState.value.durationMs
                    _playbackState.value = _playbackState.value.copy(
                        positionMs = pos.coerceAtMost(dur),
                        isPlaying = true,
                    )
                }
                delay(250)
            }
        }
    }

    private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val targetSampleRate = 48000

    private suspend fun decode(uri: Uri) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var audioTrackIdx = -1
        var videoTrackIdx = -1
        var videoCodec: MediaCodec? = null
        var audioFormat: MediaFormat? = null
        var videoFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("audio/") && audioTrackIdx < 0 -> {
                    audioTrackIdx = i
                    audioFormat = fmt
                    Log.i("PcmDecoder", "Audio track: mime=$mime ch=${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} sr=${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)}")
                }
                mime.startsWith("video/") && videoTrackIdx < 0 -> {
                    videoTrackIdx = i
                    videoFormat = fmt
                    Log.i("PcmDecoder", "Video track: mime=$mime w=${fmt.getInteger(MediaFormat.KEY_WIDTH, 0)} h=${fmt.getInteger(MediaFormat.KEY_HEIGHT, 0)}")
                }
            }
        }

        if (audioTrackIdx < 0) { extractor.release(); throw Exception("No audio track found") }

        extractor.selectTrack(audioTrackIdx)
        if (videoTrackIdx >= 0) extractor.selectTrack(videoTrackIdx)

        _playbackState.value = _playbackState.value.copy(
            durationMs = (audioFormat?.getLong(MediaFormat.KEY_DURATION) ?: 0L) / 1000,
            hasVideo = videoTrackIdx >= 0,
        )

        val mime = audioFormat!!.getString(MediaFormat.KEY_MIME)!!
        val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
        val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 48000)
        pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        val audioCodec = MediaCodec.createDecoderByType(mime)
        audioCodec.configure(audioFormat, null, null, 0)
        audioCodec.start()

        if (videoTrackIdx >= 0 && outputSurface != null) {
            videoCodec = MediaCodec.createDecoderByType(videoFormat!!.getString(MediaFormat.KEY_MIME)!!)
            videoCodec.configure(videoFormat, outputSurface, null, 0)
            videoCodec.start()
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var audioInputDone = false
        var audioOutputDone = false
        var videoInputDone = videoTrackIdx < 0
        var videoOutputDone = videoTrackIdx < 0
        var totalDecodedUs = 0L

        while (!audioOutputDone && running) {
            if (paused) { delay(50); continue }
            if (seekTargetMs >= 0) {
                handleSeek(audioCodec, extractor)
                totalDecodedUs = seekTargetMs * 1000
                playbackStartRealMs = System.currentTimeMillis()
                totalPauseMs = 0L
            }

            if (!audioInputDone || !videoInputDone) {
                val inIdx = audioCodec.dequeueInputBuffer(5000)
                if (inIdx >= 0) {
                    val inBuf = audioCodec.getInputBuffer(inIdx) ?: continue
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        audioCodec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioInputDone = true
                        videoInputDone = true
                    } else {
                        val trackIdx = extractor.sampleTrackIndex
                        val pts = extractor.sampleTime
                        if (trackIdx == audioTrackIdx) {
                            audioCodec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                        } else if (trackIdx == videoTrackIdx && videoCodec != null) {
                            audioCodec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                            val vIdx = videoCodec.dequeueInputBuffer(5000)
                            if (vIdx >= 0) {
                                val vBuf = videoCodec.getInputBuffer(vIdx)
                                vBuf?.clear()
                                extractor.readSampleData(vBuf ?: continue, 0)
                                videoCodec.queueInputBuffer(vIdx, 0, sampleSize, pts, 0)
                            }
                        }
                        extractor.advance()
                    }
                }
            }

            val outIdx = audioCodec.dequeueOutputBuffer(bufferInfo, 5000)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFmt = audioCodec.outputFormat
                    if (newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                }
                outIdx >= 0 -> {
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (eos) audioOutputDone = true

                    if (bufferInfo.size > 0) {
                        val outBuf = audioCodec.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val floats = bufToFloat(outBuf, bufferInfo.size)
                            if (floats.isNotEmpty()) {
                                val resampled = if (sampleRate != targetSampleRate)
                                    resample(floats, sampleRate, targetSampleRate, channelCount)
                                else floats
                                onPcmData?.invoke(resampled, channelCount, targetSampleRate)
                            }
                        }
                        totalDecodedUs = bufferInfo.presentationTimeUs.coerceAtLeast(totalDecodedUs)
                        if (playbackStartRealMs == 0L) {
                            playbackStartRealMs = System.currentTimeMillis()
                        }
                        delay(15)
                    }
                    audioCodec.releaseOutputBuffer(outIdx, false)
                }
            }

            if (videoCodec != null) {
                val vBufInfo = MediaCodec.BufferInfo()
                var vOutIdx = videoCodec.dequeueOutputBuffer(vBufInfo, 0)
                while (vOutIdx >= 0) {
                    val eos = vBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (eos) videoOutputDone = true
                    videoCodec.releaseOutputBuffer(vOutIdx, vBufInfo.presentationTimeUs <
                        (totalDecodedUs + 50000))
                    vOutIdx = videoCodec.dequeueOutputBuffer(vBufInfo, 0)
                }
            }
        }

        Log.i("PcmDecoder", "Decode loop ended, draining engine...")
        if (running && !seekTargetMs.let { it >= 0 }) {
            val drainStart = System.currentTimeMillis()
            while (running && (System.currentTimeMillis() - drainStart) < 2000) {
                delay(100)
            }
            _playbackState.value = _playbackState.value.copy(isPlaying = false)
            onEnded?.invoke()
        }

        audioCodec.stop()
        audioCodec.release()
        videoCodec?.stop()
        videoCodec?.release()
        extractor.release()
    }

    private fun handleSeek(codec: MediaCodec, extractor: MediaExtractor) {
        val targetUs = seekTargetMs * 1000
        seekTargetMs = -1L
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        Log.i("PcmDecoder", "Seek done to ${targetUs / 1000}ms")
    }

    fun seekTo(positionMs: Long) {
        if (!running) return
        seekTargetMs = positionMs
    }

    fun pauseDecoder() {
        paused = true
        pauseStartMs = System.currentTimeMillis()
        _playbackState.value = _playbackState.value.copy(isPlaying = false)
    }

    fun resumeDecoder() {
        paused = false
        totalPauseMs += System.currentTimeMillis() - pauseStartMs
        _playbackState.value = _playbackState.value.copy(isPlaying = true)
    }

    private fun bufToFloat(buf: ByteBuffer, size: Int): FloatArray {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val sampleCount = size / bytesPerSample
        val arr = FloatArray(sampleCount)

        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                fb.get(arr)
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                for (i in 0 until sampleCount) {
                    val raw = buf.getInt(buf.position() + i * 4)
                    arr[i] = raw.toFloat() / 2147483648f
                }
            }
            AudioFormat.ENCODING_PCM_16BIT -> {
                val sbuf = buf.asShortBuffer()
                val shorts = ShortArray(sbuf.remaining())
                sbuf.get(shorts)
                for (i in 0 until sampleCount) {
                    arr[i] = shorts[i].toFloat() / 32768f
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                for (i in 0 until sampleCount) {
                    val raw = buf.get(buf.position() + i).toInt() and 0xFF
                    arr[i] = (raw - 128) / 128f
                }
            }
            else -> {
                val sbuf = buf.asShortBuffer()
                val shorts = ShortArray(sbuf.remaining())
                sbuf.get(shorts)
                for (i in 0 until sampleCount) {
                    arr[i] = shorts[i].toFloat() / 32768f
                }
            }
        }
        return arr
    }

    private fun resample(input: FloatArray, inRate: Int, outRate: Int, ch: Int): FloatArray {
        val inFrames = input.size / ch
        val outFrames = (inFrames * outRate / inRate).coerceAtLeast(1)
        val ratio = inRate.toDouble() / outRate.toDouble()
        val out = FloatArray(outFrames * ch)
        for (f in 0 until outFrames) {
            val srcPos = f * ratio
            val srcIdx = srcPos.toInt().coerceAtMost(inFrames - 2)
            val frac = (srcPos - srcIdx).toFloat()
            for (c in 0 until ch) {
                val s0 = input[srcIdx * ch + c]
                val s1 = if (srcIdx + 1 < inFrames) input[(srcIdx + 1) * ch + c] else s0
                out[f * ch + c] = s0 + (s1 - s0) * frac
            }
        }
        return out
    }

    fun stop() {
        running = false
        paused = false
        job?.cancel()
        scope?.cancel()
        scope = null
        job = null
        _playbackState.value = PlaybackState()
    }
}

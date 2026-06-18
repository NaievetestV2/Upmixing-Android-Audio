package com.androidsurround.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmDecoder(private val context: Context) {

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    @Volatile private var running: Boolean = false

    var onPcmData: ((FloatArray, Int, Int) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun start(uri: Uri) {
        stop()
        running = true
        Log.d("PcmDecoder", "Starting decode: $uri")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        job = scope?.launch {
            try {
                decode(uri)
            } catch (e: Exception) {
                Log.e("PcmDecoder", "Decode failed", e)
                onError?.invoke(e.message ?: "Decode error")
            }
        }
    }

    private var pcmEncoding = MediaFormat.ENCODING_PCM_16BIT

    private fun decode(uri: Uri) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var audioTrackIdx = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                audioTrackIdx = i
                inputFormat = fmt
                Log.d("PcmDecoder", "Found audio track: mime=$mime ch=${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} sr=${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)}")
                break
            }
        }
        if (audioTrackIdx < 0) { extractor.release(); throw Exception("No audio track found") }
        extractor.selectTrack(audioTrackIdx)

        val mime = inputFormat!!.getString(MediaFormat.KEY_MIME)!!
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 48000)
        pcmEncoding = MediaFormat.ENCODING_PCM_16BIT

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone && running) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx) ?: continue
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val pts = extractor.sampleTime
                        codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10000)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFmt = codec.outputFormat
                    if (newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING, MediaFormat.ENCODING_PCM_16BIT)
                    }
                    Log.d("PcmDecoder", "Output format: ch=$channelCount sr=$sampleRate enc=$pcmEncoding")
                }
                outIdx >= 0 -> {
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (eos) outputDone = true

                    if (bufferInfo.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val floats = bufToFloat(outBuf, bufferInfo.size)
                            if (floats.isNotEmpty()) {
                                onPcmData?.invoke(floats, channelCount, sampleRate)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        }

        Log.d("PcmDecoder", "Decode loop ended")
        codec.stop()
        codec.release()
        extractor.release()
        onEnded?.invoke()
    }

    private fun bufToFloat(buf: ByteBuffer, size: Int): FloatArray {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (pcmEncoding) {
            MediaFormat.ENCODING_PCM_FLOAT -> 4
            MediaFormat.ENCODING_PCM_32BIT -> 4
            MediaFormat.ENCODING_PCM_16BIT -> 2
            MediaFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val sampleCount = size / bytesPerSample
        val arr = FloatArray(sampleCount)

        when (pcmEncoding) {
            MediaFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                fb.get(arr)
            }
            MediaFormat.ENCODING_PCM_32BIT -> {
                for (i in 0 until sampleCount) {
                    val raw = buf.getInt(buf.position() + i * 4)
                    arr[i] = raw.toFloat() / 2147483648f
                }
            }
            MediaFormat.ENCODING_PCM_16BIT -> {
                val sbuf = buf.asShortBuffer()
                val shorts = ShortArray(sbuf.remaining())
                sbuf.get(shorts)
                for (i in 0 until sampleCount) {
                    arr[i] = shorts[i].toFloat() / 32768f
                }
            }
            MediaFormat.ENCODING_PCM_8BIT -> {
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

    fun stop() {
        running = false
        job?.cancel()
        scope?.cancel()
        scope = null
        job = null
    }
}

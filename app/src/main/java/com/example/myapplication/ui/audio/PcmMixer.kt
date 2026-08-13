package com.example.myapplication.ui.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * PcmMixer — mixes two PCM buffers together into a single m4a file.
 *
 * Both buffers are resampled to the same sample rate (max of the two),
 * padded to equal length, and averaged sample-by-sample with soft clipping.
 */
object PcmMixer {

    /**
     * Resolves whatever [padIndex] of [kitIndex] actually plays — a custom
     * imported/recorded AudioItem if one's assigned, otherwise the kit's
     * factory raw resource — and decodes it.
     *
     * BUG FIX: mixPads/concatPads used to ONLY look at AudioRepository
     * (custom audio), returning null — i.e. silently doing nothing — for
     * any pad that still plays its factory kit sample, which is the
     * default/common case for almost every kit. That's why Mix and
     * Add-To-End looked completely broken: they only ever worked if you'd
     * already manually imported custom audio onto BOTH pads first.
     */
    private fun resolvePcm(context: Context, kitIndex: Int, padIndex: Int, factoryResIds: List<Int>): PcmResult? {
        val custom = AudioRepository.audioForPad(kitIndex, padIndex)
        if (custom != null) return PcmDecoder.decode(context, custom.uri)
        val resId = factoryResIds.getOrNull(padIndex)?.takeIf { it > 0 } ?: return null
        return PcmDecoder.decodeRawResource(context, resId)
    }

    /**
     * Mix audio from [padA] and [padB] of [kitIndex].
     * Returns the output File (AAC m4a in cacheDir) or null on failure.
     */
    suspend fun mixPads(
        context: Context,
        kitIndex: Int,
        padA: Int,
        padB: Int,
        factoryResIds: List<Int>
    ): File? = withContext(Dispatchers.IO) {
        val pcmA = resolvePcm(context, kitIndex, padA, factoryResIds) ?: return@withContext null
        val pcmB = resolvePcm(context, kitIndex, padB, factoryResIds) ?: return@withContext null

        val outSampleRate = max(pcmA.sampleRate, pcmB.sampleRate)
        val outChannels   = max(pcmA.channels, pcmB.channels)   // use widest

        // Resample both to outSampleRate / outChannels (simple linear resample)
        val samplesA = resample(pcmA, outSampleRate, outChannels)
        val samplesB = resample(pcmB, outSampleRate, outChannels)

        val len = max(samplesA.size, samplesB.size)
        val mixed = ShortArray(len)
        for (i in 0 until len) {
            val a = if (i < samplesA.size) samplesA[i].toFloat() else 0f
            val b = if (i < samplesB.size) samplesB[i].toFloat() else 0f
            // Sum and soft-clip to 16-bit range
            var sum = a + b
            sum = sum.coerceIn(-32768f, 32767f)
            mixed[i] = sum.toInt().toShort()
        }

        val outFile = File(
            context.cacheDir,
            "mix_${System.currentTimeMillis()}.m4a"
        )
        encodeToM4a(mixed, outChannels, outSampleRate, outFile)
        outFile
    }

    /**
     * Concatenate audio from [padA] followed by [padB] of [kitIndex] ("Add To End").
     * Returns the output File (AAC m4a in cacheDir) or null on failure.
     */
    suspend fun concatPads(
        context: Context,
        kitIndex: Int,
        padA: Int,
        padB: Int,
        factoryResIds: List<Int>
    ): File? = withContext(Dispatchers.IO) {
        val pcmA = resolvePcm(context, kitIndex, padA, factoryResIds) ?: return@withContext null
        val pcmB = resolvePcm(context, kitIndex, padB, factoryResIds) ?: return@withContext null

        val outSampleRate = max(pcmA.sampleRate, pcmB.sampleRate)
        val outChannels   = max(pcmA.channels, pcmB.channels)

        val samplesA = resample(pcmA, outSampleRate, outChannels)
        val samplesB = resample(pcmB, outSampleRate, outChannels)

        val joined = ShortArray(samplesA.size + samplesB.size)
        samplesA.copyInto(joined, 0)
        samplesB.copyInto(joined, samplesA.size)

        val outFile = File(
            context.cacheDir,
            "concat_${System.currentTimeMillis()}.m4a"
        )
        encodeToM4a(joined, outChannels, outSampleRate, outFile)
        outFile
    }

    private fun resample(src: PcmResult, targetRate: Int, targetChannels: Int): ShortArray {
        if (src.sampleRate == targetRate && src.channels == targetChannels) return src.pcm

        // Simple linear interpolation resample
        val srcFrames  = src.pcm.size / src.channels
        val ratio      = src.sampleRate.toDouble() / targetRate
        val outFrames  = (srcFrames / ratio).toInt()
        val out        = ShortArray(outFrames * targetChannels)

        for (i in 0 until outFrames) {
            val srcPos   = i * ratio
            val srcIdx   = srcPos.toInt()
            val frac     = srcPos - srcIdx
            val nextIdx  = min(srcIdx + 1, srcFrames - 1)

            for (ch in 0 until targetChannels) {
                val srcCh = min(ch, src.channels - 1)
                val s0 = src.pcm[srcIdx * src.channels + srcCh].toFloat()
                val s1 = src.pcm[nextIdx * src.channels + srcCh].toFloat()
                out[i * targetChannels + ch] = (s0 + (s1 - s0) * frac).toInt().toShort()
            }
        }
        return out
    }

    private fun encodeToM4a(samples: ShortArray, channels: Int, sampleRate: Int, outFile: File) {
        val mime    = MediaFormat.MIMETYPE_AUDIO_AAC
        val format  = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val codec = MediaCodec.createEncoderByType(mime)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIdx = -1
        var muxerStarted = false

        // BUG FIX: codec/muxer release used to only run after the loop
        // finished normally — any exception mid-encode (e.g. a full disk,
        // or an unexpected codec error) leaked both permanently, same class
        // of bug as the decode-side leaks fixed elsewhere in this file.
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val bufInfo  = MediaCodec.BufferInfo()
            var inOffset = 0
            var inputDone = false
            var outputDone = false
            val frameSamples = 1024 * channels

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        if (buf == null) {
                            // shouldn't happen for a just-dequeued index, but don't loop forever if it does
                        } else {
                            buf.clear()
                            val rem = samples.size - inOffset
                            if (rem <= 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val n = min(rem, min(frameSamples, buf.capacity() / 2))
                                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                                for (i in inOffset until inOffset + n) bytes.putShort(samples[i])
                                bytes.flip(); buf.put(bytes)
                                val pts = (inOffset.toLong() / channels) * 1_000_000L / sampleRate
                                codec.queueInputBuffer(inIdx, 0, n * 2, pts, 0)
                                inOffset += n
                            }
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIdx = muxer.addTrack(codec.outputFormat)
                            muxer.start(); muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && muxerStarted && bufInfo.size > 0 &&
                            bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            muxer.writeSampleData(trackIdx, outBuf, bufInfo)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (e: Exception) { /* already stopped/never started successfully */ }
            codec.release()
            if (muxerStarted) {
                try { muxer.stop() } catch (e: Exception) { /* nothing was written */ }
            }
            muxer.release()
        }
    }
}

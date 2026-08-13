package com.example.myapplication.ui.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * WaveformGenerator — decodes an audio file and produces a downsampled
 * RMS amplitude array for drawing a natural-looking waveform.
 *
 * Fix: explicit LITTLE_ENDIAN byte order on PCM read (was reading garbage
 * values before), and RMS-based amplitude (was peak-only, looked blocky).
 */
object WaveformGenerator {

    suspend fun extractAmplitudes(
        context: Context,
        uri: Uri,
        barCount: Int = 60
    ): List<Float> = withContext(Dispatchers.IO) {
        try {
            val raw = decodeAmplitudes(context, uri)
            if (raw.isEmpty()) emptyList() else downsample(raw, barCount)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** NEW: decode a raw resource (factory kit WAV) for waveform display */
    suspend fun extractAmplitudesFromRaw(
        context: Context,
        resId: Int,
        barCount: Int = 60
    ): List<Float> = withContext(Dispatchers.IO) {
        try {
            val afd = context.resources.openRawResourceFd(resId) ?: return@withContext emptyList()
            val extractor = MediaExtractor()
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            val raw = decodeAmplitudesFromExtractor(extractor)
            extractor.release()
            if (raw.isEmpty()) emptyList() else downsample(raw, barCount)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Decodes the full audio track into per-chunk RMS amplitude values (0f..1f). */
    private fun decodeAmplitudes(context: Context, uri: Uri): List<Float> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val result = decodeAmplitudesFromExtractor(extractor)
        extractor.release()
        return result
    }

    private fun decodeAmplitudesFromExtractor(extractor: MediaExtractor): List<Float> {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: return emptyList()

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime   = format.getString(MediaFormat.KEY_MIME) ?: ""

        val codec = MediaCodec.createDecoderByType(mime)
        // BUG FIX: codec.release() used to only run after the loop finished
        // normally — any exception decoding a malformed file leaked the
        // MediaCodec instance permanently (Android allows only a handful of
        // concurrent codec instances system-wide, shared with the pad-audio
        // decode path). try/finally guarantees cleanup on every exit.
        try {
        codec.configure(format, null, null, 0)
        codec.start()

        val chunkAmplitudes = mutableListOf<Float>()
        val bufferInfo  = MediaCodec.BufferInfo()
        var inputDone   = false
        var outputDone  = false

        // Each decoded buffer might be large — split it into smaller
        // windows so the waveform has fine detail, not one blob per buffer.
        val samplesPerWindow = 256

        // BUG FIX: a corrupt file that never signals EOS used to spin this
        // loop forever. Safety cap — never hit by legitimate audio.
        var iterations = 0
        val maxIterations = 200_000

        while (!outputDone && iterations < maxIterations) {
            iterations++
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                    val sampleSize  = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    // Walk through 16-bit PCM samples in fixed-size windows,
                    // computing RMS (root-mean-square) per window — this gives
                    // a smooth, natural waveform shape instead of blocky peaks.
                    var sumSquares = 0.0
                    var count = 0

                    while (outputBuffer.remaining() >= 2) {
                        val sample = outputBuffer.short.toInt()
                        val norm = sample / 32768f
                        sumSquares += (norm * norm)
                        count++

                        if (count >= samplesPerWindow) {
                            val rms = sqrt(sumSquares / count).toFloat()
                            chunkAmplitudes.add(rms)
                            sumSquares = 0.0
                            count = 0
                        }
                    }
                    // Flush any remaining partial window
                    if (count > 0) {
                        val rms = sqrt(sumSquares / count).toFloat()
                        chunkAmplitudes.add(rms)
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                outputDone = true
            }
        }

        return chunkAmplitudes
        } finally {
            try { codec.stop() } catch (e: Exception) { /* already stopped/never started successfully */ }
            codec.release()
            // NOTE: extractor is owned by the caller — do NOT release here
        }
    }

    /** Reduces raw amplitude windows down to exactly [barCount] values, normalized 0f..1f. */
    private fun downsample(raw: List<Float>, barCount: Int): List<Float> {
        if (raw.isEmpty()) return emptyList()

        val result = FloatArray(barCount)
        val chunkSize = max(1, raw.size / barCount)

        for (i in 0 until barCount) {
            val start = i * chunkSize
            val end   = minOf(start + chunkSize, raw.size)
            result[i] = if (start >= raw.size) 0f
            else raw.subList(start, end).average().toFloat()
        }

        // Normalize relative to the loudest bar for visual contrast,
        // but keep a natural floor (don't force silence up to a fake minimum)
        val maxVal = result.maxOrNull() ?: 0f
        return if (maxVal > 0.001f) {
            result.map { (it / maxVal).coerceIn(0f, 1f) }
        } else {
            result.map { 0f }
        }
    }
}
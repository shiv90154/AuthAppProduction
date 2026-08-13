package com.example.myapplication.ui.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

data class PcmResult(val pcm: ShortArray, val channels: Int, val sampleRate: Int)

/** Decodes any audio Uri (mp3/wav/m4a/etc) fully to 16-bit PCM. Runs once per load, not per hit. */
object PcmDecoder {

    fun decode(context: Context, uri: Uri): PcmResult? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            decodeInternal(extractor)
        } catch (e: Exception) {
            android.util.Log.e("PcmDecoder", "decode failed: ${e.message}")
            null
        } finally {
            extractor.release()
        }
    }

    fun decodeRawResource(context: Context, resId: Int): PcmResult? {
        val afd = context.resources.openRawResourceFd(resId) ?: return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            decodeInternal(extractor)
        } catch (e: Exception) {
            android.util.Log.e("PcmDecoder", "decodeRaw failed: ${e.message}")
            null
        } finally {
            afd.close()
            extractor.release()
        }
    }

    private fun decodeInternal(extractor: MediaExtractor): PcmResult? {
        var trackIndex = -1
        var format: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex == -1 || format == null) return null

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        val codec = MediaCodec.createDecoderByType(mime)

        // BUG FIX: codec.release() used to only happen after the decode
        // loop finished normally. Any exception mid-loop (malformed/
        // truncated audio — which real users WILL eventually import) left
        // the MediaCodec instance alive forever. Android allows only a
        // small number of concurrent codec instances system-wide; enough
        // leaked decode failures would eventually make EVERY subsequent
        // decode fail too — including loading normal kit sounds — with no
        // obvious connection to the original bad file. try/finally
        // guarantees release() runs on every exit path, not just the happy one.
        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val output = java.io.ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            // BUG FIX: a corrupt file that never signals EOS on either side
            // used to spin this loop forever on a background thread. Cap
            // total iterations as a safety net — a real file finishes in a
            // handful of iterations per second of audio, so this ceiling is
            // never hit by legitimate content.
            var iterations = 0
            val maxIterations = 200_000

            while (!sawOutputEos && iterations < maxIterations) {
                iterations++
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null) {
                        val chunk = ByteArray(bufferInfo.size)
                        outBuffer.get(chunk)
                        outBuffer.clear()
                        output.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }

            val bytes = output.toByteArray()
            val shorts = ShortArray(bytes.size / 2)
            java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(shorts)

            return PcmResult(shorts, channels, sampleRate)
        } finally {
            try { codec.stop() } catch (e: Exception) { /* already stopped/never started successfully */ }
            codec.release()
        }
    }
}
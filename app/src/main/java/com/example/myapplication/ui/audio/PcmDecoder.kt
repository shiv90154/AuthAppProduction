package com.example.myapplication.ui.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

data class PcmResult(val pcm: ShortArray, val channels: Int, val sampleRate: Int)

/** Decodes any audio Uri (mp3/wav/m4a/etc) fully to 16-bit PCM. Runs once per load, not per hit. */
object PcmDecoder {

    // MediaFormat.KEY_PCM_ENCODING values (android.media.AudioFormat.ENCODING_*).
    // 24-bit-packed and 32-bit int only got named constants in API 31; the
    // numeric values are stable, and minSdk here is 24.
    private const val ENC_PCM_8BIT = 3
    private const val ENC_PCM_16BIT = 2
    private const val ENC_PCM_FLOAT = 4
    private const val ENC_PCM_24BIT_PACKED = 21
    private const val ENC_PCM_32BIT = 22

    // Larger WAVs than this skip the in-memory fast path and go through
    // MediaCodec instead (which streams its output the same way as before).
    private const val MAX_DIRECT_WAV_BYTES = 64L * 1024 * 1024

    fun decode(context: Context, uri: Uri): PcmResult? {
        // PCM WAV is parsed directly (see parseWav) — same result on every
        // phone, no MediaCodec involved.
        readWavFromUri(context, uri)?.let { return it }

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
        // Every factory kit sample is a 24-bit stereo WAV. Decoding those
        // through MediaCodec leaves the 24-bit -> 16-bit conversion (and even
        // which sample format comes out) to whatever WAV extractor/decoder
        // the phone's OEM ships — reading that output as 16-bit when it
        // isn't turns the drums into radio-static ("sirrr saarr" on every
        // preloaded patch, on some phones only). Parsing the WAV ourselves
        // makes the result identical on every device.
        val bytes = try {
            context.resources.openRawResource(resId).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (bytes != null) parseWav(bytes)?.let { return it }

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

    // ── Direct WAV parsing ───────────────────────────────────────────────────

    private fun readWavFromUri(context: Context, uri: Uri): PcmResult? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(12)
            var n = 0
            while (n < head.size) {
                val r = input.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            if (n < head.size || !isWavHeader(head)) return@use null

            val out = java.io.ByteArrayOutputStream()
            out.write(head)
            val buf = ByteArray(64 * 1024)
            var total = head.size.toLong()
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                total += r
                if (total > MAX_DIRECT_WAV_BYTES) return@use null
                out.write(buf, 0, r)
            }
            parseWav(out.toByteArray())
        }
    } catch (e: Exception) {
        android.util.Log.w("PcmDecoder", "direct WAV read failed, falling back to MediaCodec: ${e.message}")
        null
    }

    private fun isWavHeader(b: ByteArray): Boolean =
        b.size >= 12 && hasTag(b, 0, "RIFF") && hasTag(b, 8, "WAVE")

    private fun hasTag(b: ByteArray, off: Int, tag: String): Boolean {
        if (off + 4 > b.size) return false
        for (i in 0 until 4) if (b[off + i].toInt() != tag[i].code) return false
        return true
    }

    private fun le16(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, p: Int): Long =
        (le32(b, p).toLong() and 0xFFFFFFFFL) or (le32(b, p + 4).toLong() shl 32)

    /**
     * Parses a RIFF/WAVE file (integer PCM 8/16/24/32-bit or IEEE float
     * 32/64-bit, mono or stereo, including WAVE_FORMAT_EXTENSIBLE) straight to
     * 16-bit PCM, rounding (not truncating) when it narrows. Returns null for
     * anything else so the caller falls back to MediaCodec.
     */
    internal fun parseWav(b: ByteArray): PcmResult? {
        if (!isWavHeader(b)) return null

        var fmtTag = -1
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0

        var pos = 12
        while (pos + 8 <= b.size) {
            val size = le32(b, pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            if (hasTag(b, pos, "fmt ")) {
                if (size < 16 || body + 16 > b.size) return null
                fmtTag = le16(b, body)
                channels = le16(b, body + 2)
                rate = le32(b, body + 4)
                bits = le16(b, body + 14)
                // WAVE_FORMAT_EXTENSIBLE: the real format tag is the first two
                // bytes of the sub-format GUID (offset 24 in the fmt body).
                if (fmtTag == 0xFFFE && size >= 26 && body + 26 <= b.size) fmtTag = le16(b, body + 24)
            } else if (hasTag(b, pos, "data")) {
                dataStart = body
                val avail = (b.size - body).toLong()
                dataLen = (if (size > avail) avail else size).toInt()
                break
            }
            val next = body.toLong() + size + (size and 1L)
            if (next > b.size) break
            pos = next.toInt()
        }

        if (dataStart < 0 || rate <= 0 || channels !in 1..2) return null
        val isInt = fmtTag == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32)
        val isFloat = fmtTag == 3 && (bits == 32 || bits == 64)
        if (!isInt && !isFloat) return null

        val frameBytes = channels * (bits / 8)
        val frames = dataLen / frameBytes
        if (frames <= 0) return null
        val total = frames * channels
        val out = ShortArray(total)
        var p = dataStart

        when {
            isInt && bits == 8 -> for (i in 0 until total) {
                out[i] = (((b[p].toInt() and 0xFF) - 128) shl 8).toShort()
                p += 1
            }
            isInt && bits == 16 -> for (i in 0 until total) {
                out[i] = le16(b, p).toShort()
                p += 2
            }
            isInt && bits == 24 -> for (i in 0 until total) {
                // Third byte is signed, so shifting it in sign-extends the 24-bit value.
                val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() shl 16)
                out[i] = ((v + 128) shr 8).coerceIn(-32768, 32767).toShort()
                p += 3
            }
            isInt && bits == 32 -> for (i in 0 until total) {
                out[i] = ((le32(b, p).toLong() + 32768L) shr 16).coerceIn(-32768L, 32767L).toInt().toShort()
                p += 4
            }
            isFloat && bits == 32 -> for (i in 0 until total) {
                out[i] = floatToShort(Float.fromBits(le32(b, p)).toDouble())
                p += 4
            }
            else -> for (i in 0 until total) { // 64-bit float
                out[i] = floatToShort(Double.fromBits(le64(b, p)))
                p += 8
            }
        }
        return PcmResult(out, channels, rate)
    }

    private fun floatToShort(f: Double): Short =
        if (f.isNaN()) 0 else Math.round(f.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()

    // ── MediaCodec path (mp3 / m4a / anything that isn't plain PCM WAV) ──────

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
            // What the decoder actually emits: channel count / rate / sample
            // encoding come from the OUTPUT format, which can differ from the
            // container's (a decoder is free to emit float or 24-bit, or a
            // different rate/channel count than the file header says).
            var outputFormat: MediaFormat? = null

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
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = codec.outputFormat
                }
            }

            val outFormat = outputFormat ?: runCatching { codec.outputFormat }.getOrNull()
            val outChannels = outFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
            val outRate = outFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
            // Absent key == the decoder default, 16-bit.
            val encoding = outFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: ENC_PCM_16BIT

            val shorts = bytesToPcm16(output.toByteArray(), encoding)
            return PcmResult(shorts, outChannels, outRate)
        } finally {
            try { codec.stop() } catch (e: Exception) { /* already stopped/never started successfully */ }
            codec.release()
        }
    }

    private fun MediaFormat?.intOrNull(key: String): Int? =
        if (this != null && containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    /** Interleaved decoder output bytes (in [encoding]) -> 16-bit PCM. */
    private fun bytesToPcm16(bytes: ByteArray, encoding: Int): ShortArray {
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return when (encoding) {
            ENC_PCM_8BIT -> ShortArray(bytes.size) { (((bytes[it].toInt() and 0xFF) - 128) shl 8).toShort() }
            ENC_PCM_FLOAT -> {
                val n = bytes.size / 4
                ShortArray(n) { floatToShort(bb.getFloat(it * 4).toDouble()) }
            }
            ENC_PCM_24BIT_PACKED -> {
                val n = bytes.size / 3
                ShortArray(n) {
                    val p = it * 3
                    val v = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p + 2].toInt() shl 16)
                    ((v + 128) shr 8).coerceIn(-32768, 32767).toShort()
                }
            }
            ENC_PCM_32BIT -> {
                val n = bytes.size / 4
                ShortArray(n) { ((bb.getInt(it * 4).toLong() + 32768L) shr 16).coerceIn(-32768L, 32767L).toInt().toShort() }
            }
            else -> {
                val shorts = ShortArray(bytes.size / 2)
                bb.asShortBuffer().get(shorts)
                shorts
            }
        }
    }
}

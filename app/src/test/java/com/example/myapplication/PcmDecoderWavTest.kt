package com.example.myapplication

import com.example.myapplication.ui.audio.PcmDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PcmDecoder.parseWav() — the direct WAV path that replaced MediaCodec for the
 * factory kits (all 24-bit stereo) and any PCM WAV the user imports.
 */
class PcmDecoderWavTest {

    // Builds a canonical RIFF/WAVE file around [data].
    private fun wav(fmtTag: Int, channels: Int, rate: Int, bits: Int, data: ByteArray, extensible: Boolean = false): ByteArray {
        val fmtBody = ByteBuffer.allocate(if (extensible) 40 else 16).order(ByteOrder.LITTLE_ENDIAN)
        fmtBody.putShort((if (extensible) 0xFFFE else fmtTag).toShort())
        fmtBody.putShort(channels.toShort())
        fmtBody.putInt(rate)
        fmtBody.putInt(rate * channels * bits / 8)
        fmtBody.putShort((channels * bits / 8).toShort())
        fmtBody.putShort(bits.toShort())
        if (extensible) {
            fmtBody.putShort(22)            // cbSize
            fmtBody.putShort(bits.toShort()) // valid bits
            fmtBody.putInt(3)               // channel mask
            fmtBody.putShort(fmtTag.toShort()) // sub-format GUID starts with the real tag
            fmtBody.put(ByteArray(14))
        }
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        out.write("RIFF".toByteArray()); le32(4 + 8 + fmtBody.capacity() + 8 + data.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(fmtBody.capacity()); out.write(fmtBody.array())
        out.write("data".toByteArray()); le32(data.size); out.write(data)
        return out.toByteArray()
    }

    private fun le(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun pcm16_passesThroughUnchanged() {
        // L=1000, R=-1000
        val r = PcmDecoder.parseWav(wav(1, 2, 48000, 16, le(0xE8, 0x03, 0x18, 0xFC)))!!
        assertEquals(2, r.channels); assertEquals(48000, r.sampleRate)
        assertEquals(listOf<Short>(1000, -1000), r.pcm.toList())
    }

    @Test
    fun pcm24_isRoundedNotTruncated() {
        // 0x000180 = 384 -> (384+128)>>8 = 2 (truncation would give 1)
        // 0xFFFE80 = -384 -> (-384+128)>>8 = -1
        // 0x7FFFFF -> clamps to 32767 ; 0x800000 -> -32768
        val data = le(0x80, 0x01, 0x00,  0x80, 0xFE, 0xFF,  0xFF, 0xFF, 0x7F,  0x00, 0x00, 0x80)
        val r = PcmDecoder.parseWav(wav(1, 2, 44100, 24, data))!!
        assertEquals(listOf<Short>(2, -1, 32767, -32768), r.pcm.toList())
        assertEquals(44100, r.sampleRate)
    }

    @Test
    fun pcm8_isUnsignedCentredOn128() {
        val r = PcmDecoder.parseWav(wav(1, 1, 22050, 8, le(128, 255, 0)))!!
        assertEquals(1, r.channels)
        assertEquals(listOf<Short>(0, 32512, -32768), r.pcm.toList())
    }

    @Test
    fun pcm32_usesTheTopSixteenBitsRounded() {
        val r = PcmDecoder.parseWav(wav(1, 1, 48000, 32, le(0x00, 0x80, 0x00, 0x40)))!! // 0x40008000
        assertEquals(listOf<Short>(16385), r.pcm.toList()) // (0x40008000 + 0x8000) >> 16
    }

    @Test
    fun float32_scalesAndClamps() {
        val bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        bb.putFloat(0.5f).putFloat(-1f).putFloat(2f).putFloat(Float.NaN)
        val r = PcmDecoder.parseWav(wav(3, 1, 48000, 32, bb.array()))!!
        assertEquals(listOf<Short>(16384, -32767, 32767, 0), r.pcm.toList())
    }

    @Test
    fun extensibleHeaderIsUnderstood() {
        val r = PcmDecoder.parseWav(wav(1, 2, 48000, 24, le(0x00, 0x00, 0x40, 0x00, 0x00, 0xC0), extensible = true))!!
        assertEquals(listOf<Short>(16384, -16384), r.pcm.toList())
    }

    @Test
    fun nonWavAndUnsupportedLayoutsFallBackToCodec() {
        assertNull(PcmDecoder.parseWav(ByteArray(64)))
        assertNull(PcmDecoder.parseWav(wav(1, 6, 48000, 16, ByteArray(24)))) // 6 channels
        assertNull(PcmDecoder.parseWav(wav(2, 1, 8000, 4, ByteArray(8))))    // ADPCM tag
    }

    @Test
    fun truncatedDataChunkStillDecodesWhatIsThere() {
        val full = wav(1, 1, 48000, 16, le(1, 0, 2, 0, 3, 0, 4, 0))
        val cut = full.copyOf(full.size - 3) // header says 8 data bytes, only 5 present
        assertEquals(listOf<Short>(1, 2), PcmDecoder.parseWav(cut)!!.pcm.toList())
    }

    // The real shipped factory samples, checked against values computed
    // independently (Python `wave`, same round-to-nearest 24->16 rule).
    private fun factory(name: String): File {
        val f = File("src/main/res/raw/$name")
        return if (f.exists()) f else File("app/src/main/res/raw/$name")
    }

    @Test
    fun factoryKit1Pad1() {
        val r = PcmDecoder.parseWav(factory("kit1_pad1.wav").readBytes())!!
        assertEquals(2, r.channels); assertEquals(48000, r.sampleRate)
        assertEquals(231243 * 2, r.pcm.size)
        for ((frame, lr) in listOf(33034 to (1775 to 1382), 66069 to (-212 to -432), 198208 to (20 to 2))) {
            assertEquals(lr.first, r.pcm[frame * 2].toInt())
            assertEquals(lr.second, r.pcm[frame * 2 + 1].toInt())
        }
    }

    @Test
    fun factoryKit22Pad6() {
        val r = PcmDecoder.parseWav(factory("kit22_pad6.wav").readBytes())!!
        assertEquals(36864 * 2, r.pcm.size)
        for ((frame, lr) in listOf(5266 to (5843 to 5847), 10532 to (5767 to 6149), 26331 to (0 to -151))) {
            assertEquals(lr.first, r.pcm[frame * 2].toInt())
            assertEquals(lr.second, r.pcm[frame * 2 + 1].toInt())
        }
    }
}

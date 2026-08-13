package com.example.myapplication.ui.audio

import android.content.Context
import android.net.Uri
import com.example.myapplication.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Owns the native Oboe engine. 16 native pad slots: 0-7 = Bank A's currently
 * active kit audio, 8-15 = Bank B's, so both banks can be kept loaded and
 * triggered independently (or together, for "AB" bank mode).
 */
object DrumEngine {

    private var started = false
    // Tracks which (kitIndex,padIndex,uriOrResId) is currently loaded into each native slot,
    // so we don't redundantly re-decode+reload on every recomposition.
    // 24 slots: 0-7 Bank A, 8-15 Bank B, 16-23 Bank C.
    private val loadedKey = arrayOfNulls<String>(24)

    // Cached once the stream is open — never assume a fixed rate (e.g.
    // 48000) anywhere ms-based timing gets converted to frames; the engine
    // opens at whatever rate the device actually grants (see AudioEngine::start()).
    private var cachedSampleRate = 0

    fun ensureStarted() {
        if (!started) {
            started = NativeBridge.engineStart()
            if (started) cachedSampleRate = NativeBridge.getSampleRateNative()
        }
    }

    /** Device-native sample rate the engine actually opened at, or 44100 as a
     * pre-start fallback (matches AudioEngine.h's own PadBuffer default). */
    fun sampleRate(): Int = if (cachedSampleRate > 0) cachedSampleRate else 44100

    fun shutdown() {
        NativeBridge.engineStop()
        started = false
    }

    /**
     * Loads whatever audio pad [padIndex] should play for [kitIndex] — custom if assigned,
     * else default raw. [nativeSlot] is which of the 16 native buffer slots this ends up
     * in (defaults to [padIndex]; Bank B loading passes padIndex+8 here so both banks can
     * be loaded simultaneously without clobbering each other).
     */
    fun loadPad(
        context: Context,
        kitIndex: Int,
        padIndex: Int,
        defaultResId: Int,
        reversed: Boolean = false,
        nativeSlot: Int = padIndex
    ) {
        val assigned = AudioRepository.audioForPad(kitIndex, padIndex)

        // NEW: pad has no sound at all (empty custom kit). Overwrite the
        // native slot with silence instead of skipping — skipping left the
        // PREVIOUS kit's sound sitting in that native buffer, which is why
        // switching to an empty kit still played the old kit's audio.
        if (assigned == null && defaultResId == -1) {
            val silentKey = "silent:$nativeSlot"
            if (loadedKey[nativeSlot] != silentKey) {
                NativeBridge.loadPadAudio(nativeSlot, ShortArray(64), 1, 44100)
                loadedKey[nativeSlot] = silentKey
            }
            return
        }

        val key = (assigned?.uri?.toString() ?: "raw:$defaultResId") + (if (reversed) ":rev" else "")

        if (loadedKey[nativeSlot] == key) return // already loaded, skip re-decode

        CoroutineScope(Dispatchers.Default).launch {
            val result = if (assigned != null) {
                PcmDecoder.decode(context, assigned.uri)
            } else {
                PcmDecoder.decodeRawResource(context, defaultResId)
            }

            if (result != null) {
                val pcm = if (reversed) reversePcm(result.pcm, result.channels) else result.pcm
                NativeBridge.loadPadAudio(nativeSlot, pcm, result.channels, result.sampleRate)
                loadedKey[nativeSlot] = key

                // Cache the REAL duration for factory (non-custom) samples —
                // used instead of a guessed default so LOOP-mode timing and
                // the LCD readout match the actual sample length.
                if (assigned == null && defaultResId > 0) {
                    val frames = result.pcm.size / result.channels
                    val durationMs = frames.toLong() * 1000L / result.sampleRate
                    PadDurationCache.put(defaultResId, durationMs)
                }
            }
        }
    }

    /** Reverses PCM frame order (keeping channel interleaving intact) so it plays back-to-front. */
    private fun reversePcm(pcm: ShortArray, channels: Int): ShortArray {
        val frameCount = pcm.size / channels
        val out = ShortArray(pcm.size)
        for (frame in 0 until frameCount) {
            val srcOffset = frame * channels
            val dstOffset = (frameCount - 1 - frame) * channels
            for (ch in 0 until channels) {
                out[dstOffset + ch] = pcm[srcOffset + ch]
            }
        }
        return out
    }

    fun trigger(
        padIndex: Int, volume: Float, pitch: Float,
        stopExisting: Boolean = true, lengthFraction: Float = 1f,
        pan: Float = 0f, gain: Float = 1f
    ) {
        NativeBridge.triggerPad(padIndex, volume, pitch, stopExisting, lengthFraction, pan, gain)
    }

    fun invalidatePad(padIndex: Int) {
        loadedKey[padIndex] = null
    }

    fun setVolume(padIndex: Int, volume: Float) = NativeBridge.setPadVolumeNative(padIndex, volume)
    fun setPitch(padIndex: Int, pitch: Float) = NativeBridge.setPadPitchNative(padIndex, pitch)
    fun setPan(padIndex: Int, pan: Float) = NativeBridge.setPadPanNative(padIndex, pan)
    fun setGain(padIndex: Int, gain: Float) = NativeBridge.setPadGainNative(padIndex, gain)
    fun stop(padIndex: Int) = NativeBridge.stopPadNative(padIndex)
}
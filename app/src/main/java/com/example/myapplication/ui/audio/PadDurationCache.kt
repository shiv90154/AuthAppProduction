package com.example.myapplication.ui.audio

import java.util.concurrent.ConcurrentHashMap

/**
 * Caches the REAL decoded duration (ms) of each factory raw/res sample, keyed
 * by its raw resource id. Populated by DrumEngine.loadPad right after it
 * decodes a factory sample (the decode already happened for playback, this
 * just reads the frame count off the same result — no extra I/O).
 *
 * Why this exists: before it did, factory-kit pads (no custom audio
 * assigned) fell back to a hardcoded DEFAULT_PAD_DURATION_MS = 500ms guess
 * for the LCD readout AND for LOOP-mode retrigger timing. Any factory
 * sample actually longer than 500ms got its tail cut off on every loop
 * retrigger (heard as a "tone cut" / stuttering loop) since the app assumed
 * it had finished long before it actually had.
 */
object PadDurationCache {
    private val durations = ConcurrentHashMap<Int, Long>()

    fun put(resId: Int, durationMs: Long) {
        if (resId > 0 && durationMs > 0) durations[resId] = durationMs
    }

    fun get(resId: Int): Long? = durations[resId]
}

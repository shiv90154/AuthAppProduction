// NativeBridge.kt
package com.example.myapplication

object NativeBridge {

    init {
        System.loadLibrary("myapplication")
    }

    // ── MIDI ────────────────────────────────────────────────────────────────
    external fun sendMidiMessage(channel: Int, note: Int, velocity: Int)
    external fun sendControlChange(channel: Int, ccNumber: Int, ccValue: Int)
    external fun sendProgramChange(channel: Int, program: Int)

    // Note-based MIDI Learn — coexists with CC-based mapping.
    external fun enableMidiLearn(padNumber: Int)
    external fun assignMidiNote(padNumber: Int, note: Int)
    external fun getMappedNoteForPad(padNumber: Int): Int
    external fun getAllPadsForNote(note: Int): IntArray

    // ── Audio Engine ────────────────────────────────────────────────────────
    external fun engineStart(): Boolean
    external fun engineStop()

    external fun loadPadAudio(padIndex: Int, pcm: ShortArray, channels: Int, sampleRate: Int)

    /**
     * volume = pad base volume × velocity multiplier, pitch = pitch multiplier
     * stopExisting = true (default) cuts any voice already playing on this pad
     * before starting the new one; false lets hits layer/overlap (MIX mode).
     * lengthFraction = 0.05f..1f — how much of the sample plays before it's
     * cut, for the per-pad LENGTH trim control (1f = full sample, default).
     * pan = -1f (full left)..1f (full right), 0f = center (default).
     * gain = 0f..2f multiplicative trim on top of volume, 1f = unity (default).
     */
    external fun triggerPad(
        padIndex: Int, volume: Float, pitch: Float,
        stopExisting: Boolean = true, lengthFraction: Float = 1f,
        pan: Float = 0f, gain: Float = 1f
    )

    external fun setPadVolumeNative(padIndex: Int, volume: Float)
    external fun setPadPitchNative(padIndex: Int, pitch: Float)
    external fun setPadPanNative(padIndex: Int, pan: Float)
    external fun setPadGainNative(padIndex: Int, gain: Float)
    external fun stopPadNative(padIndex: Int)

    /** Actual device-native sample rate the engine opened at — never a fixed
     * constant (see AudioEngine::start()). Use this instead of assuming
     * 48000Hz anywhere ms-based timing (e.g. delay) is converted to frames. */
    external fun getSampleRateNative(): Int

    // ── EQ + Master Level ────────────────────────────────────────────────────
    external fun setMasterLevel(level: Float)           // 0f..2f
    external fun setEqBands(low: Float, mid: Float, high: Float)  // each 0f..2f

    // ── Delay Engine ────────────────────────────────────────────────────────
    external fun setDelayEnabled(enabled: Boolean)
    external fun setDelayParams(decayFactor: Float, maxTaps: Int)
    external fun setDelayTapIntervalFrames(frames: Long)  // BPM → frame interval
    external fun setDelayChokePad(padIndex: Int)   // -1 = all pads

    @JvmStatic
    fun onControlChangeFromNative(ccNumber: Int, ccValue: Int) {
        MidiEventBus.triggerControlChange(ccNumber, ccValue)
    }

    // Native Note-On resolved to a pad (MidiProcessor::noteOn, 0-based
    // padIndex) — routed through the same MidiEventBus.triggerPad the
    // hardware-keyboard fallback already uses, which expects 1-based pad
    // numbers (see MainActivity's KEYCODE_Q..F -> 1..8 and OctapadScreen's
    // MidiEventBus.onPadHit dispatcher), hence the +1 here.
    @JvmStatic
    fun onPadHitFromNative(padIndex: Int, velocity: Float) {
        MidiEventBus.triggerPad(padIndex + 1, velocity)
    }

    @JvmStatic
    fun onMidiLearnAssigned(padNumber: Int, note: Int) {
        MidiEventBus.triggerLearnAssigned(padNumber, note)
    }

    @JvmStatic
    fun onProgramChangeFromNative(program: Int) {
        MidiEventBus.triggerProgramChange(program)
    }
}

// MidiEventBus.kt
package com.example.myapplication

/**
 * Simple in-process event bus decoupling MIDI receiver thread from Compose UI thread.
 *
 * onPadHit          : (padNumber 1-8, velocityFloat 0f-1f)
 * onControlChange   : (ccNumber 0-127, ccValue 0-127)
 * onLearnAssigned   : (padNumber 1-8... actually 0-7, see MidiLearnScreen) — fires once
 *                      when native MIDI Learn (Note) captures the next Note-On after
 *                      enableMidiLearn(pad) was called.
 * onProgramChange   : (program 0-63) — direct patch/kit select via MIDI Program Change,
 *                      distinct from the CC-based Next/Prev patch nav.
 * onExitEditMode    : () — fired when the hardware Tab key is pressed
 *                      (MainActivity.dispatchKeyEvent), tells OctapadScreen to
 *                      force EDIT MODE off regardless of its current state.
 */
object MidiEventBus {

    var onPadHit: ((pad: Int, velocity: Float) -> Unit)? = null
    var onControlChange: ((ccNumber: Int, ccValue: Int) -> Unit)? = null
    var onLearnAssigned: ((padNumber: Int, note: Int) -> Unit)? = null
    var onProgramChange: ((program: Int) -> Unit)? = null
    var onExitEditMode: (() -> Unit)? = null
    // Every Note-On, unfiltered — see NativeBridge.onRawNoteOnFromNative.
    var onRawNoteOn: ((note: Int, velocity: Int) -> Unit)? = null

    fun triggerPad(pad: Int, velocity: Float) {
        onPadHit?.invoke(pad, velocity)
    }

    fun triggerControlChange(ccNumber: Int, ccValue: Int) {
        onControlChange?.invoke(ccNumber, ccValue)
    }

    fun triggerLearnAssigned(padNumber: Int, note: Int) {
        onLearnAssigned?.invoke(padNumber, note)
    }

    fun triggerProgramChange(program: Int) {
        onProgramChange?.invoke(program)
    }

    fun triggerExitEditMode() {
        onExitEditMode?.invoke()
    }

    fun triggerRawNoteOn(note: Int, velocity: Int) {
        onRawNoteOn?.invoke(note, velocity)
    }
}

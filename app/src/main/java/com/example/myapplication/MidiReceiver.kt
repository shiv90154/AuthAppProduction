// MidiReceiverHandler.kt
package com.example.myapplication

import android.media.midi.MidiReceiver
import android.util.Log

/**
 * Parses raw MIDI bytes and routes them to NativeBridge.
 *
 * NOTE-ON  → NativeBridge.sendMidiMessage() → MidiProcessor::noteOn() resolves
 *            a pad via the Note->pad map and triggers it (coexists with CC-based
 *            pad triggering below — a controller can use either).
 *
 * CC       → NativeBridge.sendControlChange()
 *            C++ routes back via onControlChangeFromNative(cc, val), which
 *            OctapadScreen matches against CcMapRepository's targets —
 *            including PAD_1..PAD_8, for controllers that send CC instead
 *            of Note-On for pad hits.
 *
 * PROGRAM CHANGE → NativeBridge.sendProgramChange() — direct patch/kit
 *            select (0-63), distinct from the CC-based Next/Prev patch nav.
 *            This is a 2-byte message (status + program number only, no
 *            velocity byte), so it's parsed before the 3-byte-only guard
 *            below that Note-On/Off/CC need.
 */
class MidiReceiverHandler : MidiReceiver() {

    override fun onSend(
        data: ByteArray,
        offset: Int,
        count: Int,
        timestamp: Long
    ) {
        if (count < 2) return

        val status  = data[offset].toInt() and 0xFF
        val command = status and 0xF0
        val channel = (status and 0x0F) + 1

        // MIDI Channel Select: -1 = listen on all channels (default).
        // Anything else = ignore every message not on that exact channel.
        val listenChannel = MidiChannelState.selectedChannel
        if (listenChannel != -1 && channel != listenChannel) return

        // ── Program Change (2-byte message) ─────────────────────────────────
        if (command == 0xC0) {
            val program = data[offset + 1].toInt() and 0xFF
            Log.d("MIDI_PC", "PROGRAM CHANGE : program=$program channel=$channel")
            NativeBridge.sendProgramChange(channel, program)
            return
        }

        if (count < 3) return
        val note     = data[offset + 1].toInt() and 0xFF
        val velocity = data[offset + 2].toInt() and 0xFF

        when (command) {

            // ── Note On ────────────────────────────────────────────────────
            0x90 -> {
                LatencyTracker.midiTime = System.nanoTime()

                Log.d("MIDI_NOTE",  "NOTE ON  : note=$note velocity=$velocity channel=$channel")
                Log.d("VELOCITY",   "Pad hit strength = ${((velocity / 127f) * 100).toInt()}%")

                NativeBridge.sendMidiMessage(channel, note, velocity)
            }

            // ── Note Off ───────────────────────────────────────────────────
            0x80 -> {
                Log.d("MIDI_NOTE", "NOTE OFF : note=$note")
                NativeBridge.sendMidiMessage(channel, note, 0)
            }

            // ── Control Change (knobs / sliders) ───────────────────────────
            0xB0 -> {
                val ccNumber = note      // byte 2 = controller number
                val ccValue  = velocity  // byte 3 = controller value (0-127)
                Log.d("MIDI_CC_DEBUG",
                      "CONTROL CHANGE : cc=$ccNumber value=$ccValue channel=$channel")
                NativeBridge.sendControlChange(channel, ccNumber, ccValue)
            }
        }
    }
}

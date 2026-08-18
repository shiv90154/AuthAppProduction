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
            // BUG FIX: this handler runs on the MIDI receive thread for
            // EVERY incoming message — Note On/Off logs are one per hit
            // (tolerable), but the Control Change log below used to fire on
            // every single CC tick, and a fast knob sweep can send dozens of
            // those per second. Each Log.d is a real (if individually small)
            // syscall into the logging daemon; stacking that many of them
            // back-to-back on this thread, combined with the old
            // persistKits()-per-tick bug (see OctapadScreen's
            // persistKitsDebounced), is what read as "MIDI knob gets stuck
            // after testing for a while" — this thread falling behind under
            // sustained CC traffic. Removed the per-message logs entirely;
            // they were never anything but debug noise in a shipped build
            // anyway (ProGuard/R8 is disabled, so nothing strips them).
            0x90 -> {
                LatencyTracker.midiTime = System.nanoTime()
                NativeBridge.sendMidiMessage(channel, note, velocity)
            }

            // ── Note Off ───────────────────────────────────────────────────
            0x80 -> {
                NativeBridge.sendMidiMessage(channel, note, 0)
            }

            // ── Control Change (knobs / sliders) ───────────────────────────
            0xB0 -> {
                val ccNumber = note      // byte 2 = controller number
                val ccValue  = velocity  // byte 3 = controller value (0-127)
                NativeBridge.sendControlChange(channel, ccNumber, ccValue)
            }
        }
    }
}

package com.example.myapplication

/**
 * Which MIDI channel (1-16) the app listens on. -1 = ALL channels (default —
 * matches the old behaviour, so existing hardware setups keep working
 * without having to pick a channel first). Read on the MIDI input thread in
 * MidiReceiverHandler, written from the MIDI Mapping screen's channel
 * selector — plain @Volatile Int is enough synchronization for that.
 */
object MidiChannelState {
    @Volatile
    var selectedChannel: Int = -1
}

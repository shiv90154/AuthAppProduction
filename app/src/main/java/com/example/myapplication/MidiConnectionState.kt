package com.example.myapplication

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Live MIDI connection status, shown as the small MIDI badge at the top of the
 * main screen (see ui/MidiStatusBadge.kt). Written by [MidiManagerHelper] on
 * the main thread as devices come and go; [lastMessageAtMs] is stamped from
 * the MIDI receive thread on every incoming message.
 */
object MidiConnectionState {

    /** One plug/unplug, so the badge can flash it even if the cable was pulled right back out. */
    class Event(val connected: Boolean, val name: String, val seq: Int, val atMs: Long)

    /** device id -> display name, for every controller currently connected and sending to us. */
    val devices = mutableStateMapOf<Int, String>()

    var lastEvent by mutableStateOf<Event?>(null)
        private set

    @Volatile
    var lastMessageAtMs: Long = 0L

    private var seq = 0

    val isConnected: Boolean get() = devices.isNotEmpty()

    fun deviceConnected(id: Int, name: String) {
        devices[id] = name
        lastEvent = Event(true, name, ++seq, SystemClock.uptimeMillis())
    }

    fun deviceDisconnected(id: Int) {
        val name = devices.remove(id) ?: return
        lastEvent = Event(false, name, ++seq, SystemClock.uptimeMillis())
    }

    /** Called from the MIDI receive thread — keep it a single volatile write. */
    fun noteMessageReceived() {
        lastMessageAtMs = SystemClock.uptimeMillis()
    }
}

package com.example.myapplication

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Connects to every MIDI controller that is plugged in — now AND later.
 *
 * This used to list the devices once from MainActivity.onCreate() and never
 * look again, so a controller plugged in (or re-plugged) after the app had
 * started was silently never connected. A device callback is registered
 * instead: each controller is opened when it appears, closed when it goes,
 * and [MidiConnectionState] is updated both times so the screen can show it.
 *
 * Safe to call [listDevices] from every Activity (re)creation — the actual
 * setup runs once per process and everything is main-thread only.
 */
class MidiManagerHelper(
    context: Context
) {
    private val appContext = context.applicationContext

    fun listDevices() {
        MidiHotplug.start(appContext)
    }
}

private object MidiHotplug {

    private const val TAG = "MIDI_TEST"

    private var started = false
    private val handler = Handler(Looper.getMainLooper())

    // Main-thread only (callbacks below are all delivered on `handler`).
    private class Open(val device: MidiDevice, val port: MidiOutputPort)
    private val open = HashMap<Int, Open>()
    private val pending = HashSet<Int>()

    // MidiManager.devices was deprecated in API 31 in favor of
    // getDevicesForTransport(TRANSPORT_MIDI_BYTE_STREAM) — but minSdk here
    // is 24, so the old property is still the only one that works across
    // the full supported range. Suppressed rather than version-gated to
    // avoid introducing an untested API-31+ code path with no device to
    // verify it on.
    @Suppress("DEPRECATION")
    fun start(context: Context) {
        if (started) return

        // getSystemService(MidiManager::class.java) can return null on
        // devices without MIDI support — dereferencing it used to crash the
        // app on launch before the user ever saw a screen.
        val midiManager = context.getSystemService(MidiManager::class.java) ?: run {
            Log.d(TAG, "MidiManager unavailable on this device — skipping MIDI setup")
            return
        }
        started = true

        midiManager.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                connect(midiManager, device)
            }

            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                disconnect(device)
            }
        }, handler)

        val devices = midiManager.devices
        Log.d(TAG, "Devices Found = ${devices.size}")
        for (info in devices) connect(midiManager, info)
    }

    private fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI device"
    }

    private fun connect(midiManager: MidiManager, info: MidiDeviceInfo) {
        // A device with no output port never sends us anything (a synth /
        // sound module) — not a controller, nothing to connect.
        if (info.outputPortCount == 0) return
        val id = info.id
        if (open.containsKey(id) || !pending.add(id)) return

        midiManager.openDevice(info, onOpened@{ device ->
            // Null when opening failed (unplugged mid-open, permission
            // revoked, ...). Also skip a device that was removed while the
            // open request was still in flight.
            val stillWanted = pending.remove(id)
            if (device == null || !stillWanted) {
                Log.d(TAG, "Device failed to open or already gone — skipping")
                runCatching { device?.close() }
                return@onOpened
            }

            val port = device.openOutputPort(0)
            if (port == null) {
                Log.d(TAG, "Device has no usable output port — skipping")
                runCatching { device.close() }
                return@onOpened
            }
            port.connect(MidiReceiverHandler())
            open[id] = Open(device, port)
            MidiConnectionState.deviceConnected(id, nameOf(info))
            Log.d(TAG, "Receiver Connected: ${nameOf(info)}")
        }, handler)
    }

    private fun disconnect(info: MidiDeviceInfo) {
        val id = info.id
        pending.remove(id)
        val entry = open.remove(id)
        if (entry != null) {
            runCatching { entry.port.close() }
            runCatching { entry.device.close() }
        }
        MidiConnectionState.deviceDisconnected(id)
        Log.d(TAG, "Device removed: ${nameOf(info)}")
    }
}

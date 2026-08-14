package com.example.myapplication

import android.content.Context
import android.media.midi.*
import android.os.Handler
import android.os.Looper
import android.util.Log

class MidiManagerHelper(
    private val context: Context
) {

    // MidiManager.devices was deprecated in API 31 in favor of
    // getDevicesForTransport(TRANSPORT_MIDI_BYTE_STREAM) — but minSdk here
    // is 24, so the old property is still the only one that works across
    // the full supported range. Suppressed rather than version-gated to
    // avoid introducing an untested API-31+ code path with no device to
    // verify it on.
    @Suppress("DEPRECATION")
    fun listDevices() {

        // BUG FIX: getSystemService(MidiManager::class.java) can return null
        // on devices without MIDI support — this used to be dereferenced
        // unconditionally, which would crash the app on launch (this is
        // called unconditionally from MainActivity.onCreate) on any such
        // device before the user ever sees a screen.
        val midiManager =
            context.getSystemService(
                MidiManager::class.java
            ) ?: run {
                Log.d("MIDI_TEST", "MidiManager unavailable on this device — skipping MIDI setup")
                return
            }

        val devices =
            midiManager.devices

        Log.d(
            "MIDI_TEST",
            "Devices Found = ${devices.size}"
        )

        if (devices.isEmpty()) {
            Log.d(
                "MIDI_TEST",
                "No MIDI Device Found"
            )
            return
        }

        for (deviceInfo in devices) {

            Log.d(
                "MIDI_TEST",
                "Device = ${deviceInfo.properties}"
            )

            midiManager.openDevice(
                deviceInfo,
                onOpened@{ device ->

                    // BUG FIX: MidiManager.OnDeviceOpenedListener can be
                    // called with a null device if opening failed (device
                    // unplugged mid-open, permission revoked, etc.) — this
                    // used to be dereferenced unconditionally below, which
                    // would crash with an NPE instead of just skipping that
                    // device. openDevice() is a plain (non-inline) Java SDK
                    // method, so this must be a local return via an explicit
                    // label, not a bare `return`.
                    if (device == null) {
                        Log.d("MIDI_TEST", "Device failed to open — skipping")
                        return@onOpened
                    }

                    Log.d(
                        "MIDI_TEST",
                        "Device Opened"
                    )

                    val outputPort =
                        device.openOutputPort(0)

                    if (outputPort != null) {

                        outputPort.connect(
                            MidiReceiverHandler()
                        )

                        Log.d(
                            "MIDI_TEST",
                            "Receiver Connected"
                        )
                    }

                },
                Handler(
                    Looper.getMainLooper()
                )
            )
        }
    }
}
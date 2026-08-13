package com.example.myapplication

import androidx.compose.runtime.mutableStateOf

/**
 * Shared "listening for next CC" flag used by the MIDI CC mapping screen.
 * When set to a target name, OctapadScreen's onControlChange handler will
 * bind the NEXT incoming CC message to that target instead of acting on it,
 * then clear this flag.
 */
object CcLearnState {
    val listeningForTarget = mutableStateOf<String?>(null)
    val lastAssigned = mutableStateOf<Pair<String, Int>?>(null)
}

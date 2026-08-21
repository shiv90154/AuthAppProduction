package com.example.myapplication

import androidx.compose.runtime.mutableStateOf

/**
 * Shared "listening for next Note" flag used by the MIDI mapping screen's
 * button/action targets (EDIT/SAVE/PATCH_NEXT/PATCH_PREV/DELAY_TOGGLE/
 * BANK_A/BANK_B/BANK_AB — see NoteMapRepository). When set to a target
 * name, OctapadScreen's MidiEventBus.onRawNoteOn handler binds the NEXT
 * incoming Note-On to that target instead of dispatching it, then clears
 * this flag. Mirrors CcLearnState, but for Note instead of CC — these
 * targets used to be CC-learned; the client wants button/action targets to
 * always be triggered by an actual MIDI Note (from the controller), never
 * a CC number typed/learned via an on-screen button.
 */
object NoteLearnState {
    val listeningForTarget = mutableStateOf<String?>(null)
    val lastAssigned = mutableStateOf<Pair<String, Int>?>(null)
}

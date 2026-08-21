package com.example.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.CcMapRepository
import com.example.myapplication.CcLearnState
import com.example.myapplication.NoteMapRepository
import com.example.myapplication.NoteLearnState
import com.example.myapplication.MidiLearnRepository
import com.example.myapplication.MidiEventBus
import com.example.myapplication.NativeBridge

/**
 * MidiLearnScreen — MIDI mapping UI.
 *
 * Pad triggering is Note-only: a real drum-pad controller sends Note-On for
 * a pad hit, so pad mapping (below, "PAD NOTES") is always Note-based — GM
 * defaults are pre-filled, LEARN just overrides one. There used to also be
 * a CC-based PAD_1..PAD_8 path for controllers that sent CC instead; that's
 * removed — a physical pad press is a Note-On, not a knob/CC sweep, so a
 * CC-learn button for it never actually matched what the hardware sent.
 *
 * Button/action targets (PATCH_NEXT, PATCH_PREV, EDIT, SAVE, DELAY_TOGGLE,
 * BANK_A/B/AB — "ACTIONS" below) are Note-based too, for the same reason:
 * these map to a physical button/pad on the controller, which sends a
 * Note-On, not a CC. Only genuinely continuous knobs (VOLUME, PITCH, EQ —
 * "KNOBS" below) stay CC-based, since only a CC's 0-127 value stream can
 * drive a continuous slider.
 *
 * Note LEARN (pads and actions alike): tap LEARN on a row → the row starts
 * listening for the next Note-On (pads go through native
 * enableMidiLearn/onLearnAssigned; actions go through NoteLearnState,
 * consumed by OctapadScreen's MidiEventBus.onRawNoteOn) → mapping is saved
 * and applied immediately, row shows "Waiting for Note…" while listening.
 *
 * CC LEARN (knobs only): tap LEARN on a target row → screen listens for the
 * next CC message → CcLearnState captures it → mapping is saved to
 * CcMapRepository.
 */
@Composable
fun MidiLearnScreen(
    midiChannel: Int = -1,
    onMidiChannelChange: (Int) -> Unit = {},
    onClose: () -> Unit
) {

    val context = LocalContext.current

    // Init repositories
    LaunchedEffect(Unit) {
        CcMapRepository.init(context)
        NoteMapRepository.init(context)
        MidiLearnRepository.init(context)
    }

    // ── Note (pad) mappings ──────────────────────────────────────────────────
    val noteMappings = remember {
        mutableStateMapOf<Int, Int>().also { map ->
            for (pad in 0..7) map[pad] = NativeBridge.getMappedNoteForPad(pad)
        }
    }
    var learningPad by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(Unit) {
        MidiEventBus.onLearnAssigned = { pad, note ->
            noteMappings[pad] = note
            MidiLearnRepository.save(pad, note)
            learningPad = null
        }
        onDispose {
            MidiEventBus.onLearnAssigned = null
            // BUG FIX: previously only the X-button/Cancel taps cleared this,
            // so leaving the screen any other way (DONE while still
            // "listening", or topPanel changing out from under this
            // composable) left CcLearnState.listeningForTarget stuck set.
            // OctapadScreen's onControlChange handler is global and stays
            // registered after this screen closes, so the next CC message
            // anywhere in the app would get silently consumed as a learn
            // instead of acting normally — clearing here on every dispose
            // path guarantees it can't get stuck.
            CcLearnState.listeningForTarget.value = null
            // Same reasoning as CcLearnState above, for the Note-based
            // action targets — clear on every dispose path so a note never
            // gets silently consumed as a learn after this screen closes.
            NoteLearnState.listeningForTarget.value = null
        }
    }

    // ── Note (action/button target) mappings ──────────────────────────────────
    val noteActionMappings = remember {
        mutableStateMapOf<String, Int>().also { map ->
            NoteMapRepository.loadAll().forEach { (target, note) -> map[target] = note }
        }
    }
    val listeningNoteTarget = NoteLearnState.listeningForTarget.value

    LaunchedEffect(NoteLearnState.lastAssigned.value) {
        val assigned = NoteLearnState.lastAssigned.value
        if (assigned != null) {
            noteActionMappings[assigned.first] = assigned.second
            NoteLearnState.lastAssigned.value = null
        }
    }

    // ── CC (continuous knob) mappings: Volume, Pitch, EQ only ──────────────────
    val ccMappings = remember {
        mutableStateMapOf<String, Int>().also { map ->
            CcMapRepository.loadAll().forEach { (target, cc) -> map[target] = cc }
        }
    }
    val listeningTarget = CcLearnState.listeningForTarget.value

    LaunchedEffect(CcLearnState.lastAssigned.value) {
        val assigned = CcLearnState.lastAssigned.value
        if (assigned != null) {
            ccMappings[assigned.first] = assigned.second
            CcLearnState.lastAssigned.value = null
        }
    }

    // Status message
    var statusMsg by remember { mutableStateOf("Tap LEARN on a row, then hit/move the matching control") }

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD000000))
            .pointerInput(Unit) { detectTapGestures { /* consume backdrop */ } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {

            // ── Header ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "MAP MIDI",
                        color = BtnActive, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                    )
                    Text(
                        "Tap LEARN → hit/move your MIDI controller",
                        color = Color(0xFF888888), fontSize = 9.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A))
                        .pointerInput(Unit) {
                            detectTapGestures {
                                if (listeningTarget != null) {
                                    CcLearnState.listeningForTarget.value = null
                                }
                                learningPad = null
                                onClose()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Status Banner ───────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            listeningTarget != null || listeningNoteTarget != null || learningPad != null -> Color(0xFF330033)
                            statusMsg.contains("cleared") -> Color(0xFF003322)
                            else -> Color(0xFF1A1A1A)
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = when {
                            listeningTarget != null || listeningNoteTarget != null || learningPad != null -> Color(0xFFFF00FF)
                            else -> Color(0xFF2A2A2A)
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (listeningTarget != null || listeningNoteTarget != null || learningPad != null) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFFFF00FF))
                        )
                    }
                    Text(
                        text = when {
                            learningPad != null -> "Listening for PAD ${learningPad!! + 1}… hit the controller pad now"
                            listeningNoteTarget != null -> "Waiting for Note… hit/press the controller button for $listeningNoteTarget now"
                            listeningTarget != null -> "Listening for $listeningTarget… move the knob now"
                            else -> statusMsg
                        },
                        color = when {
                            listeningTarget != null || listeningNoteTarget != null || learningPad != null -> Color(0xFFFF88FF)
                            else -> Color(0xFF888888)
                        },
                        fontSize = 10.sp,
                        fontWeight = if (listeningTarget != null || listeningNoteTarget != null || learningPad != null) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── MIDI Channel Select (1-16, or ALL) ──────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1A1A))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "MIDI CHANNEL",
                    color = Color(0xFFCCCCCC), fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(50))
                            .background(NavRed)
                            .pointerInput(midiChannel) {
                                detectTapGestures {
                                    // -1 (ALL) sits before channel 1 in the cycle
                                    onMidiChannelChange(if (midiChannel <= -1) 16 else midiChannel - 1)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("<", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text(
                        if (midiChannel == -1) "ALL" else "$midiChannel",
                        color = BtnActive, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(32.dp), textAlign = TextAlign.Center
                    )
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(50))
                            .background(NavRed)
                            .pointerInput(midiChannel) {
                                detectTapGestures {
                                    onMidiChannelChange(if (midiChannel >= 16) -1 else midiChannel + 1)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(">", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Mapping list — Note pads first, then CC pads, then everything
            // else. A pad works via either its Note or its CC mapping. ──────
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF161616)),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Text(
                        "PAD NOTES  •  MOST DRUM CONTROLLERS USE THIS",
                        color = BtnActive, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pre-filled with standard GM drum notes — LEARN only if your controller uses different ones.",
                        color = Color(0xFF888888), fontSize = 8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                }

                items((0..7).toList()) { pad ->
                    val note = noteMappings[pad] ?: -1
                    val isLearning = learningPad == pad

                    NotePadRow(
                        padNumber = pad,
                        note = note,
                        isLearning = isLearning,
                        onLearn = {
                            if (isLearning) {
                                learningPad = null
                                statusMsg = "Cancelled. Tap LEARN to map a pad note."
                            } else {
                                learningPad = pad
                                NativeBridge.enableMidiLearn(pad)
                                statusMsg = "Hit the controller pad you want mapped to PAD ${pad + 1}"
                            }
                        },
                        onClear = {
                            MidiLearnRepository.clear(pad)
                            val gmDefault = intArrayOf(36, 48, 51, 49, 45, 46, 42, 38)[pad]
                            NativeBridge.assignMidiNote(pad, gmDefault)
                            noteMappings[pad] = gmDefault
                            if (learningPad == pad) learningPad = null
                            statusMsg = "PAD ${pad + 1} note reset to GM default"
                        }
                    )
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "KNOBS  •  CONTINUOUS CONTROLS, CC ONLY",
                        color = BtnActive, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(6.dp))
                }

                items(CcMapRepository.TARGETS) { target ->
                    val cc = ccMappings[target] ?: -1
                    val isListening = listeningTarget == target

                    CcTargetRow(
                        target = target,
                        ccNumber = cc,
                        isListening = isListening,
                        onLearn = {
                            if (isListening) {
                                CcLearnState.listeningForTarget.value = null
                                statusMsg = "Cancelled. Tap LEARN to map a knob."
                            } else {
                                CcLearnState.listeningForTarget.value = target
                                statusMsg = "Move the knob for $target"
                            }
                        },
                        onClear = {
                            CcMapRepository.clear(target)
                            ccMappings[target] = CcMapRepository.getCc(target)
                            if (listeningTarget == target) CcLearnState.listeningForTarget.value = null
                            statusMsg = "$target mapping cleared"
                        }
                    )
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "ACTIONS  •  BUTTON/PAD TRIGGERS, NOTE ONLY",
                        color = BtnActive, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No built-in defaults — LEARN each once before hardware triggers it.",
                        color = Color(0xFF888888), fontSize = 8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                }

                items(NoteMapRepository.TARGETS) { target ->
                    val note = noteActionMappings[target] ?: -1
                    val isListening = listeningNoteTarget == target

                    NoteTargetRow(
                        target = target,
                        note = note,
                        isListening = isListening,
                        onLearn = {
                            if (isListening) {
                                NoteLearnState.listeningForTarget.value = null
                                statusMsg = "Cancelled. Tap LEARN to map an action."
                            } else {
                                NoteLearnState.listeningForTarget.value = target
                                statusMsg = "Hit/press the controller button for $target"
                            }
                        },
                        onClear = {
                            NoteMapRepository.clear(target)
                            noteActionMappings[target] = NoteMapRepository.getNote(target)
                            if (listeningNoteTarget == target) NoteLearnState.listeningForTarget.value = null
                            statusMsg = "$target mapping cleared"
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Bottom action row ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Reset all to defaults
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A0000))
                        .pointerInput(Unit) {
                            detectTapGestures {
                                CcMapRepository.TARGETS.forEach { target ->
                                    CcMapRepository.clear(target)
                                    ccMappings[target] = CcMapRepository.getCc(target)
                                }
                                CcLearnState.listeningForTarget.value = null

                                NoteMapRepository.TARGETS.forEach { target ->
                                    NoteMapRepository.clear(target)
                                    noteActionMappings[target] = NoteMapRepository.getNote(target)
                                }
                                NoteLearnState.listeningForTarget.value = null

                                val gmDefaults = intArrayOf(36, 48, 51, 49, 45, 46, 42, 38)
                                for (pad in 0..7) {
                                    MidiLearnRepository.clear(pad)
                                    NativeBridge.assignMidiNote(pad, gmDefaults[pad])
                                    noteMappings[pad] = gmDefaults[pad]
                                }
                                learningPad = null

                                statusMsg = "All MIDI mappings reset to defaults"
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "RESET ALL",
                        color = Color(0xFFFF6666), fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }

                // Done
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF003333))
                        .pointerInput(Unit) { detectTapGestures { onClose() } }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "DONE",
                        color = BtnActive, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ── Note pad row composable ─────────────────────────────────────────────────────

private fun midiNoteName(note: Int): String {
    if (note < 0) return "?"
    val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val octave = note / 12 - 1
    return "${names[note % 12]}$octave"
}

@Composable
private fun NotePadRow(
    padNumber: Int,
    note: Int,
    isLearning: Boolean,
    onLearn: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isLearning -> Color(0xFF2A0033)
                    note >= 0 -> Color(0xFF001A2E)
                    else -> Color(0xFF1E1E1E)
                }
            )
            .border(
                width = if (isLearning) 1.dp else 0.dp,
                color = if (isLearning) Color(0xFFFF00FF) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "PAD ${padNumber + 1}",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isLearning) "Waiting for Note…" else if (note >= 0) "${midiNoteName(note)} ($note)" else "Unmapped",
                color = when {
                    isLearning -> Color(0xFFFF88FF)
                    note >= 0 -> Color(0xFF00E5FF)
                    else -> Color(0xFF666666)
                },
                fontSize = 9.sp
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isLearning) Color(0xFFFF00FF) else Color(0xFF2A2A2A))
                .pointerInput(Unit) { detectTapGestures { onLearn() } }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isLearning) "CANCEL" else "LEARN",
                color = if (isLearning) Color.Black else Color(0xFFAAAAAA),
                fontSize = 9.sp, fontWeight = FontWeight.Bold
            )
        }

        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2A1010))
                .pointerInput(Unit) { detectTapGestures { onClear() } },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = Color(0xFFFF6666), fontSize = 11.sp)
        }
    }
}

// ── CC target row composable ──────────────────────────────────────────────────

@Composable
private fun CcTargetRow(
    target: String,
    ccNumber: Int,
    isListening: Boolean,
    onLearn: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isListening -> Color(0xFF2A0033)
                    ccNumber >= 0 -> Color(0xFF001A2E)
                    else -> Color(0xFF1E1E1E)
                }
            )
            .border(
                width = if (isListening) 1.dp else 0.dp,
                color = if (isListening) Color(0xFFFF00FF) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                target.replace("_", " "),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isListening) "Waiting for CC…" else if (ccNumber >= 0) "CC $ccNumber" else "Unmapped",
                color = when {
                    isListening -> Color(0xFFFF88FF)
                    ccNumber >= 0 -> Color(0xFF00E5FF)
                    else -> Color(0xFF666666)
                },
                fontSize = 9.sp
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isListening) Color(0xFFFF00FF) else Color(0xFF2A2A2A))
                .pointerInput(Unit) { detectTapGestures { onLearn() } }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isListening) "CANCEL" else "LEARN",
                color = if (isListening) Color.Black else Color(0xFFAAAAAA),
                fontSize = 9.sp, fontWeight = FontWeight.Bold
            )
        }

        if (ccNumber >= 0) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2A1010))
                    .pointerInput(Unit) { detectTapGestures { onClear() } },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = Color(0xFFFF6666), fontSize = 11.sp)
            }
        }
    }
}

// ── Note action-target row composable ───────────────────────────────────────
// Same layout as CcTargetRow, but for a button/action target learned via a
// MIDI Note instead of a CC (see NoteMapRepository) — "Waiting for Note…"
// while listening, "Note N" once mapped.

@Composable
private fun NoteTargetRow(
    target: String,
    note: Int,
    isListening: Boolean,
    onLearn: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isListening -> Color(0xFF2A0033)
                    note >= 0 -> Color(0xFF001A2E)
                    else -> Color(0xFF1E1E1E)
                }
            )
            .border(
                width = if (isListening) 1.dp else 0.dp,
                color = if (isListening) Color(0xFFFF00FF) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                target.replace("_", " "),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isListening) "Waiting for Note…" else if (note >= 0) "${midiNoteName(note)} ($note)" else "Unmapped",
                color = when {
                    isListening -> Color(0xFFFF88FF)
                    note >= 0 -> Color(0xFF00E5FF)
                    else -> Color(0xFF666666)
                },
                fontSize = 9.sp
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isListening) Color(0xFFFF00FF) else Color(0xFF2A2A2A))
                .pointerInput(Unit) { detectTapGestures { onLearn() } }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isListening) "CANCEL" else "LEARN",
                color = if (isListening) Color.Black else Color(0xFFAAAAAA),
                fontSize = 9.sp, fontWeight = FontWeight.Bold
            )
        }

        if (note >= 0) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2A1010))
                    .pointerInput(Unit) { detectTapGestures { onClear() } },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = Color(0xFFFF6666), fontSize = 11.sp)
            }
        }
    }
}

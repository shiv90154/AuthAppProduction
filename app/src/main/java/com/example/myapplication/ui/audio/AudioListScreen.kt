package com.example.myapplication.ui.audio

import android.media.MediaPlayer
import androidx.compose.foundation.background
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
import com.example.myapplication.ui.BtnActive
import com.example.myapplication.ui.PanelBg
import com.example.myapplication.R
import com.example.myapplication.ui.audio.DrumEngine
/**
 * AudioListScreen — shows all imported audios.
 *
 * Features:
 *  - Play / Stop each audio
 *  - Delete an audio
 *  - Assign audio to a pad (0-7) via a bottom sheet picker
 *
 * @param onClose  Dismiss the screen
 */
@Composable
fun AudioListScreen(
    currentKit: Int,
    factoryResIds: List<Int>,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    // BUG FIX: this used to guess the factory resource name from the kit's
    // LIST POSITION ("kit${currentKit + 1}_padN") instead of the kit's
    // actual factory source. Those only match by coincidence — the moment
    // any kit is added, deleted, reordered, or is itself user-created
    // (factoryKitNumber = -1), currentKit+1 no longer corresponds to the
    // right factory kit number. Unassigning a custom sound from a pad could
    // silently load a COMPLETELY DIFFERENT kit's factory sound onto it, or
    // wrongly fall back to silence when a real factory sound existed.
    // factoryResIds (kits[currentKit].sounds, passed in from OctapadScreen —
    // the same authoritative source every other screen already uses) fixes
    // this by looking up the real resource id directly.
    fun defaultResIdFor(padIndex: Int): Int =
        factoryResIds.getOrElse(padIndex) { -1 }

    // Local snapshot of the repository list so recompose happens on changes
    var audioList by remember { mutableStateOf(AudioRepository.getAll()) }
    var playingId by remember { mutableStateOf<Long?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Which audio is waiting for pad assignment (shows pad picker)
    var assignTarget by remember { mutableStateOf<AudioItem?>(null) }

    fun refresh() { audioList = AudioRepository.getAll() }

    fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        playingId   = null
    }

    // Clean up player when screen closes
    DisposableEffect(Unit) {
        onDispose { stopPlayback() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            // Consume all pointer events — no pad leakthrough
            .pointerInput(Unit) { detectTapGestures { /* consume */ } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PanelBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "AUDIOS",
                    color = BtnActive,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    "${audioList.size} imported",
                    color = Color(0xFF666666),
                    fontSize = 11.sp
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A))
                        .pointerInput(Unit) { detectTapGestures { stopPlayback(); onClose() } },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Empty state ──────────────────────────────────────────────────────
            if (audioList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF161616)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎵", fontSize = 40.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "No audios imported yet.\nTap Import to add sounds.",
                            color = Color(0xFF555555),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // ── Audio list ─────────────────────────────────────────────────
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF161616)),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(audioList, key = { it.id }) { audio ->
                        AudioListItem(
                            audio     = audio,
                            isPlaying = playingId == audio.id,
                            onPlay    = {
                                if (playingId == audio.id) {
                                    stopPlayback()
                                } else {
                                    stopPlayback()
                                    try {
                                        val mp = MediaPlayer()
                                        mp.setDataSource(context, audio.uri)
                                        mp.prepare()
                                        mp.setOnCompletionListener { stopPlayback() }
                                        mp.start()
                                        mediaPlayer = mp
                                        playingId   = audio.id
                                    } catch (e: Exception) { /* ignore */ }
                                }
                            },
                            onDelete  = {
                                if (playingId == audio.id) stopPlayback()
                                AudioRepository.remove(audio.id)
                                refresh()
                            },
                            onAssignPad = {
                                assignTarget = audio
                            }
                        )
                    }
                }
            }
        }

        // ── Pad picker overlay ───────────────────────────────────────────────────
        if (assignTarget != null) {
            PadPickerOverlay(
                audioName   = assignTarget!!.name,
                currentPad  = assignTarget!!.assignedPad,
                kitIndex = currentKit,
                onPick      = { padIndex ->
                    val oldPad = assignTarget!!.assignedPad
                    // Unassign old audio from that pad first
                    AudioRepository.audios
                        .filter {
                            it.assignedKit == currentKit &&
                                    it.assignedPad == padIndex &&
                                    it.id != assignTarget!!.id
                        }
                        .forEach { it.assignedPad = -1 }
                    AudioRepository.assignPadToKit(
                        assignTarget!!.id,
                        padIndex,
                        currentKit

                    )
                    if (oldPad >= 0 && oldPad != padIndex) {

                        val oldDefaultResId = defaultResIdFor(oldPad)


                        DrumEngine.invalidatePad(oldPad)
                        DrumEngine.invalidatePad(padIndex)
                        DrumEngine.loadPad(
                            context,
                            currentKit,
                            oldPad,
                            oldDefaultResId
                        )
                    }
                    val defaultResId = defaultResIdFor(padIndex)
                    DrumEngine.invalidatePad(padIndex)
                    DrumEngine.loadPad(
                        context,
                        currentKit,
                        padIndex,
                        defaultResId
                    )
                    assignTarget = null
                    refresh()
                },
                onUnassign  = {
                    val oldPad = assignTarget!!.assignedPad
                    AudioRepository.assignPadToKit(
                        assignTarget!!.id,
                        -1,
                        currentKit
                    )
                    if (oldPad >= 0) {

                        val defaultResId = defaultResIdFor(oldPad)
                        DrumEngine.invalidatePad(oldPad)
                        DrumEngine.loadPad(
                            context,
                            currentKit,
                            oldPad,
                            defaultResId
                        )
                    }

                    assignTarget = null
                    refresh()
                },
                onDismiss   = { assignTarget = null }
            )
        }
    }
}

// ── Pad picker bottom sheet ───────────────────────────────────────────────────

@Composable
private fun PadPickerOverlay(
    audioName: String,
    currentPad: Int,
    kitIndex: Int,
    onPick: (Int) -> Unit,
    onUnassign: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Color(0xFF111111))
                .padding(20.dp)
                .pointerInput(Unit) { detectTapGestures { /* consume */ } },
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Assign \"$audioName\" to pad",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            // 2 rows of 4 pads
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 0..3) {
                        PadPickerBtn(
                            label     = "PAD ${i + 1}",
                            selected  = currentPad == i,

                            occupied =
                                AudioRepository.audioForPad(
                                    kitIndex,
                                    i
                                ) != null && currentPad != i,

                            modifier  = Modifier.weight(1f),
                            onClick   = { onPick(i) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 4..7) {
                        PadPickerBtn(
                            label     = "PAD ${i + 1}",
                            selected  = currentPad == i,
                            occupied =
                                AudioRepository.audioForPad(
                                    kitIndex,
                                    i
                                ) != null && currentPad != i,
                            modifier  = Modifier.weight(1f),
                            onClick   = { onPick(i) }
                        )
                    }
                }
            }

            // Unassign + Dismiss
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentPad >= 0) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF330000))
                            .pointerInput(Unit) { detectTapGestures { onUnassign() } }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Unassign",
                            color = Color(0xFFFF6666),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A))
                        .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Cancel",
                        color = Color(0xFFCCCCCC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PadPickerBtn(
    label: String,
    selected: Boolean,
    occupied: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> Color(0xFF001A2E)
                    occupied -> Color(0xFF1A1A00)
                    else     -> Color(0xFF222222)
                }
            )
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = when {
                    selected -> BtnActive
                    occupied -> Color(0xFFAA8800)
                    else     -> Color(0xFF888888)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            if (occupied) {
                Text("in use", color = Color(0xFF665500), fontSize = 8.sp)
            }
        }
    }
}
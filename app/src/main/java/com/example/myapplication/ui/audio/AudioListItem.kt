package com.example.myapplication.ui.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.BtnActive
import com.example.myapplication.ui.NavRed

/**
 * AudioListItem — one row in the AudioListScreen.
 *
 * Shows audio name, duration, assigned pad badge,
 * and Play / Delete action buttons.
 *
 * @param audio         The AudioItem to display
 * @param isPlaying     True if this item is currently playing
 * @param onPlay        Called when play/stop is tapped
 * @param onDelete      Called when delete is tapped
 * @param onAssignPad   Called when user taps "Assign" → opens pad picker
 */
@Composable
fun AudioListItem(
    audio: AudioItem,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onAssignPad: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E1E))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Play / Stop button ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(50))
                .background(if (isPlaying) Color(0xFF003333) else Color(0xFF222222))
                .pointerInput(Unit) { detectTapGestures { onPlay() } },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text      = if (isPlaying) "⏹" else "▶",
                color     = if (isPlaying) BtnActive else Color(0xFF888888),
                fontSize  = 14.sp
            )
        }

        // ── Name + duration ────────────────────────────────────────────────────
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text     = audio.name,
                color    = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text     = audio.durationMs.toTimeStr(),
                color    = Color(0xFF666666),
                fontSize = 10.sp
            )
        }

        // ── Pad assignment badge ───────────────────────────────────────────────
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (audio.assignedPad >= 0) Color(0xFF001A2E) else Color(0xFF222222)
                )
                .pointerInput(Unit) { detectTapGestures { onAssignPad() } }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                text = if (audio.assignedPad >= 0) "PAD ${audio.assignedPad + 1}" else "ASSIGN",
                color = if (audio.assignedPad >= 0) BtnActive else Color(0xFF555555),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // ── Delete button ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2A1010))
                .pointerInput(Unit) { detectTapGestures { onDelete() } },
            contentAlignment = Alignment.Center
        ) {
            Text("🗑", fontSize = 13.sp)
        }
    }
}

// ── Helper extension ──────────────────────────────────────────────────────────

internal fun Long.toTimeStr(): String {
    val totalSec = this / 1000
    val min      = totalSec / 60
    val sec      = totalSec % 60
    val ms       = (this % 1000) / 10
    return if (min > 0) "%d:%02d".format(min, sec)
    else "%d.%02ds".format(sec, ms)
}

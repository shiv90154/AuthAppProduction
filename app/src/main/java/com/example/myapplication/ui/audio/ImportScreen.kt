package com.example.myapplication.ui.audio

import android.Manifest
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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

/**
 * @param targetPad When set (opened directly from a pad's long-press menu),
 * a successful import is assigned straight to that pad — skipping the
 * separate Audios-screen "tap item, then tap a pad" detour entirely. When
 * null (opened from SETTINGS → IMPORT), behaves exactly as before: the file
 * is just added to the library, unassigned, for later manual assignment.
 * [targetPadDefaultResId] is that pad's real factory resource id (needed to
 * reload the OTHER pad correctly if this import overwrites its assignment —
 * matches the pattern in AudioListScreen; -1 if the pad has no factory sound).
 */
@Composable
fun ImportScreen(
    onClose: () -> Unit,
    currentKit: Int = 0,
    targetPad: Int? = null,
    targetPadDefaultResId: Int = -1
) {
    val context = LocalContext.current

    // One-tap assign: called after a successful import when this screen was
    // opened for a specific pad.
    fun assignToTargetPad(audioId: Long) {
        val pad = targetPad ?: return
        AudioRepository.assignPadToKit(audioId, pad, currentKit)
        DrumEngine.invalidatePad(pad)
        DrumEngine.loadPad(context, currentKit, pad, targetPadDefaultResId)
    }

    var pendingVideoUri by remember { mutableStateOf<Uri?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var pendingLaunch by remember { mutableStateOf<String?>(null) }

    // FIX 2: Continuous slider — any value from 5s to 30s, no fixed steps
    var maxDurationSec by remember { mutableStateOf(30f) }
    val maxDurationMs by remember { derivedStateOf { (maxDurationSec * 1000).toLong() } }

    // ── Audio file picker ─────────────────────────────────────────────────────
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            if (durationMs > maxDurationMs) {
                statusMsg =
                    "Audio is ${durationMs / 1000}s — longer than your ${maxDurationSec.toInt()}s limit."
                isError = true
                return@rememberLauncherForActivityResult
            }

            val name = context.contentResolver
                .query(uri, null, null, null, null)
                ?.use { cursor ->
                    val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    if (col >= 0) cursor.getString(col) else "audio_${System.currentTimeMillis()}"
                } ?: "audio_${System.currentTimeMillis()}"

            val newId = System.currentTimeMillis()
            AudioRepository.add(
                AudioItem(
                    id = newId,
                    name = name.substringBeforeLast("."),
                    uri = uri,
                    durationMs = durationMs
                )
            )
            if (targetPad != null) {
                assignToTargetPad(newId)
                statusMsg = "\"${name.substringBeforeLast(".")}\" imported to Pad ${targetPad + 1}!"
            } else {
                statusMsg = "\"${name.substringBeforeLast(".")}\" imported!"
            }
            isError = false
        } catch (e: Exception) {
            statusMsg = "Failed to read audio: ${e.message}"
            isError = true
        } finally {
            retriever.release()
        }
    }

    // ── Video file picker ─────────────────────────────────────────────────────
    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        pendingVideoUri = uri
    }

    // ── Runtime permissions ───────────────────────────────────────────────────
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        if (granted) {
            permissionDenied = false
            when (pendingLaunch) {
                "audio" -> audioLauncher.launch("audio/*")
                "video" -> videoLauncher.launch("video/*")
            }
            pendingLaunch = null
        } else {
            permissionDenied = true
            statusMsg = "Storage permission denied. Please allow it in App Settings."
            isError = true
        }
    }

    fun requestAndLaunch(type: String) {
        pendingLaunch = type
        statusMsg = null
        permissionLauncher.launch(permissions)
    }

    // ── AudioTrimmer overlay ──────────────────────────────────────────────────
    if (pendingVideoUri != null) {
        AudioTrimmer(
            videoUri = pendingVideoUri!!,
            maxTrimMs = maxDurationMs,
            onDone = { file ->

                // Get ACTUAL extracted audio duration
                val retriever = MediaMetadataRetriever()

                val duration = try {
                    retriever.setDataSource(file.absolutePath)

                    retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L

                } finally {
                    retriever.release()
                }

                val newId = System.currentTimeMillis()
                AudioRepository.add(
                    AudioItem(
                        id = newId,
                        name = file.nameWithoutExtension,
                        uri = Uri.fromFile(file),
                        durationMs = duration   // <-- Actual duration
                    )
                )

                pendingVideoUri = null

                if (targetPad != null) {
                    assignToTargetPad(newId)
                    statusMsg = "Audio extracted and imported to Pad ${targetPad + 1}!"
                } else {
                    statusMsg = "Audio extracted and imported!"
                }

                isError = false
            },
            onCancel = { pendingVideoUri = null }
        )
        return
    }

    // ── Main UI ───────────────────────────────────────────────────────────────
    // FIX 1 + 3: Outer Box intercepts ALL touch events — nothing leaks to pads
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        // FIX 1: Scrollable column so content is accessible on small screens
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(20.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(PanelBg)
                .padding(20.dp)
                // FIX 1: verticalScroll makes panel scrollable
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "IMPORT AUDIO",
                    color = BtnActive, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
                // FIX 3: Close button also consumes its own tap
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2A2A2A))
                        .pointerInput(Unit) {
                            detectTapGestures { onClose() }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                }
            }

            // ── FIX 2: Continuous duration slider (no steps) ─────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Max duration",
                        color = Color(0xFF888888), fontSize = 11.sp
                    )
                    Text(
                        // Show decimal only when not a whole number
                        if (maxDurationSec % 1f == 0f)
                            "${maxDurationSec.toInt()}s"
                        else
                            "${"%.1f".format(maxDurationSec)}s",
                        color = BtnActive, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                // FIX 2: steps = 0 → fully continuous slider
                Slider(
                    value = maxDurationSec,
                    onValueChange = { maxDurationSec = it },
                    valueRange = 5f..30f,
                    steps = 0,   // 0 = continuous, drag freely
                    colors = SliderDefaults.colors(
                        thumbColor = BtnActive,
                        activeTrackColor = BtnActive,
                        inactiveTrackColor = Color(0xFF333333)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                    // FIX 3: Slider needs its own pointer handling
                    // (Slider internally handles this, so no extra needed)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("5s", color = Color(0xFF555555), fontSize = 9.sp)
                    Text("15s", color = Color(0xFF555555), fontSize = 9.sp)
                    Text("30s", color = Color(0xFF555555), fontSize = 9.sp)
                }
            }

            Text(
                "Drag slider to set max clip length (5s – 30s)",
                color = Color(0xFF555555), fontSize = 10.sp
            )

            // ── Audio option ─────────────────────────────────────────────────
            ImportOptionCard(
                icon = "🎵",
                title = "Audio File",
                sub = "mp3 · wav · ogg · m4a · flac  (max ${
                    if (maxDurationSec % 1f == 0f) "${maxDurationSec.toInt()}s"
                    else "${"%.1f".format(maxDurationSec)}s"
                })",
                color = BtnActive
            ) { requestAndLaunch("audio") }

            // ── Video option ─────────────────────────────────────────────────
            ImportOptionCard(
                icon = "🎬",
                title = "Video File",
                sub = "mp4 · mkv · mov → select up to ${
                    if (maxDurationSec % 1f == 0f) "${maxDurationSec.toInt()}s"
                    else "${"%.1f".format(maxDurationSec)}s"
                } of audio",
                color = Color(0xFFFF9800)
            ) { requestAndLaunch("video") }

            // ── Permission denied hint ────────────────────────────────────────
            if (permissionDenied) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A1500))
                        .padding(12.dp)
                ) {
                    Text(
                        "Settings → Apps → MyApplication → Permissions → Storage → Allow",
                        color = Color(0xFFFFAA44), fontSize = 10.sp
                    )
                }
            }

            // ── Status message ────────────────────────────────────────────────
            if (statusMsg != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isError) Color(0xFF330000) else Color(0xFF003322))
                        .padding(12.dp)
                ) {
                    Text(
                        statusMsg!!,
                        color = if (isError) Color(0xFFFF6666) else Color(0xFF66FFAA),
                        fontSize = 11.sp
                    )
                }
            }

            val count = AudioRepository.getAll().size
            if (count > 0) {
                Text(
                    "$count audio${if (count > 1) "s" else ""} imported. Check Audios tab.",
                    color = Color(0xFF555555),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Bottom padding for scroll breathing room
            Spacer(Modifier.height(16.dp))
        }
    }
}
// ── Reusable card ─────────────────────────────────────────────────────────────

@Composable
private fun ImportOptionCard(
    icon: String, title: String, sub: String, color: Color, onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            // FIX 3: Each card fully consumes its own tap
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(icon, fontSize = 28.sp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(sub,   color = Color(0xFF666666), fontSize = 10.sp)
        }
    }
}
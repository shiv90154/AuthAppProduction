package com.example.myapplication.ui.audio

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
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

/**
 * ExportScreen — select audios and share via Android share sheet.
 *
 * FIX 2: Crash fix — content:// URIs already have permission flags,
 * file:// URIs are wrapped safely. Also outer Box consumes pointer
 * events so pads behind don't get triggered.
 */
@Composable
fun ExportScreen(onClose: () -> Unit) {
    val context   = LocalContext.current
    val audioList = remember { AudioRepository.getAll() }
    val selected  = remember { mutableStateListOf<Long>() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            // FIX 1 (also here): consume all pointer events — no pad leakthrough
            .pointerInput(Unit) { detectTapGestures { /* consume backdrop */ } }
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
                    "EXPORT AUDIOS",
                    color = BtnActive, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (audioList.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF222222))
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        if (selected.size == audioList.size) selected.clear()
                                        else {
                                            selected.clear()
                                            selected.addAll(audioList.map { it.id })
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                if (selected.size == audioList.size) "Deselect all" else "Select all",
                                color = Color(0xFFAAAAAA), fontSize = 9.sp
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2A2A2A))
                            .pointerInput(Unit) { detectTapGestures { onClose() } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✕", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Empty state ──────────────────────────────────────────────────────
            if (audioList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF161616)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📤", fontSize = 40.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "No audios to export.\nImport some first.",
                            color = Color(0xFF555555), fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF161616)),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(audioList, key = { it.id }) { audio ->
                        val isSelected = audio.id in selected
                        ExportRow(
                            audio      = audio,
                            isSelected = isSelected,
                            onToggle   = {
                                if (isSelected) selected.remove(audio.id)
                                else            selected.add(audio.id)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Share button ──────────────────────────────────────────────────────
            val canExport = selected.isNotEmpty()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (canExport) Color(0xFF003333) else Color(0xFF1A1A1A))
                    .pointerInput(canExport) {
                        detectTapGestures {
                            if (!canExport) return@detectTapGestures

                            val items = audioList.filter { it.id in selected }

                            // FIX 2: file:// URIs → convert to FileProvider URI
                            // content:// URIs → use as-is
                            fun safeUri(uri: Uri): Uri {
                                return if (uri.scheme == "file") {
                                    androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.provider",
                                        java.io.File(uri.path!!)
                                    )
                                } else {
                                    uri // content:// already safe
                                }
                            }

                            try {
                                if (items.size == 1) {
                                    val shareUri = safeUri(items.first().uri)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type  = "audio/*"
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(intent, "Share audio via…").apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                } else {
                                    val uris = ArrayList(items.map { safeUri(it.uri) })
                                    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                        type  = "audio/*"
                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(intent, "Share ${items.size} audios via…").apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                            } catch (e: Exception) {
                                // Silently ignore — show nothing rather than crash
                                android.util.Log.e("ExportScreen", "Share failed: ${e.message}")
                            }
                        }
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        !canExport         -> "Select audios to export"
                        selected.size == 1 -> "Share 1 audio  📤"
                        else               -> "Share ${selected.size} audios  📤"
                    },
                    color      = if (canExport) BtnActive else Color(0xFF444444),
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── Export row ────────────────────────────────────────────────────────────────

@Composable
private fun ExportRow(
    audio: AudioItem,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Color(0xFF1A1A2E) else Color(0xFF1E1E1E))
            .pointerInput(Unit) { detectTapGestures { onToggle() } }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isSelected) BtnActive else Color(0xFF2A2A2A)),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Text("✓", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("🎵", fontSize = 16.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                audio.name,
                color      = if (isSelected) Color.White else Color(0xFFCCCCCC),
                fontSize   = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                audio.durationMs.toTimeStr() +
                        if (audio.assignedPad >= 0) " · PAD ${audio.assignedPad + 1}" else "",
                color    = Color(0xFF555555),
                fontSize = 10.sp
            )
        }
    }
}
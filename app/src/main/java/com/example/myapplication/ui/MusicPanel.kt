package com.example.myapplication.ui

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll


@Composable
fun MusicPanel(
    onImport: () -> Unit,
    onAudios: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onLoadKit: () -> Unit = {},    // NEW
    onMapMidi: () -> Unit = {},
    onBackup: () -> Unit = {},
    // NEW: single-patch import — separate from whole-app BACKUP/RESTORE,
    // adds one shared kit live without needing an app restart.
    onImportPatch: () -> Unit = {},
    onClose: () -> Unit,
    width: androidx.compose.ui.unit.Dp = 170.dp
) {

    // Outer column is NOT scrollable (Modifier.weight below needs a bounded
    // parent) — only the menu-item list scrolls, so CLOSE stays fixed and
    // reachable at the bottom even on short screens or with more items added.
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B1B1B))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        Text(
            text = "SETTINGS",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MenuItem("IMPORT", Color.Cyan)         { onImport() }
            MenuItem("AUDIOS", Color.White)        { onAudios() }
            MenuItem("EXPORT", Color.Yellow)       { onExport() }
            MenuItem("RENAME KIT", Color.Green)    { onRename() }
            MenuItem("LOAD KIT", Color(0xFFFFB74D)) { onLoadKit() }   // NEW
            MenuItem("MAP MIDI", Color.Cyan)       { onMapMidi() }
            MenuItem("BACKUP / RESTORE", Color(0xFF00C853)) { onBackup() }
            MenuItem("IMPORT PATCH", Color(0xFFFF8A65)) { onImportPatch() }
        }

        MenuItem("CLOSE", Color.Red) { onClose() }
    }
}

@Composable
private fun MenuItem(
    text: String,
    color: Color,
    onClick: () -> Unit
) {

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2A2A2A))
            .pointerInput(Unit) {
                detectTapGestures {
                    onClick()
                }
            }
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {

        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

    }
}
package com.example.myapplication.ui.audio

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.example.myapplication.ui.Kit
import com.example.myapplication.ui.KitRepository
import com.example.myapplication.ui.PanelBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LoadKitScreen
 *
 * Opens a SAF folder picker. Scans the folder for audio files (sorted by name).
 * Takes up to 8 files, creates a new kit named after the folder, and assigns
 * each file to pads 1-8 in AudioRepository.
 *
 * @param onKitLoaded  Called with the new kit name when the kit is ready.
 *                     Caller should add it to the kits list and switch to it.
 * @param onClose      Dismiss the screen.
 */
@Composable
fun LoadKitScreen(
    currentKitCount: Int,
    onKitLoaded: (kitName: String, files: List<Pair<Uri, String>>) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var status     by remember { mutableStateOf<String?>(null) }
    var isError    by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var preview    by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    var kitName    by remember { mutableStateOf("") }

    // SAF folder picker
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        // BUG FIX: without this, the folder-tree permission (and every
        // child file URI resolved from it) only lasts for the current
        // process — the pads load fine right now, but on the next app
        // restart, resolving these URIs throws SecurityException and the
        // kit silently comes back with no sound assigned. Same root cause
        // class as the ImportScreen.kt audio-picker fix.
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Not fatal — the kit still loads for this session either way.
        }

        isScanning = true
        status = "Scanning folder…"
        isError = false

        scope.launch {
            try {
                val (name, files) = scanFolderForAudio(context, uri)
                if (files.isEmpty()) {
                    status = "No audio files found in this folder."
                    isError = true
                    isScanning = false
                    return@launch
                }
                kitName = name.ifBlank { "KIT ${"%03d".format(currentKitCount + 1)}" }
                preview = files
                status = "Found ${files.size} file(s). Tap LOAD KIT to assign them."
                isError = false
            } catch (e: Exception) {
                status = "Error: ${e.message}"
                isError = true
            } finally {
                isScanning = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD000000))
            .pointerInput(Unit) { detectTapGestures { /* consume */ } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────
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
                    Text("LOAD KIT FROM FOLDER", color = BtnActive,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Pick a folder — up to 8 audio files become a kit",
                        color = Color(0xFF888888), fontSize = 9.sp)
                }
                Box(
                    modifier = Modifier
                        .size(32.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A))
                        .pointerInput(Unit) { detectTapGestures { onClose() } },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Folder pick button ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF003333))
                    .pointerInput(isScanning) {
                        detectTapGestures {
                            if (!isScanning) folderLauncher.launch(null)
                        }
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isScanning) "Scanning…" else "📁  BROWSE FOLDER",
                    color = BtnActive, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }

            // ── Status ────────────────────────────────────────────────────
            if (status != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isError) Color(0xFF330000) else Color(0xFF003322))
                        .padding(10.dp)
                ) {
                    Text(status!!, color = if (isError) Color(0xFFFF6666) else Color(0xFF66FFAA),
                        fontSize = 10.sp)
                }
            }

            // ── Preview list ──────────────────────────────────────────────
            if (preview.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("FILES TO ASSIGN:", color = Color(0xFF888888),
                    fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.height(4.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF161616)),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    itemsIndexed(preview) { idx, (_, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E1E1E))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF2A2A2A)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("P${idx + 1}", color = BtnActive,
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(name, color = Color(0xFFCCCCCC), fontSize = 11.sp,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ── LOAD KIT button ───────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF004422))
                        .pointerInput(Unit) {
                            detectTapGestures {
                                onKitLoaded(kitName, preview)
                            }
                        }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("LOAD  \"$kitName\"  (${preview.size} pads)",
                        color = BtnActive, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else if (!isScanning) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No folder selected yet.\nTap BROWSE FOLDER to start.",
                        color = Color(0xFF555555), fontSize = 12.sp,
                        textAlign = TextAlign.Center)
                }
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ── SAF folder scanner ────────────────────────────────────────────────────────

private suspend fun scanFolderForAudio(
    context: Context,
    folderUri: Uri
): Pair<String, List<Pair<Uri, String>>> = withContext(Dispatchers.IO) {

    // Resolve the children URI from the folder URI
    val docId   = DocumentsContract.getTreeDocumentId(folderUri)
    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)

    val audioMimes = setOf(
        "audio/mpeg", "audio/wav", "audio/x-wav", "audio/ogg",
        "audio/mp4", "audio/m4a", "audio/aac", "audio/flac",
        "audio/x-flac", "audio/3gpp"
    )

    val files = mutableListOf<Pair<Uri, String>>()
    val folderName = resolveFolderName(context, folderUri)

    val cursor = context.contentResolver.query(
        childUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ),
        null, null, "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} ASC"
    ) ?: return@withContext Pair(folderName, emptyList())

    cursor.use {
        while (it.moveToNext() && files.size < 8) {
            val docIdChild = it.getString(0) ?: continue
            val displayName = it.getString(1) ?: continue
            val mime = it.getString(2) ?: continue

            if (mime !in audioMimes && !displayName.matches(Regex(".*\\.(mp3|wav|ogg|m4a|aac|flac|3gp)$", RegexOption.IGNORE_CASE))) continue

            val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docIdChild)
            val nameNoExt = displayName.substringBeforeLast(".")
            files.add(Pair(fileUri, nameNoExt))
        }
    }

    Pair(folderName, files)
}

private fun resolveFolderName(context: Context, uri: Uri): String {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        docId.substringAfterLast("/").substringAfterLast(":").ifBlank { "New Kit" }
    } catch (e: Exception) {
        "New Kit"
    }
}

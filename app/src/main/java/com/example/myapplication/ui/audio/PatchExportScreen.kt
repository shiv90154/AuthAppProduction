package com.example.myapplication.ui.audio

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.core.content.FileProvider
import com.example.myapplication.ui.BtnActive
import com.example.myapplication.ui.KitRepository
import com.example.myapplication.ui.PanelBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PatchExportScreen — export/share a SINGLE kit ("patch"): every per-pad
 * setting plus any custom (non-factory) audio assigned to it, as one small
 * .zip. Same zip shape as BackupScreen's whole-app backup (a JSON manifest +
 * audio/<id>.dat entries) but scoped to one kit — "patch.json" instead of
 * "backup.json", and only that kit's audio.
 *
 * NOTE: reads the kit from KitRepository.load() (persisted storage), so the
 * caller must persistKits() first if the kit was just edited — same
 * convention BackupScreen's whole-app export already relies on.
 */
@Composable
fun PatchExportScreen(kitIndex: Int, kitName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val safeFileName = remember(kitName) {
        kitName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "patch" }
    }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { writePatchZip(context, kitIndex, it) }
                        ?: throw IllegalStateException("Could not open destination file")
                }
            }
            busy = false
            result.onSuccess { count ->
                statusMsg = "Patch saved — $count custom sound(s) included."
                isError = false
            }.onFailure {
                statusMsg = "Save failed: ${it.message}"
                isError = true
            }
        }
    }

    fun share() {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cacheFile = File(context.cacheDir, "patch_${System.currentTimeMillis()}.zip")
                    cacheFile.outputStream().use { writePatchZip(context, kitIndex, it) }
                    cacheFile
                }
            }
            busy = false
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share patch"))
            }.onFailure {
                statusMsg = "Share failed: ${it.message}"
                isError = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(24.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PanelBg)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("EXPORT PATCH", color = BtnActive, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "\"$kitName\" — every pad setting and custom sound in this one kit.",
                color = Color(0xFFAAAAAA), fontSize = 10.sp, textAlign = TextAlign.Center
            )

            PatchActionRow("SHARE", Color(0xFF003333), BtnActive, enabled = !busy) { share() }
            PatchActionRow("SAVE TO DEVICE", Color(0xFF332200), Color(0xFFFFB74D), enabled = !busy) {
                createLauncher.launch("$safeFileName.octapadpatch.zip")
            }

            if (busy) Text("Working…", color = Color(0xFF888888), fontSize = 10.sp)
            statusMsg?.let {
                Text(
                    it,
                    color = if (isError) Color(0xFFFF5252) else Color(0xFF00C853),
                    fontSize = 10.sp, textAlign = TextAlign.Center
                )
            }

            PatchActionRow("CLOSE", Color(0xFF330000), Color(0xFFFF4444), enabled = true) { onClose() }
        }
    }
}

@Composable
private fun PatchActionRow(label: String, bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) bg else Color(0xFF1A1A1A))
            .pointerInput(enabled) { if (enabled) detectTapGestures { onClick() } }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) fg else Color(0xFF444444), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Zip writer (shared by SAVE and SHARE) ───────────────────────────────────

private fun writePatchZip(context: android.content.Context, kitIndex: Int, out: OutputStream): Int {
    var audioCount = 0
    ZipOutputStream(out).use { zip ->
        val entry = KitRepository.load().getOrNull(kitIndex)
            ?: throw IllegalStateException("Kit not found")

        val audiosJson = JSONArray()
        AudioRepository.getAll().filter { it.assignedKit == kitIndex }.forEach { item ->
            val entryObj = JSONObject()
            entryObj.put("id", item.id)
            entryObj.put("name", item.name)
            entryObj.put("durationMs", item.durationMs)
            entryObj.put("assignedPad", item.assignedPad)

            val opened = runCatching { context.contentResolver.openInputStream(item.uri) }.getOrNull()
            if (opened != null) {
                val zipEntryName = "audio/${item.id}.dat"
                opened.use { input ->
                    zip.putNextEntry(ZipEntry(zipEntryName))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
                entryObj.put("zipEntry", zipEntryName)
                audioCount++
            }
            audiosJson.put(entryObj)
        }

        val root = JSONObject()
        root.put("version", 1)
        root.put("kit", KitRepository.toJson(entry))
        root.put("audios", audiosJson)

        zip.putNextEntry(ZipEntry("patch.json"))
        zip.write(root.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    return audioCount
}

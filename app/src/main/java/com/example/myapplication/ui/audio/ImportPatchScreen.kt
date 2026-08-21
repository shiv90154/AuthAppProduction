package com.example.myapplication.ui.audio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.myapplication.ui.KitRepository
import com.example.myapplication.ui.PanelBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** One audio file pulled out of an imported patch zip, still needing to be
 *  turned into an AudioItem by the caller (which owns the live kit list). */
data class ImportedPatchAudio(
    val padIndex: Int,
    val file: File,
    val name: String,
    val durationMs: Long
)

/**
 * ImportPatchScreen — picks a single-patch .zip (written by
 * PatchExportScreen) and hands the parsed KitEntry + extracted audio back to
 * the caller, which creates the new kit live (same pattern LoadKitScreen's
 * onKitLoaded already uses) — no app restart needed, unlike whole-app
 * Restore Backup.
 */
@Composable
fun ImportPatchScreen(
    onPatchImported: (KitRepository.KitEntry, List<ImportedPatchAudio>) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { readPatchZip(context, uri) } }
            busy = false
            result.onSuccess { (entry, audios) ->
                statusMsg = "Loaded \"${entry.name}\" — ${audios.size} custom sound(s)."
                isError = false
                onPatchImported(entry, audios)
            }.onFailure {
                statusMsg = "Import failed: ${it.message}"
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("IMPORT PATCH", color = BtnActive, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "Pick a patch file someone shared with you — it's added as a new kit.",
                color = Color(0xFFAAAAAA), fontSize = 10.sp, textAlign = TextAlign.Center
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (!busy) Color(0xFF003333) else Color(0xFF1A1A1A))
                    .pointerInput(busy) {
                        if (!busy) detectTapGestures {
                            openLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("PICK PATCH FILE", color = if (!busy) BtnActive else Color(0xFF444444), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            if (busy) Text("Working…", color = Color(0xFF888888), fontSize = 10.sp)
            statusMsg?.let {
                Text(
                    it,
                    color = if (isError) Color(0xFFFF5252) else Color(0xFF00C853),
                    fontSize = 10.sp, textAlign = TextAlign.Center
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF330000))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("CLOSE", color = Color(0xFFFF4444), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun readPatchZip(
    context: android.content.Context,
    srcUri: Uri
): Pair<KitRepository.KitEntry, List<ImportedPatchAudio>> {
    val importDir = File(context.filesDir, "imported_patch_audio").apply { mkdirs() }
    var patchJson: JSONObject? = null
    val extractedFiles = mutableMapOf<String, File>() // zipEntry -> file on disk

    context.contentResolver.openInputStream(srcUri)?.use { input ->
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "patch.json") {
                    patchJson = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                } else if (entry.name.startsWith("audio/")) {
                    // Unique filename per import so re-importing the same
                    // patch twice (or two different patches) never collide.
                    val outFile = File(importDir, "${System.currentTimeMillis()}_${entry.name.substringAfterLast("/")}")
                    outFile.outputStream().use { fos -> zip.copyTo(fos) }
                    extractedFiles[entry.name] = outFile
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } ?: throw IllegalStateException("Could not open patch file")

    val root = patchJson ?: run {
        extractedFiles.values.forEach { it.delete() }
        throw IllegalStateException("Not a valid patch file")
    }
    val kitObj = root.optJSONObject("kit") ?: run {
        extractedFiles.values.forEach { it.delete() }
        throw IllegalStateException("Not a valid patch file")
    }
    val entry = try {
        KitRepository.fromJson(kitObj)
    } catch (e: Exception) {
        extractedFiles.values.forEach { it.delete() }
        throw IllegalStateException("Not a valid patch file", e)
    }

    val audios = mutableListOf<ImportedPatchAudio>()
    val audiosJson = root.optJSONArray("audios")
    if (audiosJson != null) {
        for (i in 0 until audiosJson.length()) {
            val obj = audiosJson.getJSONObject(i)
            val zipEntry = obj.optString("zipEntry", "")
            val file = if (zipEntry.isNotEmpty()) extractedFiles[zipEntry] else null
            val padIndex = obj.optInt("assignedPad", -1)
            if (file != null && padIndex in 0..7) {
                audios.add(
                    ImportedPatchAudio(
                        padIndex = padIndex,
                        file = file,
                        name = obj.optString("name", file.nameWithoutExtension),
                        durationMs = obj.optLong("durationMs", 0L)
                    )
                )
            }
        }
    }

    return entry to audios
}

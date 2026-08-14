package com.example.myapplication.ui.audio

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
import com.example.myapplication.CcMapRepository
import com.example.myapplication.MidiLearnRepository
import com.example.myapplication.ui.BtnActive
import com.example.myapplication.ui.KitRepository
import com.example.myapplication.ui.PanelBg
import com.example.myapplication.ui.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * BackupScreen — bundles every repository (kits, prefs, MIDI CC map, MIDI
 * learn mappings) plus every reachable custom audio file into one .zip, and
 * restores the same way. Whole-app "Export / Backup" + "Restore Backup" +
 * "Import / Export Settings" from the spec, all in one file since they share
 * the exact same payload.
 */
@Composable
fun BackupScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { writeBackup(context, uri) } }
            busy = false
            result.onSuccess { count ->
                statusMsg = "Backup saved — $count audio file(s) included."
                isError = false
            }.onFailure {
                statusMsg = "Backup failed: ${it.message}"
                isError = true
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { restoreBackup(context, uri) } }
            busy = false
            result.onSuccess { count ->
                statusMsg = "Restored $count audio file(s). Close and reopen the app to see the restored kits."
                isError = false
            }.onFailure {
                statusMsg = "Restore failed: ${it.message}"
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
            Text("BACKUP & RESTORE", color = BtnActive, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "Saves every kit, pad setting, MIDI mapping and custom sound into one file.",
                color = Color(0xFFAAAAAA), fontSize = 10.sp, textAlign = TextAlign.Center
            )

            ActionRow("EXPORT / BACKUP", Color(0xFF003333), BtnActive, enabled = !busy) {
                createLauncher.launch("octapad_backup_${System.currentTimeMillis()}.zip")
            }
            ActionRow("RESTORE BACKUP", Color(0xFF332200), Color(0xFFFFB74D), enabled = !busy) {
                openLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }

            if (busy) {
                Text("Working…", color = Color(0xFF888888), fontSize = 10.sp)
            }
            statusMsg?.let {
                Text(
                    it,
                    color = if (isError) Color(0xFFFF5252) else Color(0xFF00C853),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
            }

            ActionRow("CLOSE", Color(0xFF330000), Color(0xFFFF4444), enabled = true) { onClose() }
        }
    }
}

@Composable
private fun ActionRow(label: String, bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit) {
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

// ── Export ───────────────────────────────────────────────────────────────────

private fun writeBackup(context: android.content.Context, destUri: Uri): Int {
    var audioCount = 0
    context.contentResolver.openOutputStream(destUri)?.use { out ->
        ZipOutputStream(out).use { zip ->
            val audiosJson = JSONArray()

            AudioRepository.getAll().forEach { item ->
                val entryObj = JSONObject()
                entryObj.put("id", item.id)
                entryObj.put("name", item.name)
                entryObj.put("durationMs", item.durationMs)
                entryObj.put("assignedPad", item.assignedPad)
                entryObj.put("assignedKit", item.assignedKit)

                val opened = runCatching { context.contentResolver.openInputStream(item.uri) }.getOrNull()
                if (opened != null) {
                    val entryName = "audio/${item.id}.dat"
                    opened.use { input ->
                        zip.putNextEntry(ZipEntry(entryName))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                    entryObj.put("zipEntry", entryName)
                    audioCount++
                }
                audiosJson.put(entryObj)
            }

            val root = JSONObject()
            root.put("version", 1)
            root.put("kits", KitRepository.exportBackup())
            root.put("preferences", PreferencesRepository.exportBackup())
            CcMapRepository.exportBackup()?.let { root.put("ccMap", it) }
            MidiLearnRepository.exportBackup()?.let { root.put("midiNoteMap", it) }
            root.put("audios", audiosJson)

            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(root.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    } ?: throw IllegalStateException("Could not open destination file")
    return audioCount
}

// ── Restore ──────────────────────────────────────────────────────────────────

private fun restoreBackup(context: android.content.Context, srcUri: Uri): Int {
    val restoredDir = File(context.filesDir, "restored_audio").apply { mkdirs() }
    var backupJson: JSONObject? = null
    val extractedFiles = mutableMapOf<String, File>() // zipEntry -> file on disk

    context.contentResolver.openInputStream(srcUri)?.use { input ->
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "backup.json") {
                    backupJson = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                } else if (entry.name.startsWith("audio/")) {
                    val outFile = File(restoredDir, entry.name.substringAfterLast("/"))
                    outFile.outputStream().use { fos -> zip.copyTo(fos) }
                    extractedFiles[entry.name] = outFile
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } ?: throw IllegalStateException("Could not open backup file")

    val root = backupJson ?: run {
        // Nothing usable was extracted — don't leave the audio files behind.
        extractedFiles.values.forEach { it.delete() }
        throw IllegalStateException("backup.json missing from zip")
    }

    try {
        root.optJSONObject("kits")?.let { KitRepository.importBackup(it) }
        root.optJSONObject("preferences")?.let { PreferencesRepository.importBackup(it) }
        if (root.has("ccMap")) CcMapRepository.importBackup(root.getString("ccMap"))
        if (root.has("midiNoteMap")) MidiLearnRepository.importBackup(root.getString("midiNoteMap"))

        var restoredCount = 0
        val audiosJson = root.optJSONArray("audios")
        if (audiosJson != null) {
            val items = mutableListOf<AudioItem>()
            for (i in 0 until audiosJson.length()) {
                val obj = audiosJson.getJSONObject(i)
                val zipEntry = obj.optString("zipEntry", "")
                val file = if (zipEntry.isNotEmpty()) extractedFiles[zipEntry] else null
                if (file != null) {
                    val item = AudioItem(
                        id = obj.getLong("id"),
                        name = obj.getString("name"),
                        uri = Uri.fromFile(file),
                        durationMs = obj.getLong("durationMs")
                    )
                    item.assignedPad = obj.optInt("assignedPad", -1)
                    item.assignedKit = obj.optInt("assignedKit", 0)
                    items.add(item)
                    restoredCount++
                }
            }
            AudioRepository.replaceAll(items)
        }

        return restoredCount
    } catch (e: Exception) {
        // A partially/incompatible backup (e.g. invalid kit data) must not
        // leave orphaned extracted audio files behind on disk.
        extractedFiles.values.forEach { it.delete() }
        throw e
    }
}

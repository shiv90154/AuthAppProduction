package com.example.myapplication.ui.audio
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * AudioItem — represents one imported audio clip.
 *
 * @param id        Unique id (System.currentTimeMillis())
 * @param name      Display name (filename without extension)
 * @param uri       Content URI pointing to the audio file in app storage
 * @param durationMs Duration in milliseconds (max 30_000)
 * @param assignedPad  Which pad index (0-7) this audio is assigned to, -1 = unassigned
 */
data class AudioItem(
    val id: Long,
    val name: String,
    val uri: Uri,
    val durationMs: Long,

    ) {
    var assignedPad by mutableIntStateOf(-1)
    var assignedKit by mutableIntStateOf(0)
}

/**
 * AudioRepository — single in-memory store shared across all audio screens.
 *
 * Kept as an object (singleton) so ImportScreen, AudioListScreen,
 * and ExportScreen all see the same list without passing it through
 * every composable.
 */
object AudioRepository {
    val audios = mutableStateListOf<AudioItem>()

    // ── NEW: Persistence support ────────────────────────────────────────────
    private const val PREFS_NAME = "audio_repository_prefs"
    private const val KEY_AUDIOS = "saved_audios"
    private var appContext: Context? = null

    /** Call this ONCE, early in app lifecycle (e.g. OctapadScreen's LaunchedEffect(Unit)) */
    fun init(context: Context) {
        if (appContext != null) return // already initialized, avoid double-load
        appContext = context.applicationContext
        loadFromStorage()
    }

    private fun persist() {
        val ctx = appContext ?: return
        val jsonArray = JSONArray()
        audios.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("uri", item.uri.toString())
            obj.put("durationMs", item.durationMs)
            obj.put("assignedPad", item.assignedPad)
            obj.put("assignedKit", item.assignedKit)
            jsonArray.put(obj)
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUDIOS, jsonArray.toString())
            .apply()
    }

    private fun loadFromStorage() {
        val ctx = appContext ?: return
        val jsonString = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AUDIOS, null) ?: return

        try {
            val jsonArray = JSONArray(jsonString)
            audios.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val item = AudioItem(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    uri = Uri.parse(obj.getString("uri")),
                    durationMs = obj.getLong("durationMs")
                )
                item.assignedPad = obj.getInt("assignedPad")
                item.assignedKit = obj.getInt("assignedKit")
                audios.add(item)
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioRepository", "Failed to load saved audios: ${e.message}")
        }
    }
    // ── END NEW ──────────────────────────────────────────────────────────────

    fun add(item: AudioItem) {
        audios.add(item)
        persist() // ✅ NEW
    }

    fun remove(id: Long) {
        audios.removeAll { it.id == id }
        persist() // ✅ NEW
    }

    fun assignPadToKit(
        id: Long,
        padIndex: Int,
        kitIndex: Int
    ) {

        val item = audios.find { it.id == id } ?: return

        val oldPad = item.assignedPad

        if (oldPad != -1) {
            DrumEngine.invalidatePad(oldPad)
        }

        if (padIndex != -1) {
            DrumEngine.invalidatePad(padIndex)
        }

        audios.forEach {

            if (
                it.id != id &&
                it.assignedKit == kitIndex &&
                it.assignedPad == padIndex &&
                padIndex != -1
            ) {

                it.assignedPad = -1

                DrumEngine.invalidatePad(padIndex)
            }
        }

        item.assignedKit = kitIndex
        item.assignedPad = padIndex

        persist() // ✅ NEW
    }

    /** Returns the AudioItem assigned to [padIndex], or null. */
    fun audioForPad(
        kitIndex: Int,
        padIndex: Int
    ): AudioItem? =
        audios.find {
            it.assignedKit == kitIndex &&
                    it.assignedPad == padIndex
        }

    fun assignRecordedAudio(
        kitIndex: Int,
        padIndex: Int,
        file: java.io.File
    ) {

        // Purani recording remove kar do (same kit + same pad)
        audios.removeAll {
            it.assignedKit == kitIndex &&
                    it.assignedPad == padIndex
        }

        val retriever = MediaMetadataRetriever()

        val duration = try {

            retriever.setDataSource(file.absolutePath)

            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

        } finally {

            retriever.release()
        }

        val item = AudioItem(
            id = System.currentTimeMillis(),
            name = file.nameWithoutExtension,
            uri = Uri.fromFile(file),
            durationMs = duration
        )

        item.assignedPad = padIndex
        item.assignedKit = kitIndex

        try {
            audios.add(item)
            DrumEngine.invalidatePad(padIndex)
            persist() // ✅ NEW
        } catch (e: Exception) {
            throw e
        }
    }


    fun swapPads(
        kitIndex: Int,
        pad1: Int,
        pad2: Int
    ) {

        val audio1 = audioForPad(kitIndex, pad1)
        val audio2 = audioForPad(kitIndex, pad2)

        when {

            // Both pads have audio
            audio1 != null && audio2 != null -> {
                audio1.assignedPad = pad2
                audio2.assignedPad = pad1
            }

            // Only pad1 has audio
            audio1 != null -> {
                audio1.assignedPad = pad2
            }

            // Only pad2 has audio
            audio2 != null -> {
                audio2.assignedPad = pad1
            }
        }

        DrumEngine.invalidatePad(pad1)
        DrumEngine.invalidatePad(pad2)

        persist() // ✅ NEW
    }
    fun getAll(): List<AudioItem> = audios.toList()

    /** Wipes and replaces the whole audio list — used by Restore Backup. */
    fun replaceAll(items: List<AudioItem>) {
        audios.clear()
        audios.addAll(items)
        persist()
    }


    /** Duplicates every AudioItem assigned to [sourceKitIndex] as new items
     *  assigned to [newKitIndex] (same underlying file URI — Kit Copy). */
    fun copyForKit(sourceKitIndex: Int, newKitIndex: Int) {
        val toCopy = audios.filter { it.assignedKit == sourceKitIndex }.toList()
        toCopy.forEachIndexed { i, source ->
            val copy = AudioItem(
                id = System.currentTimeMillis() + i,
                name = source.name,
                uri = source.uri,
                durationMs = source.durationMs
            )
            copy.assignedPad = source.assignedPad
            copy.assignedKit = newKitIndex
            audios.add(copy)
        }
        persist()
    }

    fun unassignPad(id: Long) {
        val item = audios.find { it.id == id } ?: return

        val pad = item.assignedPad
        if (pad != -1) {
            item.assignedPad = -1
            DrumEngine.invalidatePad(pad)
        }

        persist() // ✅ NEW
    }
}
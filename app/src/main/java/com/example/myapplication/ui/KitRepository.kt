package com.example.myapplication.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * KitRepository — persists the kit list (name + which factory kit's raw
 * resources to use, or -1 for a user-created empty kit) so added/deleted/
 * renamed kits survive an app restart instead of always regenerating the
 * original 25 factory kits.
 */
object KitRepository {

    data class KitEntry(
        val name: String,
        val factoryKitNumber: Int,
        val chokeGroups: List<List<Int>> = List(8) { emptyList() },
        val activeChokeLevel: Int = 0,
        val volumes: List<Float> = List(8) { 1f },
        val pitches: List<Float> = List(8) { 1f },
        // Per-pad EQ + level + delay (8 values each)
        val padLevels:  List<Float> = List(8) { 1f },
        val padEqLow:   List<Float> = List(8) { 1f },
        val padEqMid:   List<Float> = List(8) { 1f },
        val padEqHigh:  List<Float> = List(8) { 1f },
        val padDelayMs: List<Int>   = List(8) { 300 },
        val padLengthPct: List<Float> = List(8) { 1f },
        // NEW: non-destructive CROP start handle (fraction 0f..0.95f skipped
        // before playback). Older saved kits without this key default to 0f.
        val padCropStartPct: List<Float> = List(8) { 0f },
        val padReverse: List<Boolean> = List(8) { false },
        val padPlayMode: List<String> = List(8) { "ONESHOT" },
        // NEW: per-pad Pan (-1f..1f, 0f = center) and Gain (0f..2f, 1f = unity)
        val padPan:  List<Float> = List(8) { 0f },
        val padGain: List<Float> = List(8) { 1f },
        // NEW: delay on/off per pad per kit, not a single global toggle
        val padDelayEnabled: List<Boolean> = List(8) { false }
    )

    private const val PREFS_NAME = "kit_repository_prefs"
    private const val KEY_KITS = "saved_kits"
    private const val KEY_LAST_SELECTED_KIT = "last_selected_kit"   // NEW
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ── Single-KitEntry (de)serialization — shared by the whole-list
    // save()/load() below AND single-patch export/import (KitExportScreen). ──

    fun toJson(entry: KitEntry): JSONObject {
        val obj = JSONObject()
        obj.put("name", entry.name)
        obj.put("factoryKitNumber", entry.factoryKitNumber)

        val padsArr = JSONArray()
        entry.chokeGroups.forEach { levels ->
            val levelsArr = JSONArray()
            levels.forEach { levelsArr.put(it) }
            padsArr.put(levelsArr)
        }
        obj.put("chokeGroups", padsArr)
        obj.put("activeChokeLevel", entry.activeChokeLevel)

        val volArr = JSONArray()
        entry.volumes.forEach { volArr.put(it.toDouble()) }
        obj.put("volumes", volArr)

        val pitArr = JSONArray()
        entry.pitches.forEach { pitArr.put(it.toDouble()) }
        obj.put("pitches", pitArr)

        fun putFloatArray(key: String, list: List<Float>) {
            val a = JSONArray(); list.forEach { a.put(it.toDouble()) }; obj.put(key, a)
        }
        fun putIntArray(key: String, list: List<Int>) {
            val a = JSONArray(); list.forEach { a.put(it) }; obj.put(key, a)
        }
        fun putBoolArray(key: String, list: List<Boolean>) {
            val a = JSONArray(); list.forEach { a.put(it) }; obj.put(key, a)
        }
        fun putStringArray(key: String, list: List<String>) {
            val a = JSONArray(); list.forEach { a.put(it) }; obj.put(key, a)
        }
        putFloatArray("padLevels",  entry.padLevels)
        putFloatArray("padEqLow",   entry.padEqLow)
        putFloatArray("padEqMid",   entry.padEqMid)
        putFloatArray("padEqHigh",  entry.padEqHigh)
        putIntArray  ("padDelayMs", entry.padDelayMs)
        putFloatArray("padLengthPct", entry.padLengthPct)
        putFloatArray("padCropStartPct", entry.padCropStartPct)
        putBoolArray ("padReverse", entry.padReverse)
        putStringArray("padPlayMode", entry.padPlayMode)
        putFloatArray("padPan",  entry.padPan)
        putFloatArray("padGain", entry.padGain)
        putBoolArray ("padDelayEnabled", entry.padDelayEnabled)

        return obj
    }

    fun fromJson(obj: JSONObject): KitEntry {
        val chokeGroups: List<List<Int>> = if (obj.has("chokeGroups")) {
            val padsArr = obj.getJSONArray("chokeGroups")
            (0 until padsArr.length()).map { padIdx ->
                val levelsArr = padsArr.getJSONArray(padIdx)
                (0 until levelsArr.length()).map { levelsArr.getInt(it) }
            }
        } else {
            List(8) { emptyList() }
        }

        val volumes: List<Float> = if (obj.has("volumes")) {
            val volArr = obj.getJSONArray("volumes")
            (0 until volArr.length()).map { volArr.getDouble(it).toFloat() }
        } else {
            List(8) { 1f }
        }

        val pitches: List<Float> = if (obj.has("pitches")) {
            val pitArr = obj.getJSONArray("pitches")
            (0 until pitArr.length()).map { pitArr.getDouble(it).toFloat() }
        } else {
            List(8) { 1f }
        }

        fun loadFloatArray(key: String, default: Float): List<Float> =
            if (obj.has(key)) {
                val a = obj.getJSONArray(key)
                (0 until a.length()).map { a.getDouble(it).toFloat() }
            } else List(8) { default }

        fun loadIntArray(key: String, default: Int): List<Int> =
            if (obj.has(key)) {
                val a = obj.getJSONArray(key)
                (0 until a.length()).map { a.getInt(it) }
            } else List(8) { default }

        fun loadBoolArray(key: String, default: Boolean): List<Boolean> =
            if (obj.has(key)) {
                val a = obj.getJSONArray(key)
                (0 until a.length()).map { a.getBoolean(it) }
            } else List(8) { default }

        fun loadStringArray(key: String, default: String): List<String> =
            if (obj.has(key)) {
                val a = obj.getJSONArray(key)
                (0 until a.length()).map { a.getString(it) }
            } else List(8) { default }

        return KitEntry(
            name = obj.getString("name"),
            factoryKitNumber = obj.getInt("factoryKitNumber"),
            chokeGroups = chokeGroups,
            activeChokeLevel = obj.optInt("activeChokeLevel", 0),
            volumes = volumes,
            pitches = pitches,
            padLevels  = loadFloatArray("padLevels",  1f),
            padEqLow   = loadFloatArray("padEqLow",   1f),
            padEqMid   = loadFloatArray("padEqMid",   1f),
            padEqHigh  = loadFloatArray("padEqHigh",  1f),
            padDelayMs = loadIntArray("padDelayMs", 300),
            padLengthPct = loadFloatArray("padLengthPct", 1f),
            padCropStartPct = loadFloatArray("padCropStartPct", 0f),
            padReverse = loadBoolArray("padReverse", false),
            padPlayMode = loadStringArray("padPlayMode", "ONESHOT"),
            padPan  = loadFloatArray("padPan",  0f),
            padGain = loadFloatArray("padGain", 1f),
            padDelayEnabled = loadBoolArray("padDelayEnabled", false)
        )
    }

    fun load(): List<KitEntry> {
        val ctx = appContext ?: return emptyList()
        val jsonString = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_KITS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonString)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            android.util.Log.e("KitRepository", "Failed to load saved kits: ${e.message}")
            emptyList()
        }
    }

    fun save(entries: List<KitEntry>) {
        val ctx = appContext ?: return
        val arr = JSONArray()
        entries.forEach { entry -> arr.put(toJson(entry)) }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_KITS, arr.toString())
            .apply()
    }

    // NEW: remembers which kit index was open when the app was last closed
    fun saveLastSelectedKit(index: Int) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_SELECTED_KIT, index)
            .apply()
    }

    // NEW: returns the last-open kit index, or 0 if none saved yet
    fun loadLastSelectedKit(): Int {
        val ctx = appContext ?: return 0
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_SELECTED_KIT, 0)
    }

    // ── Backup / Restore support ────────────────────────────────────────────
    fun exportBackup(): JSONObject {
        val ctx = appContext
        val obj = JSONObject()
        val raw = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.getString(KEY_KITS, null)
        if (raw != null) obj.put(KEY_KITS, raw)
        obj.put(KEY_LAST_SELECTED_KIT, loadLastSelectedKit())
        return obj
    }

    // Validates the kit payload actually parses into real kit entries WITHOUT
    // writing anything — split out from importBackup() so BackupScreen's
    // restore flow can validate every repository's payload up front before
    // committing any of them, instead of committing kits first and only then
    // discovering a later repository's payload is bad (which used to leave
    // kits replaced by the backup while preferences/CC/MIDI maps stayed old
    // — a hybrid, inconsistent restore).
    fun validateBackupPayload(obj: JSONObject): String? {
        val kitsStr = if (obj.has(KEY_KITS)) obj.getString(KEY_KITS) else null
        if (kitsStr != null) {
            try {
                val arr = JSONArray(kitsStr)
                for (i in 0 until arr.length()) fromJson(arr.getJSONObject(i))
            } catch (e: Exception) {
                throw IllegalStateException("Backup contains invalid kit data", e)
            }
        }
        return kitsStr
    }

    fun importBackup(obj: JSONObject) {
        val ctx = appContext ?: return
        // Validate the kit payload actually parses into real kit entries
        // BEFORE writing anything — a malformed/truncated/incompatible-version
        // backup must fail loudly instead of overwriting the currently saved
        // kits with a string that later fails to parse in load() (which would
        // silently wipe the whole kit list on the next read).
        val kitsStr = validateBackupPayload(obj)
        val edit = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (kitsStr != null) edit.putString(KEY_KITS, kitsStr)
        edit.putInt(KEY_LAST_SELECTED_KIT, obj.optInt(KEY_LAST_SELECTED_KIT, 0))
        edit.apply()
    }
}

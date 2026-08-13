package com.example.myapplication

import android.content.Context
import org.json.JSONObject

/**
 * Persists MIDI CC (Control Change) -> named-target mappings, so a hardware
 * knob/button can be "learned" for any of: VOLUME, PITCH, EQ_LOW, EQ_MID,
 * EQ_HIGH, PATCH_NEXT, PATCH_PREV, EDIT, SAVE.
 *
 * Storage: SharedPreferences key "cc_mappings" -> JSON object { "TARGET": ccNumber }
 */
object CcMapRepository {

    val TARGETS = listOf(
        "VOLUME", "PITCH", "EQ_LOW", "EQ_MID", "EQ_HIGH",
        "PATCH_NEXT", "PATCH_PREV", "EDIT", "SAVE",
        // NEW: for MIDI pad controllers that send Control Change instead of
        // Note-On when a pad is hit (some cheap/generic pad controllers do
        // this instead of the GM drum-note convention the Note-On path
        // assumes). Map each of these via MIDI Learn exactly like any other
        // CC target — hit LEARN, then hit the physical pad.
        "PAD_1", "PAD_2", "PAD_3", "PAD_4", "PAD_5", "PAD_6", "PAD_7", "PAD_8"
    )

    // Preserves the app's original fixed CC numbers as defaults so existing
    // hardware setups keep working without re-learning.
    private val DEFAULTS = mapOf(
        "VOLUME" to 11,
        "PITCH" to 12
    )

    private const val PREFS_NAME = "cc_map_prefs"
    private const val KEY_MAPPINGS = "cc_mappings"
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadRaw(): JSONObject {
        val json = prefs()?.getString(KEY_MAPPINGS, null) ?: return JSONObject()
        return try { JSONObject(json) } catch (e: Exception) { JSONObject() }
    }

    fun save(target: String, ccNumber: Int) {
        val obj = loadRaw()
        obj.put(target, ccNumber)
        prefs()?.edit()?.putString(KEY_MAPPINGS, obj.toString())?.apply()
    }

    fun clear(target: String) {
        val obj = loadRaw()
        obj.remove(target)
        prefs()?.edit()?.putString(KEY_MAPPINGS, obj.toString())?.apply()
    }

    /** CC number bound to [target], or -1 if unmapped. */
    fun getCc(target: String): Int {
        val obj = loadRaw()
        if (obj.has(target)) return obj.optInt(target, -1)
        return DEFAULTS[target] ?: -1
    }

    fun loadAll(): Map<String, Int> =
        TARGETS.associateWith { getCc(it) }

    // ── Backup / Restore support ────────────────────────────────────────────
    fun exportBackup(): String? = prefs()?.getString(KEY_MAPPINGS, null)

    fun importBackup(raw: String?) {
        if (raw == null) return
        prefs()?.edit()?.putString(KEY_MAPPINGS, raw)?.apply()
    }
}

package com.example.myapplication

import android.content.Context
import org.json.JSONObject

/**
 * Persists MIDI Note -> pad mappings (pad 0-7), so a hardware controller
 * that sends Note-On for pad hits (the common case for real drum pad
 * controllers) can have each pad's note re-learned. Coexists with
 * CcMapRepository's PAD_1..PAD_8 CC targets — a pad can be triggered by
 * either, whichever the controller actually sends.
 *
 * Storage: SharedPreferences key "midi_mappings" -> JSON object { "padIndex": note }
 */
object MidiLearnRepository {

    private const val PREFS_NAME = "midi_learn_prefs"
    private const val KEY_MAPPINGS = "midi_mappings"
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

    fun save(padNumber: Int, note: Int) {
        val obj = loadRaw()
        obj.put(padNumber.toString(), note)
        prefs()?.edit()?.putString(KEY_MAPPINGS, obj.toString())?.apply()
    }

    fun clear(padNumber: Int) {
        val obj = loadRaw()
        obj.remove(padNumber.toString())
        prefs()?.edit()?.putString(KEY_MAPPINGS, obj.toString())?.apply()
    }

    /** All saved pad(0-7) -> note mappings — anything not saved keeps native's GM default. */
    fun loadAll(): Map<Int, Int> {
        val obj = loadRaw()
        val result = mutableMapOf<Int, Int>()
        for (pad in 0..7) {
            val key = pad.toString()
            if (obj.has(key)) result[pad] = obj.optInt(key)
        }
        return result
    }

    // ── Backup / Restore support ────────────────────────────────────────────
    fun exportBackup(): String? = prefs()?.getString(KEY_MAPPINGS, null)

    fun importBackup(raw: String?) {
        if (raw == null) return
        prefs()?.edit()?.putString(KEY_MAPPINGS, raw)?.apply()
    }
}

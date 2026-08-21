package com.example.myapplication

import android.content.Context
import org.json.JSONObject

/**
 * Persists MIDI Note -> named-target mappings for button/action controls:
 * PATCH_NEXT, PATCH_PREV, EDIT, SAVE, DELAY_TOGGLE, BANK_A, BANK_B, BANK_AB.
 *
 * These used to be CcMapRepository (CC-learned) targets. Moved to Note-
 * based learn per client request: a CC-learn button doesn't reflect how
 * their hardware actually sends these — a physical button/pad sends a MIDI
 * Note, not a CC — so LEARN here listens for the next Note-On instead of a
 * CC message. VOLUME/PITCH/EQ_LOW/EQ_MID/EQ_HIGH stay in CcMapRepository —
 * those are continuous knob sweeps, which only a CC's 0-127 value stream
 * can drive; a Note-On has no equivalent continuous value.
 *
 * Storage: SharedPreferences key "note_mappings" -> JSON object
 * { "TARGET": noteNumber }. No built-in defaults — a target stays
 * unmapped until explicitly learned once, same as CcMapRepository's
 * PAD_1..PAD_8 used to.
 */
object NoteMapRepository {

    val TARGETS = listOf(
        "PATCH_NEXT", "PATCH_PREV", "EDIT", "SAVE",
        "DELAY_TOGGLE", "BANK_A", "BANK_B", "BANK_AB"
    )

    private const val PREFS_NAME = "note_map_prefs"
    private const val KEY_MAPPINGS = "note_mappings"
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

    fun save(target: String, note: Int) {
        val obj = loadRaw()
        // One note -> at most one target, same reasoning as
        // CcMapRepository.save: strip any other target currently pointing
        // at this same note first, so re-learning it here can't leave two
        // targets silently matching the same incoming Note-On (the first
        // match in the dispatch `when` would keep winning forever).
        TARGETS.forEach { existingTarget ->
            if (existingTarget != target && getNote(existingTarget) == note) {
                obj.put(existingTarget, -1)
            }
        }
        obj.put(target, note)
        prefs()?.edit()?.putString(KEY_MAPPINGS, obj.toString())?.apply()
    }

    fun clear(target: String) {
        val obj = loadRaw()
        obj.remove(target)
        prefs()?.edit()?.putString(KEY_MAPPINGS, obj.toString())?.apply()
    }

    /** Note number bound to [target], or -1 if unmapped. */
    fun getNote(target: String): Int {
        val obj = loadRaw()
        return obj.optInt(target, -1)
    }

    fun loadAll(): Map<String, Int> =
        TARGETS.associateWith { getNote(it) }

    // ── Backup / Restore support ────────────────────────────────────────────
    fun exportBackup(): String? = prefs()?.getString(KEY_MAPPINGS, null)

    /** Throws if [raw] isn't valid JSON; writes nothing either way. */
    fun validateBackupPayload(raw: String) {
        JSONObject(raw)
    }

    fun importBackup(raw: String?) {
        if (raw == null) return
        try {
            validateBackupPayload(raw)
        } catch (e: Exception) {
            throw IllegalStateException("Backup contains invalid Note mapping data", e)
        }
        prefs()?.edit()?.putString(KEY_MAPPINGS, raw)?.apply()
    }
}

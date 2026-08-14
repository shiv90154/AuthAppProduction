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
        // BUG FIX: a CC number is only ever dispatched to ONE target (the
        // first match in OctapadScreen's onControlChange `when`), so leaving
        // a stale mapping from a previous target to this same CC number
        // meant the newly-learned target would show as mapped in the UI but
        // silently never fire — the older target kept winning every time.
        // One CC -> at most one target, so re-learning it here must strip it
        // from wherever it used to point.
        //
        // BUG FIX 2: this used to compare against
        // `obj.optInt(existingTarget, DEFAULTS[existingTarget] ?: -1)` but
        // then clear the collision with `obj.remove(existingTarget)` — a
        // no-op when existingTarget was still on its built-in default (e.g.
        // VOLUME=11) and had never been explicitly saved, since there was no
        // key to remove. Learning PITCH onto CC 11 then still left
        // getCc("VOLUME") returning the default 11 afterward, so both
        // targets matched the same incoming CC and the dispatcher's `when`
        // (which checks VOLUME first) kept firing VOLUME — PITCH showed as
        // "learned" in the UI but silently never fired. Use getCc() (which
        // already applies the same default fallback) for the comparison,
        // and explicitly persist -1 (not remove) so a default-backed
        // collision is actually recorded as unmapped rather than silently
        // falling back to the same default on the next read.
        TARGETS.forEach { existingTarget ->
            if (existingTarget != target && getCc(existingTarget) == ccNumber) {
                obj.put(existingTarget, -1)
            }
        }
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

    /** Throws if [raw] isn't valid JSON; writes nothing either way. */
    fun validateBackupPayload(raw: String) {
        JSONObject(raw)
    }

    fun importBackup(raw: String?) {
        if (raw == null) return
        // BUG FIX: this used to write the backup's raw string straight into
        // SharedPreferences with no validation — the same failure class
        // KitRepository.importBackup() was fixed for. A truncated/corrupted
        // zip entry got written verbatim; the next loadRaw() call then hit
        // its JSONException fallback and returned an empty JSONObject(),
        // silently wiping every saved CC mapping. Parse first and reject
        // (leaving existing mappings untouched) rather than write bad data.
        try {
            validateBackupPayload(raw)
        } catch (e: Exception) {
            throw IllegalStateException("Backup contains invalid CC mapping data", e)
        }
        prefs()?.edit()?.putString(KEY_MAPPINGS, raw)?.apply()
    }
}

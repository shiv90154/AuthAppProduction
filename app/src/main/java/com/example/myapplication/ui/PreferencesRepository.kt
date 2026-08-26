package com.example.myapplication.ui

import android.content.Context

/**
 * PreferencesRepository — persists global app preferences like BPM and
 * exclusive mode so they survive an app restart instead of resetting to
 * defaults.
 */
object PreferencesRepository {

    private const val PREFS_NAME = "preferences"
    private const val KEY_BPM = "bpm"
    private const val KEY_EXCLUSIVE_MODE = "exclusive_mode"
    private const val KEY_LOOP_ENABLED = "loop_enabled"
    private const val KEY_DELAY_ENABLED = "delay_enabled"
    private const val KEY_DELAY_CHOKE_PAD = "delay_choke_pad"
    private const val KEY_DELAY_LEVEL = "delay_level"
    // NEW: global tempo-synced playback-rate multiplier for the LOOP group's
    // SPEED control — separate from BPM (which sets the beat interval); this
    // scales it (0.5x..2x), same idea as a hardware sampler's speed knob.
    private const val KEY_SPEED = "speed"
    private const val KEY_SELECTED_PAD = "selected_pad"
    private const val KEY_VELOCITY_ON = "velocity_on"
    private const val KEY_BANK_MODE = "bank_mode"
    private const val KEY_KIT_B = "kit_b_index"
    private const val KEY_KIT_C = "kit_c_index"
    private const val KEY_MIDI_CHANNEL = "midi_channel"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveBpm(bpm: Int) {
        prefs()?.edit()?.putInt(KEY_BPM, bpm)?.apply()
    }

    fun loadBpm(): Int = prefs()?.getInt(KEY_BPM, 120) ?: 120

    fun saveExclusiveMode(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_EXCLUSIVE_MODE, enabled)?.apply()
    }

    fun loadExclusiveMode(): Boolean = prefs()?.getBoolean(KEY_EXCLUSIVE_MODE, false) ?: false

    fun saveLoopEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_LOOP_ENABLED, enabled)?.apply()
    }

    fun loadLoopEnabled(): Boolean = prefs()?.getBoolean(KEY_LOOP_ENABLED, false) ?: false

    // Global delay MASTER kill switch (DelayPanel.kt) — defaults to on so it
    // never silently mutes a pad's own delay flag for anyone upgrading.
    fun saveDelayEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_DELAY_ENABLED, enabled)?.apply()
    }

    fun loadDelayEnabled(): Boolean = prefs()?.getBoolean(KEY_DELAY_ENABLED, true) ?: true

    fun saveDelayChokePad(padIndex: Int) {
        prefs()?.edit()?.putInt(KEY_DELAY_CHOKE_PAD, padIndex)?.apply()
    }

    fun loadDelayChokePad(): Int = prefs()?.getInt(KEY_DELAY_CHOKE_PAD, -1) ?: -1

    fun saveDelayLevel(level: Float) {
        prefs()?.edit()?.putFloat(KEY_DELAY_LEVEL, level)?.apply()
    }

    fun loadDelayLevel(): Float = prefs()?.getFloat(KEY_DELAY_LEVEL, 0.5f) ?: 0.5f

    fun saveSpeed(speed: Float) {
        prefs()?.edit()?.putFloat(KEY_SPEED, speed)?.apply()
    }

    fun loadSpeed(): Float = prefs()?.getFloat(KEY_SPEED, 1f) ?: 1f

    fun saveSelectedPad(padIndex: Int) {
        prefs()?.edit()?.putInt(KEY_SELECTED_PAD, padIndex)?.apply()
    }

    fun loadSelectedPad(): Int = prefs()?.getInt(KEY_SELECTED_PAD, 0) ?: 0

    fun saveVelocityOn(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_VELOCITY_ON, enabled)?.apply()
    }

    fun loadVelocityOn(): Boolean = prefs()?.getBoolean(KEY_VELOCITY_ON, true) ?: true

    fun saveBankMode(mode: String) {
        prefs()?.edit()?.putString(KEY_BANK_MODE, mode)?.apply()
    }

    fun loadBankMode(): String = prefs()?.getString(KEY_BANK_MODE, "A") ?: "A"

    fun saveKitB(index: Int) {
        prefs()?.edit()?.putInt(KEY_KIT_B, index)?.apply()
    }

    // BUG FIX (B bank kit isolation): used to default to index 25 ("EMPTY
    // 026"), which is still inside Bank A's own 0..199 kit pool — Bank A
    // and Bank B indexed into the very same shared `kits` list back then,
    // so this was never actually isolated from Bank A, just started on a
    // kit that happened to look blank. `kits` now has a dedicated,
    // permanently-blank 200-slot pool reserved for Bank B at indices
    // 200..399 (see OctapadScreen.kt's BANK_B_KIT_START) — default there
    // instead, and OctapadScreen.kt migrates any pre-existing saved value
    // (including this old default of 25) that still points into Bank A's
    // range back onto Bank B's own pool.
    fun loadKitB(): Int = prefs()?.getInt(KEY_KIT_B, 200) ?: 200

    fun saveKitC(index: Int) {
        prefs()?.edit()?.putInt(KEY_KIT_C, index)?.apply()
    }

    // Same reasoning as loadKitB() — starts blank (index 26 = "EMPTY 027",
    // the second blank slot, so B and C don't default to the SAME blank kit
    // and inadvertently look "linked" to each other).
    fun loadKitC(): Int = prefs()?.getInt(KEY_KIT_C, 26) ?: 26

    fun saveMidiChannel(channel: Int) {
        prefs()?.edit()?.putInt(KEY_MIDI_CHANNEL, channel)?.apply()
    }

    /** -1 = listen on all channels (default). */
    fun loadMidiChannel(): Int = prefs()?.getInt(KEY_MIDI_CHANNEL, -1) ?: -1

    // ── Backup / Restore support ────────────────────────────────────────────
    fun exportBackup(): org.json.JSONObject {
        val obj = org.json.JSONObject()
        obj.put(KEY_BPM, loadBpm())
        obj.put(KEY_EXCLUSIVE_MODE, loadExclusiveMode())
        obj.put(KEY_LOOP_ENABLED, loadLoopEnabled())
        obj.put(KEY_DELAY_ENABLED, loadDelayEnabled())
        obj.put(KEY_DELAY_CHOKE_PAD, loadDelayChokePad())
        obj.put(KEY_DELAY_LEVEL, loadDelayLevel())
        obj.put(KEY_SPEED, loadSpeed())
        obj.put(KEY_SELECTED_PAD, loadSelectedPad())
        obj.put(KEY_VELOCITY_ON, loadVelocityOn())
        obj.put(KEY_BANK_MODE, loadBankMode())
        obj.put(KEY_KIT_B, loadKitB())
        obj.put(KEY_KIT_C, loadKitC())
        obj.put(KEY_MIDI_CHANNEL, loadMidiChannel())
        return obj
    }

    // Holds every field read out of a backup's "preferences" payload, fully
    // parsed and type-checked, before anything is written to disk.
    private class ParsedPrefs(
        val bpm: Int?, val exclusiveMode: Boolean?, val loopEnabled: Boolean?,
        val delayEnabled: Boolean?, val delayChokePad: Int?, val delayLevel: Float?,
        val speed: Float?, val selectedPad: Int?, val velocityOn: Boolean?,
        val bankMode: String?, val kitB: Int?, val kitC: Int?, val midiChannel: Int?
    )

    // BUG FIX: importBackup used to call save*() immediately as it read each
    // field, so a single malformed field (wrong JSON type — e.g. a corrupted
    // backup where KEY_DELAY_LEVEL isn't a number) threw partway through,
    // leaving every field read BEFORE it already persisted and every field
    // after it untouched — a half-applied restore, not the atomic
    // all-or-nothing a "restore" should be. Split into a validate-only parse
    // pass (throws before any write) and a commit pass, mirroring
    // KitRepository's fix for the same failure class. Also lets
    // BackupScreen's restore flow validate every repository's payload before
    // committing any of them.
    private fun parseBackupPayload(obj: org.json.JSONObject): ParsedPrefs = ParsedPrefs(
        bpm = if (obj.has(KEY_BPM)) obj.getInt(KEY_BPM) else null,
        exclusiveMode = if (obj.has(KEY_EXCLUSIVE_MODE)) obj.getBoolean(KEY_EXCLUSIVE_MODE) else null,
        loopEnabled = if (obj.has(KEY_LOOP_ENABLED)) obj.getBoolean(KEY_LOOP_ENABLED) else null,
        delayEnabled = if (obj.has(KEY_DELAY_ENABLED)) obj.getBoolean(KEY_DELAY_ENABLED) else null,
        delayChokePad = if (obj.has(KEY_DELAY_CHOKE_PAD)) obj.getInt(KEY_DELAY_CHOKE_PAD) else null,
        delayLevel = if (obj.has(KEY_DELAY_LEVEL)) obj.getDouble(KEY_DELAY_LEVEL).toFloat() else null,
        speed = if (obj.has(KEY_SPEED)) obj.getDouble(KEY_SPEED).toFloat() else null,
        selectedPad = if (obj.has(KEY_SELECTED_PAD)) obj.getInt(KEY_SELECTED_PAD) else null,
        velocityOn = if (obj.has(KEY_VELOCITY_ON)) obj.getBoolean(KEY_VELOCITY_ON) else null,
        bankMode = if (obj.has(KEY_BANK_MODE)) obj.getString(KEY_BANK_MODE) else null,
        kitB = if (obj.has(KEY_KIT_B)) obj.getInt(KEY_KIT_B) else null,
        kitC = if (obj.has(KEY_KIT_C)) obj.getInt(KEY_KIT_C) else null,
        midiChannel = if (obj.has(KEY_MIDI_CHANNEL)) obj.getInt(KEY_MIDI_CHANNEL) else null
    )

    /** Throws if [obj]'s fields don't parse; writes nothing either way. */
    fun validateBackupPayload(obj: org.json.JSONObject) {
        parseBackupPayload(obj)
    }

    fun importBackup(obj: org.json.JSONObject) {
        val parsed = parseBackupPayload(obj)
        parsed.bpm?.let { saveBpm(it) }
        parsed.exclusiveMode?.let { saveExclusiveMode(it) }
        parsed.loopEnabled?.let { saveLoopEnabled(it) }
        parsed.delayEnabled?.let { saveDelayEnabled(it) }
        parsed.delayChokePad?.let { saveDelayChokePad(it) }
        parsed.delayLevel?.let { saveDelayLevel(it) }
        parsed.speed?.let { saveSpeed(it) }
        parsed.selectedPad?.let { saveSelectedPad(it) }
        parsed.velocityOn?.let { saveVelocityOn(it) }
        parsed.bankMode?.let { saveBankMode(it) }
        parsed.kitB?.let { saveKitB(it) }
        parsed.kitC?.let { saveKitC(it) }
        parsed.midiChannel?.let { saveMidiChannel(it) }
    }
}

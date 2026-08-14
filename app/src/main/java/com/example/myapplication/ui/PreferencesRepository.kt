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

    fun saveDelayEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_DELAY_ENABLED, enabled)?.apply()
    }

    fun loadDelayEnabled(): Boolean = prefs()?.getBoolean(KEY_DELAY_ENABLED, false) ?: false

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

    // NEW: defaults to a genuinely blank kit slot (index 25 = "EMPTY 026",
    // the first of the pre-generated blank kits), not kit index 0 which is
    // a real factory kit — Bank B should start empty until the user
    // explicitly points it at a loaded kit, not silently inherit Bank A's
    // default sounds.
    fun loadKitB(): Int = prefs()?.getInt(KEY_KIT_B, 25) ?: 25

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

    fun importBackup(obj: org.json.JSONObject) {
        if (obj.has(KEY_BPM)) saveBpm(obj.getInt(KEY_BPM))
        if (obj.has(KEY_EXCLUSIVE_MODE)) saveExclusiveMode(obj.getBoolean(KEY_EXCLUSIVE_MODE))
        if (obj.has(KEY_LOOP_ENABLED)) saveLoopEnabled(obj.getBoolean(KEY_LOOP_ENABLED))
        if (obj.has(KEY_DELAY_ENABLED)) saveDelayEnabled(obj.getBoolean(KEY_DELAY_ENABLED))
        if (obj.has(KEY_DELAY_CHOKE_PAD)) saveDelayChokePad(obj.getInt(KEY_DELAY_CHOKE_PAD))
        if (obj.has(KEY_DELAY_LEVEL)) saveDelayLevel(obj.getDouble(KEY_DELAY_LEVEL).toFloat())
        if (obj.has(KEY_SPEED)) saveSpeed(obj.getDouble(KEY_SPEED).toFloat())
        if (obj.has(KEY_SELECTED_PAD)) saveSelectedPad(obj.getInt(KEY_SELECTED_PAD))
        if (obj.has(KEY_VELOCITY_ON)) saveVelocityOn(obj.getBoolean(KEY_VELOCITY_ON))
        if (obj.has(KEY_BANK_MODE)) saveBankMode(obj.getString(KEY_BANK_MODE))
        if (obj.has(KEY_KIT_B)) saveKitB(obj.getInt(KEY_KIT_B))
        if (obj.has(KEY_KIT_C)) saveKitC(obj.getInt(KEY_KIT_C))
        if (obj.has(KEY_MIDI_CHANNEL)) saveMidiChannel(obj.getInt(KEY_MIDI_CHANNEL))
    }
}

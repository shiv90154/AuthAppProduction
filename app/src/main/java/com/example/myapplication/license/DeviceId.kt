package com.example.myapplication.license

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Stable per-install device identifier used to lock an activation code to
 * exactly one phone. ANDROID_ID is the primary source (survives app
 * reinstall on the same device on most OEMs); a random UUID persisted in
 * SharedPreferences is the fallback for the rare device where ANDROID_ID is
 * null/blank.
 */
object DeviceId {
    private const val PREFS_NAME = "license_prefs"
    private const val KEY_FALLBACK_ID = "device_id_fallback"

    fun get(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") { // known bad ANDROID_ID on some emulators
            return androidId
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_FALLBACK_ID, null)
        if (existing != null) return existing

        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_FALLBACK_ID, fresh).apply()
        return fresh
    }
}

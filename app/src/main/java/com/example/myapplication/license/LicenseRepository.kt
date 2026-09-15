package com.example.myapplication.license

import android.content.Context

/**
 * Local cache of activation state so the app opens instantly offline after
 * the first successful activation, while still catching a remote
 * deactivation from the admin panel within OFFLINE_GRACE_MS of it happening
 * (checked opportunistically whenever the app can reach the network — no
 * hard block on every launch, which is what "0 latency" + a working login
 * together require).
 */
object LicenseRepository {

    private const val PREFS_NAME = "license_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_ACTIVATED = "activated"
    private const val KEY_CODE = "activation_code"
    private const val KEY_ACTIVE = "active"
    private const val KEY_MIDI_PURCHASED = "midi_purchased"
    private const val KEY_LAST_VERIFIED_AT = "last_verified_at"
    private const val KEY_EXP = "license_exp"
    private const val KEY_SIG = "license_sig"

    // How long the app keeps working offline since the last successful
    // server check-in before it insists on reconnecting to re-verify.
    // Raised 3 -> 14 days: a genuine paying user who takes their pad on
    // stage somewhere with no signal for a week shouldn't get locked out,
    // and it also cushions any short outage of the activation server
    // (Vercel/Mongo) without every user losing access on day 3.
    private const val OFFLINE_GRACE_MS = 14L * 24 * 60 * 60 * 1000 // 14 days

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServerUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "") ?: ""

    fun saveServerUrl(context: Context, url: String) {
        // Trim trailing slash so "$baseUrl/api/..." never ends up with "//api".
        prefs(context).edit().putString(KEY_SERVER_URL, url.trimEnd('/')).apply()
    }

    fun getCode(context: Context): String = prefs(context).getString(KEY_CODE, "") ?: ""

    fun isMidiPurchased(context: Context): Boolean = prefs(context).getBoolean(KEY_MIDI_PURCHASED, false)

    /**
     * True if a server-signed license token is on file and verifies against
     * the currently-stored active/midiPurchased/exp — i.e. this state was
     * actually issued by the admin panel's private key, not just written
     * into SharedPreferences (which editing license_prefs directly, or
     * replaying/tampering an old HTTP response, could otherwise fake).
     */
    private fun hasValidSignedState(context: Context): Boolean {
        val p = prefs(context)
        val sig = p.getString(KEY_SIG, null) ?: return false
        val exp = p.getLong(KEY_EXP, 0L)
        val active = p.getBoolean(KEY_ACTIVE, false)
        val midiPurchased = p.getBoolean(KEY_MIDI_PURCHASED, false)
        return LicenseToken.verify(DeviceId.get(context), getCode(context), active, midiPurchased, exp, sig)
    }

    /** True if the app should open straight to the main screen without showing activation. */
    fun isUsable(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVATED, false)) return false
        if (!p.getBoolean(KEY_ACTIVE, false)) return false

        if (p.getString(KEY_SIG, null) != null) {
            // A signed token is on file — it, not the raw boolean, is the
            // source of truth from here. A tampered active flag or a copied
            // signature that no longer matches the stored fields fails
            // verify() and this device is treated as not activated.
            if (!hasValidSignedState(context)) return false
            return System.currentTimeMillis() < p.getLong(KEY_EXP, 0L)
        }

        // No signature yet — this activation predates signed tokens (an
        // already-activated install that hasn't reached /api/app/status
        // since this was added). Fall back to the old last-verified-time
        // grace window instead of instantly logging the user out; the next
        // successful redeem/status call attaches a signature.
        val lastVerified = p.getLong(KEY_LAST_VERIFIED_AT, 0L)
        return System.currentTimeMillis() - lastVerified < OFFLINE_GRACE_MS
    }

    /** True once grace has expired and the app needs a fresh server check before continuing. */
    fun needsReverification(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVATED, false)) return false

        val sig = p.getString(KEY_SIG, null)
        if (sig != null) {
            return System.currentTimeMillis() >= p.getLong(KEY_EXP, 0L) || !hasValidSignedState(context)
        }

        val lastVerified = p.getLong(KEY_LAST_VERIFIED_AT, 0L)
        return System.currentTimeMillis() - lastVerified >= OFFLINE_GRACE_MS
    }

    fun saveActivation(context: Context, code: String, active: Boolean, midiPurchased: Boolean, exp: Long? = null, sig: String? = null) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVATED, true)
            .putString(KEY_CODE, code)
            .putBoolean(KEY_ACTIVE, active)
            .putBoolean(KEY_MIDI_PURCHASED, midiPurchased)
            .putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())
            .also { editor ->
                if (exp != null && sig != null) editor.putLong(KEY_EXP, exp).putString(KEY_SIG, sig)
                else editor.remove(KEY_EXP).remove(KEY_SIG)
            }
            .apply()
    }

    /** Called after a background /status re-check succeeds or fails. */
    fun applyStatusCheck(context: Context, result: LicenseResult) {
        if (result.httpFailure) return // offline — leave cached state as-is, grace period covers it
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, result.active)
            .putBoolean(KEY_MIDI_PURCHASED, result.midiPurchased)
            .putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())
            .also { editor ->
                if (result.exp != null && result.sig != null) editor.putLong(KEY_EXP, result.exp).putString(KEY_SIG, result.sig)
                else editor.remove(KEY_EXP).remove(KEY_SIG)
            }
            .apply()
    }

    /** Wipes activation — used if a device needs to be deliberately logged out / re-activated. */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACTIVATED).remove(KEY_CODE).remove(KEY_ACTIVE)
            .remove(KEY_MIDI_PURCHASED).remove(KEY_LAST_VERIFIED_AT)
            .remove(KEY_EXP).remove(KEY_SIG)
            .apply()
    }
}

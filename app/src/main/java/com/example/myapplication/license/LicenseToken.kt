package com.example.myapplication.license

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies the ECDSA (P-256) signature the admin panel attaches to every
 * /api/app/redeem and /api/app/status response (see
 * admin-panel/src/lib/licenseToken.ts). Only the server holds the private
 * key — this object can check that a signature was genuinely issued by the
 * server, but can never produce a new one itself. Without this, activation
 * state was a plain boolean read straight out of the JSON response and out
 * of license_prefs — trivially flippable by editing that SharedPreferences
 * file on a rooted device, or by a MITM'd/replayed HTTP response, with no
 * server round trip required either way.
 */
object LicenseToken {

    // SPKI/DER-encoded EC P-256 public key, base64. The matching private key
    // lives only in the admin panel's LICENSE_SIGNING_PRIVATE_KEY_B64 env
    // var (never in this repo) — regenerate both together if this ever
    // needs to rotate (see admin-panel/.env.local.example for the command),
    // and be aware rotating it invalidates every already-activated device's
    // stored signature until it next successfully reaches /api/app/status.
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAED3KemAW/9GZ+dDGuSgsqWzCw0rt0SUU6Yhe1E/CpZ9m/OaRk6BVES4B3GYFf0fZZWVI+tnRvk2tYg0LbDPXpAA=="

    private val publicKey: PublicKey by lazy {
        val bytes = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    // Must stay byte-for-byte identical to licenseToken.ts's payload() —
    // field order and separator are part of what gets signed.
    private fun payloadFor(deviceId: String, code: String, active: Boolean, midiPurchased: Boolean, exp: Long) =
        "$deviceId|$code|$active|$midiPurchased|$exp"

    /** True only if [sig] is a valid server signature over exactly this (deviceId, code, active, midiPurchased, exp) tuple. */
    fun verify(deviceId: String, code: String, active: Boolean, midiPurchased: Boolean, exp: Long, sig: String): Boolean {
        return try {
            val payload = payloadFor(deviceId, code, active, midiPurchased, exp)
            val sigBytes = Base64.decode(sig, Base64.DEFAULT)
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(payload.toByteArray(Charsets.UTF_8))
            }.verify(sigBytes)
        } catch (e: Exception) {
            false
        }
    }
}

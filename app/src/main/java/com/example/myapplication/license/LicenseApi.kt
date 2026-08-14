package com.example.myapplication.license

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL


data class LicenseResult(
    val ok: Boolean,
    val active: Boolean = false,
    val midiPurchased: Boolean = false,
    val reason: String? = null,   // "INVALID_CODE" | "DEACTIVATED" | "ALREADY_USED" | "NOT_BOUND" | network error message
    val httpFailure: Boolean = false // true = couldn't reach the server at all (offline / bad URL)
)

object LicenseApi {

    private const val TIMEOUT_MS = 10_000

    suspend fun signup(baseUrl: String, deviceId: String, name: String, phone: String) {
        withContext(Dispatchers.IO) {
            try {
                post("$baseUrl/api/app/signup", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("name", name)
                    put("phone", phone)
                })
            } catch (e: Exception) {
                // Best-effort — signup failing shouldn't block using the app.
                android.util.Log.w("LicenseApi", "signup failed: ${e.message}")
            }
        }
    }

    suspend fun redeem(baseUrl: String, code: String, deviceId: String): LicenseResult =
        withContext(Dispatchers.IO) {
            try {
                val (status, body) = post("$baseUrl/api/app/redeem", JSONObject().apply {
                    put("code", code)
                    put("deviceId", deviceId)
                })
                parseLicenseResponse(status, body)
            } catch (e: Exception) {
                LicenseResult(ok = false, reason = e.message ?: "Couldn't reach the server", httpFailure = true)
            }
        }

    suspend fun status(baseUrl: String, code: String, deviceId: String): LicenseResult =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/api/app/status?code=${urlEncode(code)}&deviceId=${urlEncode(deviceId)}"
                val (status, body) = get(url)
                parseLicenseResponse(status, body)
            } catch (e: Exception) {
                LicenseResult(ok = false, reason = e.message ?: "Couldn't reach the server", httpFailure = true)
            }
        }

    private fun parseLicenseResponse(status: Int, body: String): LicenseResult {
        val obj = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
        val ok = obj.optBoolean("ok", status in 200..299)
        return LicenseResult(
            ok = ok,
            active = obj.optBoolean("active", false),
            midiPurchased = obj.optBoolean("midiPurchased", false),
            reason = if (obj.has("reason")) obj.getString("reason") else null
        )
    }

    private fun post(urlStr: String, body: JSONObject): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            return status to text
        } finally {
            conn.disconnect()
        }
    }

    private fun get(urlStr: String): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            return status to text
        } finally {
            conn.disconnect()
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}

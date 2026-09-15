package com.example.myapplication.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val apkUrl: String,
    val changelog: String,
    val forceUpdate: Boolean
)

object AppUpdateApi {

    private const val TIMEOUT_MS = 10_000

    // Public, unauthenticated endpoint (mirrors LicenseApi's shape) — see
    // admin-panel/src/app/api/app/version/route.ts. Returns null on any
    // failure (offline, server down, malformed body) so a failed check never
    // blocks the app; it's purely best-effort.
    suspend fun fetchLatest(baseUrl: String): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$baseUrl/api/app/version").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            try {
                val status = conn.responseCode
                if (status !in 200..299) return@withContext null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(text)
                AppUpdateInfo(
                    latestVersionCode = obj.optInt("latestVersionCode", 0),
                    latestVersionName = obj.optString("latestVersionName", ""),
                    apkUrl = obj.optString("apkUrl", ""),
                    changelog = obj.optString("changelog", ""),
                    forceUpdate = obj.optBoolean("forceUpdate", false)
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            android.util.Log.w("AppUpdateApi", "version check failed: ${e.message}")
            null
        }
    }
}

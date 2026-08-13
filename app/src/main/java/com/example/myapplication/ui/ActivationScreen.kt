package com.example.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.KeyboardPlayState
import com.example.myapplication.license.DeviceId
import com.example.myapplication.license.LicenseApi
import com.example.myapplication.license.LicenseRepository
import kotlinx.coroutines.launch

/**
 * Blocking first-run (and re-verification) screen: enter the admin-panel
 * URL once, then an activation code. One code binds to exactly one device
 * (enforced server-side by admin-panel/src/app/api/app/redeem) — a second
 * phone trying the same code gets ALREADY_USED.
 */
@Composable
fun ActivationScreen(onActivated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceId = remember { DeviceId.get(context) }

    var serverUrl by remember {
        mutableStateOf(
            LicenseRepository.getServerUrl(context).ifBlank {
                "https://octapad-adminpanel-final.vercel.app"
            }
        )
    }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(serverUrl.isBlank()) }

    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        KeyboardPlayState.textInputActive = true
        onDispose { KeyboardPlayState.textInputActive = false }
    }

    fun fieldError(): String? = when {
        serverUrl.isBlank() -> "Enter your admin panel's URL first (see admin-panel/README.md)."
        code.isBlank() -> "Enter your activation code."
        // NEW: phone number is now required before activation can proceed —
        // it's how the developer actually reaches a customer for support.
        phone.isBlank() -> "Enter your phone number to continue."
        phone.count { it.isDigit() } < 7 -> "Enter a valid phone number."
        else -> null
    }

    fun submit() {
        val err = fieldError()
        if (err != null) { errorMsg = err; return }
        val normalizedUrl = if (serverUrl.startsWith("http")) serverUrl else "https://$serverUrl"
        loading = true
        errorMsg = null
        scope.launch {
            LicenseRepository.saveServerUrl(context, normalizedUrl)
            LicenseApi.signup(normalizedUrl, deviceId, name, phone)
            val result = LicenseApi.redeem(normalizedUrl, code.trim(), deviceId)
            loading = false
            when {
                result.httpFailure -> errorMsg = "Couldn't reach the server. Check the URL and your internet connection."
                !result.ok && result.reason == "INVALID_CODE" -> errorMsg = "That activation code doesn't exist. Double-check it and try again."
                !result.ok && result.reason == "ALREADY_USED" -> errorMsg = "This code is already activated on a different device."
                !result.ok && result.reason == "DEACTIVATED" -> errorMsg = "This code has been deactivated. Contact support."
                !result.ok -> errorMsg = "Activation failed. Try again."
                !result.active -> errorMsg = "This code has been deactivated. Contact support."
                else -> {
                    LicenseRepository.saveActivation(context, code.trim(), active = true, midiPurchased = result.midiPurchased)
                    onActivated()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelBg)
            // BUG FIX: edge-to-edge mode (see MainActivity) means the OS no
            // longer auto-pans content away from the keyboard — imePadding()
            // does that in Compose instead, so the ACTIVATE button and
            // fields below it aren't left hidden behind the keyboard while
            // typing the phone number/name/code.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Brand mark ────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF0F1F1F)),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = LedActive, fontSize = 26.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(14.dp))
            Text("ARUN SPD 30", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text(
                "Enter your activation code to continue",
                color = Color(0xFF888888), fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            // ── Form card ────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF161616))
                    .border(1.dp, Color(0xFF262626), RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Field("ACTIVATION CODE", code, { code = it.uppercase() }, placeholder = "AB3D-9KXQ-7M2P")
                Spacer(Modifier.height(12.dp))
                Field("YOUR NAME (optional)", name, { name = it })
                Spacer(Modifier.height(12.dp))
                Field("PHONE NUMBER", phone, { phone = it }, keyboardType = KeyboardType.Phone)

                Spacer(Modifier.height(14.dp))

                // Advanced: server URL — collapsed once set, since most users
                // only ever configure this once (or it ships pre-set by you).
                Box(
                    modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { showAdvanced = !showAdvanced } },
                ) {
                    Text(
                        if (showAdvanced) "▾ SERVER URL" else "▸ SERVER URL${if (serverUrl.isNotBlank()) " (set)" else ""}",
                        color = Color(0xFF888888), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                }
                if (showAdvanced) {
                    Spacer(Modifier.height(8.dp))
                    Field("ADMIN PANEL URL", serverUrl, { serverUrl = it }, placeholder = "https://your-app.vercel.app")
                }

                Spacer(Modifier.height(18.dp))

                errorMsg?.let {
                    Text(it, color = Color(0xFFFF5252), fontSize = 11.5.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (loading) Color(0xFF1A3D3D) else LedActive)
                        .pointerInput(loading) { if (!loading) detectTapGestures { submit() } }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("ACTIVATE", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            }

            Text(
                "One code activates one device only. Your name and number are sent to the developer for support purposes.",
                color = Color(0xFF666666), fontSize = 9.5.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp)
            )

            // Compact social row, always visible right here on the login
            // screen — same links used to also live on a separate one-time
            // "follow us" screen, which has been removed in favor of this.
            Text(
                "FOLLOW US",
                color = Color(0xFF555555), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
            )
            SocialRow()
        }
    }
}

@Composable
private fun SocialRow(modifier: Modifier = Modifier) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SocialIcon("▶", Color(0xFFFF0000)) { uriHandler.openUri(SOCIAL_YOUTUBE_URL) }
        SocialIcon("📷", Color(0xFFE1306C)) { uriHandler.openUri(SOCIAL_INSTAGRAM_URL) }
        SocialIcon("💬", Color(0xFF25D366)) { uriHandler.openUri(SOCIAL_WHATSAPP_URL) }
    }
}

@Composable
private fun SocialIcon(icon: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        Text(icon, fontSize = 14.sp)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = Color(0xFF888888), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 4.dp, start = 2.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, color = Color(0xFF555555)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = LedActive, unfocusedBorderColor = Color(0xFF3A3A3A),
                focusedContainerColor = Color(0xFF1A1A1A), unfocusedContainerColor = Color(0xFF1A1A1A),
                cursorColor = LedActive
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

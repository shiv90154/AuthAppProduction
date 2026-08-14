package com.example.myapplication.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.KeyboardPlayState
import com.example.myapplication.R
import com.example.myapplication.license.DeviceId
import com.example.myapplication.license.LicenseApi
import com.example.myapplication.license.LicenseRepository
import kotlinx.coroutines.launch

// ── Local design tokens (this screen only) ─────────────────────────────────
private val CardBg      = Color(0xFF17191B)
private val CardBorder  = Color(0xFF262626)
private val FieldBg     = Color(0xFF1B1B1B)
private val FieldBorder = Color(0xFF3A3A3A)
private val TextMuted   = Color(0xFF9A9A9A)
private val TextFaint   = Color(0xFF787878)
private val ErrorBg     = Color(0xFF3A1414)
private val ErrorBorder = Color(0xFF5C1F1F)
private val ErrorText   = Color(0xFFFF8A8A)

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
    val uriHandler = LocalUriHandler.current
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

    val nameFocus = remember { FocusRequester() }
    val phoneFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }

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
            Box(contentAlignment = Alignment.Center) {
                // Soft glow behind the mark so the header doesn't sit flat
                // against the panel background.
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(LedActive.copy(alpha = 0.16f), Color.Transparent)
                            )
                        )
                )
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = "ARUN SPD 30 logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, LedActive.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("ARUN SPD 30", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text(
                "Enter your activation code to continue",
                color = TextMuted, fontSize = 12.5.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 28.dp)
            )

            // ── Form card ────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardBg)
                    .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                    .padding(20.dp)
            ) {
                Field(
                    label = "ACTIVATION CODE",
                    value = code,
                    onChange = { code = it.uppercase() },
                    placeholder = "AB3D-9KXQ-7M2P",
                    leadingIcon = Icons.Filled.VpnKey,
                    imeAction = ImeAction.Next,
                    onNext = { nameFocus.requestFocus() }
                )
                Spacer(Modifier.height(12.dp))
                Field(
                    label = "YOUR NAME (optional)",
                    value = name,
                    onChange = { name = it },
                    leadingIcon = Icons.Filled.Person,
                    imeAction = ImeAction.Next,
                    focusRequester = nameFocus,
                    onNext = { phoneFocus.requestFocus() }
                )
                Spacer(Modifier.height(12.dp))
                Field(
                    label = "PHONE NUMBER",
                    value = phone,
                    onChange = { phone = it },
                    keyboardType = KeyboardType.Phone,
                    leadingIcon = Icons.Filled.Phone,
                    imeAction = if (showAdvanced) ImeAction.Next else ImeAction.Done,
                    focusRequester = phoneFocus,
                    onNext = { urlFocus.requestFocus() },
                    onDone = { submit() }
                )

                Spacer(Modifier.height(14.dp))

                // Advanced: server URL — collapsed once set, since most users
                // only ever configure this once (or it ships pre-set by you).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) { detectTapGestures { showAdvanced = !showAdvanced } },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (showAdvanced) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                        contentDescription = null, tint = TextFaint, modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "SERVER URL${if (!showAdvanced && serverUrl.isNotBlank()) " (set)" else ""}",
                        color = TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                }
                if (showAdvanced) {
                    Spacer(Modifier.height(8.dp))
                    Field(
                        label = "ADMIN PANEL URL",
                        value = serverUrl,
                        onChange = { serverUrl = it },
                        placeholder = "https://your-app.vercel.app",
                        leadingIcon = Icons.Filled.Language,
                        imeAction = ImeAction.Done,
                        focusRequester = urlFocus,
                        onDone = { submit() }
                    )
                }

                Spacer(Modifier.height(18.dp))

                errorMsg?.let { msg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ErrorBg)
                            .border(1.dp, ErrorBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = ErrorText, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(msg, color = ErrorText, fontSize = 11.5.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = if (loading) 0.dp else 10.dp,
                            shape = RoundedCornerShape(10.dp),
                            ambientColor = LedActive.copy(alpha = 0.5f),
                            spotColor = LedActive.copy(alpha = 0.5f)
                        )
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

            // ── Support / contact ───────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF141414))
                    .border(1.dp, Color(0xFF242424), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "One code activates one device only. Your name and number are sent to the developer for support purposes.",
                    color = TextMuted, fontSize = 10.sp, textAlign = TextAlign.Center
                )

                // Hindi call-to-action with the direct number, so anyone
                // stuck on activation can just call/WhatsApp instead of
                // hunting for support elsewhere.
                Text(
                    "एक्टिवेशन में परेशानी हो तो सीधे कॉल या व्हाट्सएप करें",
                    color = Color(0xFFB0B0B0), fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .pointerInput(Unit) { detectTapGestures { uriHandler.openUri("tel:+918319277458") } }
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null, tint = LedActive, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "+91 83192 77458",
                        color = LedActive, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                }
            }

            // Compact social row, always visible right here on the login
            // screen — same links used to also live on a separate one-time
            // "follow us" screen, which has been removed in favor of this.
            Text(
                "FOLLOW US",
                color = TextFaint, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)
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
        YouTubeIcon { uriHandler.openUri(SOCIAL_YOUTUBE_URL) }
        InstagramIcon { uriHandler.openUri(SOCIAL_INSTAGRAM_URL) }
        WhatsAppIcon { uriHandler.openUri(SOCIAL_WHATSAPP_URL) }
    }
}

// All three below are hand-drawn Canvas marks, not emoji/unicode glyphs —
// emoji (was "▶"/"📷"/"💬") render inconsistently across devices/keyboards/
// OEM fonts and don't reliably read as the actual brand. Drawing the real
// silhouette (play button / camera / phone-in-bubble) on the brand's own
// color looks the same everywhere and is unambiguous.

@Composable
private fun YouTubeIcon(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFFFF0000))
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(16.dp)) {
            val w = size.width
            val h = size.height
            val triangle = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.18f, h * 0.08f)
                lineTo(w * 0.92f, h * 0.5f)
                lineTo(w * 0.18f, h * 0.92f)
                close()
            }
            drawPath(triangle, color = Color.White)
        }
    }
}

@Composable
private fun InstagramIcon(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFEDA75), Color(0xFFD62976), Color(0xFF4F5BD5))
                )
            )
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(19.dp)) {
            val stroke = size.width * 0.11f
            drawRoundRect(
                color = Color.White,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.32f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            drawCircle(
                color = Color.White,
                radius = size.minDimension * 0.21f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            drawCircle(
                color = Color.White,
                radius = stroke * 0.55f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.76f, size.height * 0.24f)
            )
        }
    }
}

@Composable
private fun WhatsAppIcon(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF25D366))
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(18.dp)) {
            rotate(45f) {
                val capsuleWidth = size.width * 0.4f
                val capsuleHeight = size.height * 0.42f
                drawRoundRect(
                    color = Color.White,
                    topLeft = androidx.compose.ui.geometry.Offset(size.width / 2 - capsuleWidth / 2, 0f),
                    size = androidx.compose.ui.geometry.Size(capsuleWidth, capsuleHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(capsuleWidth / 2)
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = androidx.compose.ui.geometry.Offset(size.width / 2 - capsuleWidth / 2, size.height - capsuleHeight),
                    size = androidx.compose.ui.geometry.Size(capsuleWidth, capsuleHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(capsuleWidth / 2)
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    leadingIcon: ImageVector? = null,
    imeAction: ImeAction = ImeAction.Default,
    focusRequester: FocusRequester? = null,
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = TextFaint, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 4.dp, start = 2.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, color = Color(0xFF5C5C5C)) },
            singleLine = true,
            leadingIcon = leadingIcon?.let { icon ->
                {
                    Icon(icon, contentDescription = null, tint = Color(0xFF6E6E6E), modifier = Modifier.size(18.dp))
                }
            },
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onNext = { onNext?.invoke() },
                onDone = { onDone?.invoke() },
                onGo = { onDone?.invoke() }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = LedActive, unfocusedBorderColor = FieldBorder,
                focusedContainerColor = FieldBg, unfocusedContainerColor = FieldBg,
                cursorColor = LedActive
            ),
            modifier = Modifier
                .fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
        )
    }
}

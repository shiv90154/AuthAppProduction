package com.example.myapplication.ui

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.R

/**
 * Branded startup screen — shown for a short beat before handing off to
 * either the activation gate or the main pad screen, so the app never
 * flashes straight from a blank window into either. Purely presentational;
 * MainActivity controls how long it stays up.
 */
@Composable
fun SplashScreen() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(420, easing = EaseOutCubic),
        label = "splashFade"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = tween(420, easing = EaseOutCubic),
        label = "splashScale"
    )

    Box(
        modifier = Modifier.fillMaxSize().background(PanelBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .alpha(alpha)
                .scale(scale)
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = "ARUN SPD-30 MOBILE OCTAPAD logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(104.dp)
            )

            Spacer(Modifier.height(22.dp))

            Text(
                "ARUN SPD-30",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "MOBILE OCTAPAD  ·  8-PAD SAMPLER",
                color = Color(0xFF8A8A8A),
                fontSize = 10.5.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── Social links — the standalone one-time "follow us" screen that used to
// live here has been removed; these URLs are now only consumed by the
// compact social row on ActivationScreen.kt (same package).
const val SOCIAL_YOUTUBE_URL   = "https://youtube.com/@arunspd30?si=qimBMd6E13OYr4Wy"
const val SOCIAL_INSTAGRAM_URL = "https://www.instagram.com/arunprachiofficial?igsh=cDdqcXgzbmM2NTcz"
const val SOCIAL_WHATSAPP_URL  = "https://wa.me/918319277458"

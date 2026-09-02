package com.example.myapplication

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.example.myapplication.license.LicenseRepository
import com.example.myapplication.ui.ActivationScreen
import com.example.myapplication.ui.OctapadScreen
import com.example.myapplication.ui.PreferencesRepository
import com.example.myapplication.ui.SplashScreen
import kotlinx.coroutines.delay
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat



class MainActivity : ComponentActivity() {

    private lateinit var soundPool: SoundPool

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // NEW: true fullscreen (status bar + nav bar hidden, swipe to reveal
        // temporarily) — this is meant to feel like a hardware sampler, not
        // an app with a visible clock/battery strip across the top. Applies
        // to every screen (splash, follow-us, activation, pads) since it's
        // set once here, not per-screen.
        hideSystemBars()

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // maxStreams raised so many pads can overlap without cutting each other off
        soundPool = SoundPool.Builder()
            .setMaxStreams(16)
            .setAudioAttributes(attributes)
            .build()

        // NEW: pad1..pad8 default sample files no longer exist — real kit
        // sounds are loaded by name (kitN_padM) inside OctapadScreen itself.
        val sounds = emptyList<Int>()

        // ── Restore MIDI device connection — this was missing, which is why
        //    no MIDI_TEST logs appeared and the device was never connected ──
        MidiManagerHelper(this).listDevices()

        requestMicPermission()

        setContent {
            // "har phone me display alag alag aa raha hai" fix — two parts.
            //
            // (1) fontScale pinned to 1x: this is a fixed-pixel hardware-
            // instrument UI (landscape-only pad grid + tightly packed control
            // panel), not a scrolling document, so the device's accessibility
            // "font size" setting must not reflow it.
            //
            // (2) Density remap: the bigger cause of the same layout looking
            // different across phones is effective screen DENSITY: every OEM
            // ships a different default,
            // and Android's own "Display size" / "screen zoom" setting
            // changes it further — so two phones with the identical physical
            // screen can report very different dp widths, and every fixed-dp
            // size in this hardware-instrument UI then occupies a different
            // fraction of the screen.
            //
            // We remap density so the usable screen is ALWAYS the same number
            // of dp wide (REFERENCE_WIDTH_DP) regardless of the device's real
            // density or zoom setting. Every dp measurement in the app is now
            // relative to one fixed design width, so the pad grid + control
            // panel lay out identically everywhere; height still tracks the
            // device aspect ratio (handled by the existing BoxWithConstraints
            // height-scaling in OctapadScreen/RightPanel). Purely a uniform
            // scale — no clipping, no distortion.
            val referenceWidthDp = 820f
            val cfg = LocalConfiguration.current
            val realDensity = LocalDensity.current.density
            val currentWidthDp = cfg.screenWidthDp.toFloat().coerceAtLeast(1f)
            val scaledDensity = (realDensity * (currentWidthDp / referenceWidthDp))
                .coerceIn(0.5f, 8f)
            val fixedDensity = Density(
                density = scaledDensity,
                fontScale = 1f
            )
            CompositionLocalProvider(LocalDensity provides fixedDensity) {
                // Gate: activation must succeed once (checked with an offline
                // grace period after that — see LicenseRepository) before the
                // instrument itself is reachable. A short branded splash covers
                // the very first frame so the app never flashes straight from a
                // blank window into either screen.
                val context = LocalContext.current
                PreferencesRepository.init(context)
                var activated by remember { mutableStateOf(LicenseRepository.isUsable(context)) }
                var showSplash by remember { mutableStateOf(true) }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    delay(1100)
                    showSplash = false
                }

                // NOTE: the one-time standalone "follow us" screen that used
                // to appear here (right after the splash) has been removed —
                // the same social links now live directly on ActivationScreen
                // instead, always visible there rather than a one-time step.
                when {
                    showSplash -> SplashScreen()
                    activated -> OctapadScreen(soundPool, sounds, onDeactivated = { activated = false })
                    else -> ActivationScreen(onActivated = { activated = true })
                }
            }
        }
    }

    private fun requestMicPermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool.release()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // System bars can reappear (e.g. after a swipe-reveal, or when the user
    // switches back into the app) without a new onCreate — re-hide them
    // whenever the window regains focus so it doesn't stay stuck visible.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ── Permanent hardware-keyboard pad mapping ─────────────────────────────
    // Top row Q W E R -> pads 1-4, bottom row A S D F -> pads 5-8. Plays a pad
    // exactly like a MIDI hit (full velocity) via the same MidiEventBus path
    // OctapadScreen already listens on. Suspended while a text field (e.g.
    // the Rename Kit dialog) has focus so typing those letters still works.
    //
    // Overriding dispatchKeyEvent is completely normal Activity usage, but
    // lint's RestrictedApi check misfires on it with the androidx.core /
    // activity-compose versions this project is pinned to (a known false
    // positive for this exact override, not a real API-visibility issue).
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            !KeyboardPlayState.textInputActive
        ) {
            // Tab always exits EDIT MODE (a quick "get me out of here"
            // shortcut for a hardware keyboard), regardless of whether it's
            // currently on — OctapadScreen just forces the flag false.
            if (event.keyCode == KeyEvent.KEYCODE_TAB) {
                MidiEventBus.triggerExitEditMode()
                return true
            }
            val pad = when (event.keyCode) {
                KeyEvent.KEYCODE_Q -> 1
                KeyEvent.KEYCODE_W -> 2
                KeyEvent.KEYCODE_E -> 3
                KeyEvent.KEYCODE_R -> 4
                KeyEvent.KEYCODE_A -> 5
                KeyEvent.KEYCODE_S -> 6
                KeyEvent.KEYCODE_D -> 7
                KeyEvent.KEYCODE_F -> 8
                else -> -1
            }
            if (pad != -1) {
                MidiEventBus.triggerPad(pad, 1f)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
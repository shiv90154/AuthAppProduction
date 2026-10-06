package com.example.myapplication.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.MidiConnectionState
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private val MidiOn = Color(0xFF00C853)
private val MidiOff = Color(0xFF8A8A8A)
private val MidiBad = Color(0xFFFF5252)

/**
 * Small MIDI status badge: a 5-pin DIN connector symbol that is green when a
 * controller is connected and grey with a red slash when not, a dot that
 * blinks on every incoming MIDI message, and — for a few seconds after every
 * plug / unplug — a banner naming what just happened, so even pulling the
 * cable straight back out leaves a visible trace.
 */
@Composable
fun MidiStatusBadge(modifier: Modifier = Modifier) {
    val connected = MidiConnectionState.isConnected
    val event = MidiConnectionState.lastEvent

    var banner by remember { mutableStateOf<MidiConnectionState.Event?>(null) }
    LaunchedEffect(event?.seq) {
        val e = event ?: return@LaunchedEffect
        // Only flash something that just happened — not an old event seen
        // again because the screen was rebuilt.
        if (SystemClock.uptimeMillis() - e.atMs > 3_000L) return@LaunchedEffect
        banner = e
        delay(2_800L)
        banner = null
    }

    // Activity dot: lit while MIDI data arrived in the last ~140ms. Polled
    // here (only while connected) rather than driven by state writes from the
    // MIDI thread, so a fast CC sweep can't flood recomposition.
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(connected) {
        while (connected) {
            active = SystemClock.uptimeMillis() - MidiConnectionState.lastMessageAtMs < 140L
            delay(50L)
        }
        active = false
    }

    val shown = banner
    val accent = when {
        shown != null -> if (shown.connected) MidiOn else MidiBad
        connected -> MidiOn
        else -> MidiOff
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xE61A1A1A))
            .border(1.dp, accent, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MidiGlyph(color = if (connected) MidiOn else MidiOff, slashed = !connected)
        if (shown != null) {
            Text(
                text = if (shown.connected) "MIDI CONNECTED · ${shown.name}" else "MIDI DISCONNECTED",
                color = accent,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = "MIDI",
                color = if (connected) MidiOn else MidiOff,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !connected -> Color(0xFF3A3A3A)
                            active -> Color.White
                            else -> Color(0xFF00692F)
                        }
                    )
            )
        }
    }
}

/** 5-pin DIN connector: a ring with five pins along its upper arc. */
@Composable
private fun MidiGlyph(color: Color, slashed: Boolean) {
    Canvas(Modifier.size(10.dp)) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(color, radius = r - 0.6.dp.toPx(), center = c, style = Stroke(1.1.dp.toPx()))
        val pinRing = r * 0.5f
        for (i in 0 until 5) {
            // 180° (left) -> 270° (top) -> 360° (right), screen y points down.
            val a = Math.toRadians(180.0 + i * 45.0)
            drawCircle(
                color,
                radius = 0.8.dp.toPx(),
                center = Offset(c.x + pinRing * cos(a).toFloat(), c.y + pinRing * sin(a).toFloat())
            )
        }
        if (slashed) {
            drawLine(
                MidiBad,
                Offset(size.width * 0.08f, size.height * 0.92f),
                Offset(size.width * 0.92f, size.height * 0.08f),
                strokeWidth = 1.4.dp.toPx()
            )
        }
    }
}

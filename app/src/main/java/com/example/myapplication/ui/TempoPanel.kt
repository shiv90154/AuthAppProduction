package com.example.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TempoBg        = Color(0xFF111111)
private val TempoPanelBg   = Color(0xFF1A1A1A)
private val TempoAccent    = Color(0xFF00E5FF)
private val TempoGreen     = Color(0xFF00C853)
private val TempoDivider   = Color(0xFF2A2A2A)
private val TempoTextMuted = Color(0xFF888888)
private val TempoTextWht   = Color(0xFFEEEEEE)

@Composable
fun TempoPanel(
    bpm: Int,
    loopEnabled: Boolean,
    onBpmChange: (Int) -> Unit,
    onLoopChange: (Boolean) -> Unit,
    // NEW: global tempo-synced playback-rate multiplier
    speed: Float = 1f,
    onSpeedChange: (Float) -> Unit = {},
    // NEW: per-pad play mode selector, moved here from the FX panel — ONE
    // SHOT / LOOP / MULTIPLAY (MULTIPLAY = the old "MIX" mode, layers
    // repeated hits instead of cutting off)
    padPlayMode: String = "ONESHOT",
    onPlayModeChange: (String) -> Unit = {},
    onClose: () -> Unit,
    width: androidx.compose.ui.unit.Dp = 200.dp
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
            .background(TempoBg)
            .border(1.dp, TempoDivider, RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // ── Header ────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LOOP", color = TempoAccent, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF2A2A2A))
                        .clickable(remember { MutableInteractionSource() }, null) { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = TempoTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(TempoDivider))

            // ── BPM Display + Stepper ────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TempoPanelBg)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$bpm", color = TempoTextWht, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("BPM", color = TempoTextMuted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BpmStepButton("-10") { onBpmChange((bpm - 10).coerceIn(40, 300)) }
                        BpmStepButton("-1")  { onBpmChange((bpm - 1).coerceIn(40, 300)) }
                        BpmStepButton("+1")  { onBpmChange((bpm + 1).coerceIn(40, 300)) }
                        BpmStepButton("+10") { onBpmChange((bpm + 10).coerceIn(40, 300)) }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(TempoDivider))

            // ── Speed (tempo-synced playback-rate multiplier) ──────────────
            // Continuous drag knob, same LinearSlider used for VOL/PITCH —
            // replaces the old -10%/RESET/+10% step buttons, which weren't
            // direct enough for a live-performance speed adjustment.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TempoPanelBg)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearSlider(
                        title = "SPEED",
                        value = speed,
                        min = 0.5f,
                        max = 2f,
                        displayText = "${"%.2f".format(speed)}x",
                        accentColor = TempoAccent,
                        onValueChange = { onSpeedChange(it.coerceIn(0.5f, 2f)) }
                    )
                    Spacer(Modifier.height(8.dp))
                    BpmStepButton("RESET") { onSpeedChange(1f) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Varispeed: plays the sample faster/slower + higher/lower pitched, like the PITCH knob. Independent of BPM.",
                        color = TempoTextMuted, fontSize = 8.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(TempoDivider))

            // ── Loop toggle ───────────────────────────────────────────────
            ToggleRow(
                label    = "LOOP",
                subtitle = if (loopEnabled) "Pad loops at BPM rate" else "Loop is off",
                enabled  = loopEnabled,
                color    = TempoGreen,
                onClick  = { onLoopChange(!loopEnabled) }
            )

            Text(
                "Loop syncs with BPM — affects all pads. Choke levels are set in the CHOKE panel.",
                color = TempoTextMuted, fontSize = 8.sp, modifier = Modifier.fillMaxWidth()
            )

            Box(Modifier.fillMaxWidth().height(1.dp).background(TempoDivider))

            // ── Per-pad play mode (moved here from FX) ──────────────────────
            Text("PLAY MODE", color = TempoTextWht, fontSize = 9.sp,
                 fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ONESHOT" to "ONE SHOT", "LOOP" to "LOOP", "MIX" to "MULTIPLAY").forEach { (mode, label) ->
                    val selected = padPlayMode == mode
                    Box(
                        modifier = Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (selected) TempoAccent else Color(0xFF2A2A2A))
                            .clickable(remember { MutableInteractionSource() }, null) { onPlayModeChange(mode) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (selected) Color.Black else TempoTextMuted,
                             fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    subtitle: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TempoPanelBg)
            .clickable(remember { MutableInteractionSource() }, null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(label, color = TempoTextWht, fontSize = 9.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(subtitle, color = if (enabled) color else TempoTextMuted, fontSize = 8.sp)
        }
        // Pill toggle
        Box(
            modifier = Modifier
                .width(38.dp).height(20.dp)
                .clip(RoundedCornerShape(50))
                .background(if (enabled) color.copy(alpha = 0.25f) else Color(0xFF2A2A2A))
                .border(1.dp,
                    if (enabled) color.copy(alpha = 0.6f) else Color(0xFF3A3A3A),
                    RoundedCornerShape(50))
        ) {
            Box(
                modifier = Modifier
                    .padding(3.dp)
                    .size(14.dp)
                    .offset(x = if (enabled) 18.dp else 0.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (enabled) color else Color(0xFF555555))
            )
        }
    }
}

@Composable
private fun BpmStepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2A2A2A))
            .clickable(remember { MutableInteractionSource() }, null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = TempoTextWht, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

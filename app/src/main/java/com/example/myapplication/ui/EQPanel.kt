package com.example.myapplication.ui
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val EqBg        = Color(0xFF111111)
private val EqPanelBg   = Color(0xFF1A1A1A)
private val EqAccent    = Color(0xFF00E5FF)
private val EqGreen     = Color(0xFF00C853)
private val EqOrange    = Color(0xFFFFB74D)
private val EqRed       = Color(0xFFFF5252)
private val EqDivider   = Color(0xFF2A2A2A)
private val EqTextMuted = Color(0xFF888888)
private val EqTextWht   = Color(0xFFEEEEEE)

@Composable
fun EQPanel(
    visible: Boolean,
    selectedPad: Int = 0,          // NEW: display which pad these settings apply to
    // NOTE: choke levels moved out to their own dedicated ChokePanel.kt (a
    // top-level CHOKE button next to CROP) — used to live buried in here
    // behind an EXCLUSIVE MODE toggle, three taps deep. That toggle (and
    // the separate ACTIVE LEVEL selector) was later removed entirely —
    // choke groups are simply always live now, see ChokePanel.kt.
    velocityOn: Boolean = true,
    onVelocityChange: (Boolean) -> Unit = {},
    isRecording: Boolean,
    onRecordClick: () -> Unit,
    // Per-pad EQ + Level + Delay Time
    masterLevel: Float = 1f,
    eqLow: Float = 1f,
    eqMid: Float = 1f,
    eqHigh: Float = 1f,
    padLengthPct: Float = 1f,
    onPadLengthChange: (Float) -> Unit = {},
    onMasterLevelChange: (Float) -> Unit = {},
    onEqLowChange: (Float) -> Unit = {},
    onEqMidChange: (Float) -> Unit = {},
    onEqHighChange: (Float) -> Unit = {},
    // Per-pad FX: Reverse + explicit Save (PLAY MODE moved to the LOOP panel)
    padReverse: Boolean = false,
    onReverseChange: (Boolean) -> Unit = {},
    // NEW: per-pad Pan (-1f full left .. 1f full right) + Gain (0f..2f trim)
    padPan: Float = 0f,
    onPanChange: (Float) -> Unit = {},
    padGain: Float = 1f,
    onGainChange: (Float) -> Unit = {},
    // DELAY moved out to its own dedicated DelayPanel.kt (a top-level DELAY
    // button next to CROP/CHOKE) — no longer part of FX.
    // NEW: one-tap file-manager import straight onto this pad — skips the
    // separate Import screen → Audios screen → pad-picker detour.
    onImportToPad: () -> Unit = {},
    onSaveClick: () -> Unit = {},
    onClose: () -> Unit,
    width: androidx.compose.ui.unit.Dp = 220.dp
) {
    AnimatedVisibility(
        visible = visible,
        enter   = slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(220)) + fadeIn(tween(220)),
        exit    = slideOutHorizontally(targetOffsetX  = { -it }, animationSpec = tween(180)) + fadeOut(tween(180))
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                .background(EqBg)
                .border(1.dp, EqDivider, RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                // ── Header ────────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "PAD ${selectedPad + 1}  •  FX", color = EqAccent,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF2A2A2A))
                            .clickable(remember { MutableInteractionSource() }, null) { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✕", color = EqTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                EqDividerLine()

                // ── One-tap import straight onto this pad ──────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A1A1A))
                        .clickable(remember { MutableInteractionSource() }, null) { onImportToPad() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("IMPORT TO THIS PAD", color = EqAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }

                EqDividerLine()

                // ── LEVEL + LENGTH ──────────────────────────────────────────
                SectionLabel("LEVEL  &  LENGTH")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    EqKnobColumn(
                        label = "LEVEL",
                        value = masterLevel,
                        min = 0f, max = 2f,
                        color = EqAccent,
                        displayText = "${(masterLevel * 100).toInt()}%",
                        onValueChange = onMasterLevelChange
                    )
                    EqKnobColumn(
                        label = "LENGTH",
                        value = padLengthPct,
                        min = 0.05f, max = 1f,
                        color = EqGreen,
                        displayText = "${(padLengthPct * 100).toInt()}%",
                        onValueChange = onPadLengthChange
                    )
                }

                EqDividerLine()

                // ── 3-BAND EQ ─────────────────────────────────────────────────
                SectionLabel("EQUALIZER")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    EqKnobColumn(
                        label = "LOW",
                        value = eqLow,
                        min = 0f, max = 2f,
                        color = EqGreen,
                        displayText = eqDbText(eqLow),
                        onValueChange = onEqLowChange
                    )
                    EqKnobColumn(
                        label = "MID",
                        value = eqMid,
                        min = 0f, max = 2f,
                        color = EqAccent,
                        displayText = eqDbText(eqMid),
                        onValueChange = onEqMidChange
                    )
                    EqKnobColumn(
                        label = "HIGH",
                        value = eqHigh,
                        min = 0f, max = 2f,
                        color = EqRed,
                        displayText = eqDbText(eqHigh),
                        onValueChange = onEqHighChange
                    )
                }

                // EQ reset button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2A2A2A))
                        .clickable(remember { MutableInteractionSource() }, null) {
                            onEqLowChange(1f); onEqMidChange(1f); onEqHighChange(1f)
                            onMasterLevelChange(1f)
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("RESET EQ", color = EqTextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }

                EqDividerLine()

                // ── PAN + GAIN ───────────────────────────────────────────────
                SectionLabel("PAN  &  GAIN")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    EqKnobColumn(
                        label = "PAN",
                        value = padPan,
                        min = -1f, max = 1f,
                        color = EqAccent,
                        displayText = when {
                            padPan < -0.02f -> "L${(-padPan * 100).toInt()}"
                            padPan > 0.02f  -> "R${(padPan * 100).toInt()}"
                            else            -> "CENTER"
                        },
                        onValueChange = onPanChange
                    )
                    EqKnobColumn(
                        label = "GAIN",
                        value = padGain,
                        min = 0f, max = 2f,
                        color = EqGreen,
                        displayText = "${(padGain * 100).toInt()}%",
                        onValueChange = onGainChange
                    )
                }

                EqDividerLine()

                // ── FX: Reverse + Playback Mode ───────────────────────────────
                SectionLabel("FX")

                EqToggleRow(
                    title = "REVERSE",
                    subtitle = if (padReverse) "Playing back-to-front" else "Normal playback",
                    enabled = padReverse,
                    activeColor = EqOrange,
                    onToggle = onReverseChange
                )

                EqDividerLine()

                // ── PAD BEHAVIOUR ─────────────────────────────────────────────
                SectionLabel("PAD BEHAVIOUR")

                EqToggleRow(
                    title = "VELOCITY",
                    subtitle = if (velocityOn) "Hit strength affects volume" else "Always full volume",
                    enabled = velocityOn,
                    activeColor = EqGreen,
                    onToggle = onVelocityChange
                )

                EqDividerLine()

                // ── RECORDING ─────────────────────────────────────────────────
                SectionLabel("RECORDING")

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isRecording) Color.Red else EqPanelBg)
                        .clickable(remember { MutableInteractionSource() }, null) { onRecordClick() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isRecording) "STOP RECORDING" else "START RECORDING",
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                }

                EqDividerLine()

                // ── SAVE ────────────────────────────────────────────────────────
                var savedFlash by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (savedFlash) EqGreen else EqAccent)
                        .clickable(remember { MutableInteractionSource() }, null) {
                            onSaveClick()
                            savedFlash = true
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (savedFlash) "SAVED ✓" else "SAVE",
                        color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
                LaunchedEffect(savedFlash) {
                    if (savedFlash) {
                        kotlinx.coroutines.delay(1200)
                        savedFlash = false
                    }
                }
            }
        }
    }
}

// ── EQ Knob column ────────────────────────────────────────────────────────────

@Composable
private fun EqKnobColumn(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    color: Color,
    displayText: String,
    onValueChange: (Float) -> Unit
) {
    LinearSlider(
        title = label,
        value = value,
        min = min,
        max = max,
        displayText = displayText,
        accentColor = color,
        trackHeight = 84.dp,
        trackWidth = 18.dp,
        onValueChange = onValueChange
    )
}

// ── Shared helpers (identical to old EQPanel) ─────────────────────────────────

@Composable
private fun EqToggleRow(
    title: String, subtitle: String,
    enabled: Boolean, activeColor: Color,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(EqPanelBg)
            .clickable(remember { MutableInteractionSource() }, null) { onToggle(!enabled) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = EqTextWht, fontSize = 9.sp,
                 fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(subtitle,
                 color = if (enabled) activeColor else EqTextMuted, fontSize = 8.sp)
        }
        PillToggle(enabled = enabled, activeColor = activeColor)
    }
}

@Composable
private fun PillToggle(enabled: Boolean, activeColor: Color) {
    val thumbOffset by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f, animationSpec = tween(160), label = "pillThumb"
    )
    Box(
        modifier = Modifier.width(38.dp).height(20.dp).clip(RoundedCornerShape(50))
            .background(if (enabled) activeColor.copy(alpha = 0.25f) else Color(0xFF2A2A2A))
            .border(1.dp, if (enabled) activeColor.copy(alpha = 0.6f) else Color(0xFF3A3A3A), RoundedCornerShape(50))
    ) {
        Box(
            modifier = Modifier.size(14.dp)
                .offset(x = (2f + thumbOffset * 18f).dp, y = 3.dp)
                .clip(RoundedCornerShape(50))
                .background(if (enabled) activeColor else Color(0xFF555555))
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = EqTextMuted, fontSize = 8.sp,
         fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
         modifier = Modifier.padding(start = 2.dp))
}

@Composable
private fun EqDividerLine() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(EqDivider))
}

private fun eqDbText(gain: Float): String {
    if (gain <= 0.01f) return "-∞dB"
    val db = 20f * kotlin.math.log10(gain)
    return if (db >= 0) "+${"%.1f".format(db)}dB" else "${"%.1f".format(db)}dB"
}

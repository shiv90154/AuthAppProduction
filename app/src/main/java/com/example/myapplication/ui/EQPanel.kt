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
    exclusiveMode: Boolean,
    onExclusiveChange: (Boolean) -> Unit,
    velocityOn: Boolean = true,
    onVelocityChange: (Boolean) -> Unit = {},
    allPadChokeGroups: List<List<Int>> = List(8) { emptyList() },
    onToggleChokeGroup: (Int, Int) -> Unit = { _, _ -> },
    activeChokeLevel: Int = 0,
    onSelectActiveChokeLevel: (Int) -> Unit = {},
    isRecording: Boolean,
    onRecordClick: () -> Unit,
    // Per-pad EQ + Level + Delay Time
    masterLevel: Float = 1f,
    eqLow: Float = 1f,
    eqMid: Float = 1f,
    eqHigh: Float = 1f,
    delayTimeMs: Int = 300,
    padLengthPct: Float = 1f,
    onPadLengthChange: (Float) -> Unit = {},
    onMasterLevelChange: (Float) -> Unit = {},
    onEqLowChange: (Float) -> Unit = {},
    onEqMidChange: (Float) -> Unit = {},
    onEqHighChange: (Float) -> Unit = {},
    onDelayTimeMsChange: (Int) -> Unit = {},
    // Per-pad FX: Reverse + explicit Save (PLAY MODE moved to the LOOP panel)
    padReverse: Boolean = false,
    onReverseChange: (Boolean) -> Unit = {},
    // NEW: per-pad Pan (-1f full left .. 1f full right) + Gain (0f..2f trim)
    padPan: Float = 0f,
    onPanChange: (Float) -> Unit = {},
    padGain: Float = 1f,
    onGainChange: (Float) -> Unit = {},
    // Delay on/off + decay ("DELAY LEVEL") — merged in from the old standalone
    // DelayPanel so every FX control lives in one place.
    delayEnabled: Boolean = false,
    onDelayEnabledChange: (Boolean) -> Unit = {},
    delayLevel: Float = 0.5f,
    onDelayLevelChange: (Float) -> Unit = {},
    delayChokePad: Int = -1,
    onDelayChokePadChange: (Int) -> Unit = {},
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
                            .size(22.dp)
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

                // ── DELAY (merged in from the old standalone Delay panel) ──────
                SectionLabel("DELAY")

                EqToggleRow(
                    title = "DELAY",
                    subtitle = if (delayEnabled) "On" else "Off",
                    enabled = delayEnabled,
                    activeColor = EqOrange,
                    onToggle = onDelayEnabledChange
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    EqKnobColumn(
                        label = "DLY TIME",
                        value = delayTimeMs.toFloat(),
                        min = 50f, max = 1000f,
                        color = EqOrange,
                        displayText = "${delayTimeMs}ms",
                        onValueChange = { onDelayTimeMsChange(it.toInt()) }
                    )
                    EqKnobColumn(
                        label = "DLY LEVEL",
                        value = delayLevel,
                        min = 0f, max = 1f,
                        color = EqOrange,
                        displayText = "${(delayLevel * 100).toInt()}%",
                        onValueChange = onDelayLevelChange
                    )
                }

                Text("APPLY TO", color = EqTextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                DelayApplyToPicker(delayChokePad = delayChokePad, onSelect = onDelayChokePadChange)

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
                    title = "EXCLUSIVE MODE",
                    subtitle = if (exclusiveMode) "Single Pad Playback" else "Multi Pad Playback",
                    enabled = exclusiveMode,
                    activeColor = EqAccent,
                    onToggle = onExclusiveChange
                )

                EqToggleRow(
                    title = "VELOCITY",
                    subtitle = if (velocityOn) "Hit strength affects volume" else "Always full volume",
                    enabled = velocityOn,
                    activeColor = EqGreen,
                    onToggle = onVelocityChange
                )

                if (exclusiveMode) {
                    ActiveChokeLevelSelector(
                        activeLevel = activeChokeLevel,
                        onSelect    = onSelectActiveChokeLevel
                    )
                    ChokeLevelsSection(
                        allPadChokeGroups = allPadChokeGroups,
                        onToggleLevel     = onToggleChokeGroup
                    )
                }

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

// ── Delay "APPLY TO" pad picker (ported from the old standalone DelayPanel) ────

@Composable
private fun DelayApplyToPicker(delayChokePad: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (delayChokePad == -1) EqAccent else Color(0xFF2A2A2A))
                .clickable(remember { MutableInteractionSource() }, null) { onSelect(-1) },
            contentAlignment = Alignment.Center
        ) {
            Text("ALL", color = if (delayChokePad == -1) Color.Black else EqTextMuted,
                 fontSize = 7.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (0..3).forEach { pad ->
                Box(
                    modifier = Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (delayChokePad == pad) EqAccent else Color(0xFF2A2A2A))
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(pad) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("${pad + 1}", color = if (delayChokePad == pad) Color.Black else EqTextMuted,
                         fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (4..7).forEach { pad ->
                Box(
                    modifier = Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (delayChokePad == pad) EqAccent else Color(0xFF2A2A2A))
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(pad) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("${pad + 1}", color = if (delayChokePad == pad) Color.Black else EqTextMuted,
                         fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
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
private fun ActiveChokeLevelSelector(activeLevel: Int, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(EqPanelBg).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("ACTIVE LEVEL", color = EqTextWht, fontSize = 9.sp,
             fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(
            if (activeLevel != 0) "Level $activeLevel is live" else "No level active — tap one below",
            color = if (activeLevel != 0) EqAccent else EqTextMuted, fontSize = 8.sp
        )
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..6).forEach { level ->
                val selected = level == activeLevel
                Box(
                    modifier = Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (selected) EqGreen else Color(0xFF2A2A2A))
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(level) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("$level", color = if (selected) Color.Black else EqTextMuted,
                         fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ChokeLevelsSection(allPadChokeGroups: List<List<Int>>, onToggleLevel: (Int, Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(EqPanelBg).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("CHOKE LEVELS", color = EqTextWht, fontSize = 9.sp,
             fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text("Pick which pads belong to each level", color = EqTextMuted, fontSize = 8.sp)
        (1..6).forEach { level ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("LEVEL $level", color = EqAccent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..7).forEach { padIndex ->
                        val sel = level in allPadChokeGroups[padIndex]
                        Box(
                            modifier = Modifier.weight(1f).height(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (sel) EqAccent else Color(0xFF2A2A2A))
                                .clickable(remember { MutableInteractionSource() }, null) { onToggleLevel(padIndex, level) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${padIndex + 1}", color = if (sel) Color.Black else EqTextMuted,
                                 fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
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

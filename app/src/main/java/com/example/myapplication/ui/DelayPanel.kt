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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DelayBg        = Color(0xFF111111)
private val DelayPanelBg   = Color(0xFF1A1A1A)
private val DelayAccent    = Color(0xFFFFB74D)
private val DelayDivider   = Color(0xFF2A2A2A)
private val DelayTextMuted = Color(0xFF888888)
private val DelayTextWht   = Color(0xFFEEEEEE)

/**
 * DelayPanel — dedicated top-level DELAY button/panel, next to CROP/CHOKE.
 * Used to live buried inside FX; pulled out on its own per explicit request
 * so DELAY controls aren't mixed in with unrelated FX knobs.
 *
 * Two separate switches, both real:
 *  - MASTER (this panel's header toggle): a global kill switch. OFF mutes
 *    delay everywhere immediately, regardless of which pads have their own
 *    delay flag set. Turning it back ON does NOT re-enable delay on every
 *    pad — it restores exactly whichever pads had their own per-pad flag on
 *    before it was muted, since that flag is untouched by this switch.
 *  - PAD DELAY (per selected pad, per kit — same as before): whether THIS
 *    pad, in THIS kit, uses delay at all. Persists with the kit like every
 *    other per-pad FX setting.
 */
@Composable
fun DelayPanel(
    visible: Boolean,
    selectedPad: Int = 0,
    masterEnabled: Boolean = true,
    onMasterEnabledChange: (Boolean) -> Unit = {},
    padDelayEnabled: Boolean = false,
    onPadDelayEnabledChange: (Boolean) -> Unit = {},
    delayTimeMs: Int = 300,
    onDelayTimeMsChange: (Int) -> Unit = {},
    delayLevel: Float = 0.5f,
    onDelayLevelChange: (Float) -> Unit = {},
    delayChokePad: Int = -1,
    onDelayChokePadChange: (Int) -> Unit = {},
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
                .background(DelayBg)
                .border(1.dp, DelayDivider, RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "DELAY", color = DelayAccent,
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
                        Text("✕", color = DelayTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                DelayDividerLine()

                DelayToggleRow(
                    title = "MASTER",
                    subtitle = if (masterEnabled) "Delay active on every pad that has it" else "Delay muted everywhere",
                    enabled = masterEnabled,
                    onToggle = onMasterEnabledChange
                )

                DelayDividerLine()

                Text("PAD ${selectedPad + 1}", color = DelayTextMuted, fontSize = 8.sp,
                     fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

                DelayToggleRow(
                    title = "DELAY",
                    subtitle = if (padDelayEnabled) "On for this pad" else "Off for this pad",
                    enabled = padDelayEnabled,
                    onToggle = onPadDelayEnabledChange
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    LinearSlider(
                        title = "DLY TIME",
                        value = delayTimeMs.toFloat(),
                        min = 50f, max = 1000f,
                        displayText = "${delayTimeMs}ms",
                        accentColor = DelayAccent,
                        trackHeight = 84.dp,
                        trackWidth = 18.dp,
                        onValueChange = { onDelayTimeMsChange(it.toInt()) }
                    )
                    LinearSlider(
                        title = "DLY LEVEL",
                        value = delayLevel,
                        min = 0f, max = 1f,
                        displayText = "${(delayLevel * 100).toInt()}%",
                        accentColor = DelayAccent,
                        trackHeight = 84.dp,
                        trackWidth = 18.dp,
                        onValueChange = onDelayLevelChange
                    )
                }

                Text("APPLY TO", color = DelayTextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                DelayApplyToPicker(delayChokePad = delayChokePad, onSelect = onDelayChokePadChange)
            }
        }
    }
}

@Composable
private fun DelayApplyToPicker(delayChokePad: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (delayChokePad == -1) DelayAccent else Color(0xFF2A2A2A))
                .clickable(remember { MutableInteractionSource() }, null) { onSelect(-1) },
            contentAlignment = Alignment.Center
        ) {
            Text("ALL", color = if (delayChokePad == -1) Color.Black else DelayTextMuted,
                 fontSize = 7.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (0..3).forEach { pad ->
                Box(
                    modifier = Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (delayChokePad == pad) DelayAccent else Color(0xFF2A2A2A))
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(pad) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("${pad + 1}", color = if (delayChokePad == pad) Color.Black else DelayTextMuted,
                         fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (4..7).forEach { pad ->
                Box(
                    modifier = Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (delayChokePad == pad) DelayAccent else Color(0xFF2A2A2A))
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(pad) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("${pad + 1}", color = if (delayChokePad == pad) Color.Black else DelayTextMuted,
                         fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DelayToggleRow(title: String, subtitle: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DelayPanelBg)
            .clickable(remember { MutableInteractionSource() }, null) { onToggle(!enabled) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = DelayTextWht, fontSize = 9.sp,
                 fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(subtitle, color = if (enabled) DelayAccent else DelayTextMuted, fontSize = 8.sp)
        }
        val thumbOffset by animateFloatAsState(
            targetValue = if (enabled) 1f else 0f, animationSpec = tween(160), label = "delayPillThumb"
        )
        Box(
            modifier = Modifier.width(38.dp).height(20.dp).clip(RoundedCornerShape(50))
                .background(if (enabled) DelayAccent.copy(alpha = 0.25f) else Color(0xFF2A2A2A))
                .border(1.dp, if (enabled) DelayAccent.copy(alpha = 0.6f) else Color(0xFF3A3A3A), RoundedCornerShape(50))
        ) {
            Box(
                modifier = Modifier.size(14.dp)
                    .offset(x = (2f + thumbOffset * 18f).dp, y = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (enabled) DelayAccent else Color(0xFF555555))
            )
        }
    }
}

@Composable
private fun DelayDividerLine() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DelayDivider))
}

package com.example.myapplication.ui

import androidx.compose.animation.AnimatedVisibility
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

private val ChokeBg        = Color(0xFF111111)
private val ChokePanelBg   = Color(0xFF1A1A1A)
private val ChokeAccent    = Color(0xFF00E5FF)
private val ChokeGreen     = Color(0xFF00C853)
private val ChokeDivider   = Color(0xFF2A2A2A)
private val ChokeTextMuted = Color(0xFF888888)
private val ChokeTextWht   = Color(0xFFEEEEEE)

/**
 * ChokePanel — dedicated top-level CHOKE button/panel, next to CROP. Used to
 * live buried inside FX (behind an EXCLUSIVE MODE toggle three taps deep) —
 * pulled out on its own since choke groups are used constantly during live
 * playing, not an occasional setting.
 *
 * Exclusive mode (the on/off switch that makes choke groups active at all)
 * lives here too now, since it has no meaning on its own without choke
 * levels to assign — VELOCITY stays in FX since it's unrelated.
 *
 * Levels are None/1/2/3/4 (trimmed down from 1..6) and a pad belongs to at
 * most ONE level at a time now — picking a level for a pad is a single
 * choice, not a multi-select toggle. "CHOKE LEVELS" below is organized as
 * one collapsible folder per level (1-4): collapsed it just shows which
 * pads are currently assigned, tap to expand and change that level's
 * pads — instead of one long list showing every level's full pad grid at
 * once.
 */
@Composable
fun ChokePanel(
    visible: Boolean,
    exclusiveMode: Boolean,
    onExclusiveChange: (Boolean) -> Unit,
    allPadChokeGroups: List<List<Int>> = List(8) { emptyList() },
    onToggleChokeGroup: (Int, Int) -> Unit = { _, _ -> },
    activeChokeLevel: Int = 0,
    onSelectActiveChokeLevel: (Int) -> Unit = {},
    onClose: () -> Unit,
    width: androidx.compose.ui.unit.Dp = 220.dp
) {
    // Which level's folder is currently expanded (null = all collapsed).
    // Only one open at a time keeps the panel from growing unbounded.
    var expandedLevel by remember { mutableStateOf<Int?>(null) }
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
                .background(ChokeBg)
                .border(1.dp, ChokeDivider, RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
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
                        "CHOKE", color = ChokeAccent,
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
                        Text("✕", color = ChokeTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ChokeDivider))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ChokePanelBg)
                        .clickable(remember { MutableInteractionSource() }, null) { onExclusiveChange(!exclusiveMode) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("EXCLUSIVE MODE", color = ChokeTextWht, fontSize = 9.sp,
                             fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Text(
                            if (exclusiveMode) "Choke groups active" else "Turn on to use choke groups",
                            color = if (exclusiveMode) ChokeAccent else ChokeTextMuted, fontSize = 8.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(38.dp).height(20.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (exclusiveMode) ChokeAccent.copy(alpha = 0.25f) else Color(0xFF2A2A2A))
                            .border(1.dp, if (exclusiveMode) ChokeAccent.copy(alpha = 0.6f) else Color(0xFF3A3A3A), RoundedCornerShape(50))
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(3.dp).size(14.dp)
                                .offset(x = if (exclusiveMode) 18.dp else 0.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (exclusiveMode) ChokeAccent else Color(0xFF555555))
                        )
                    }
                }

                if (exclusiveMode) {
                    EqDividerLine()

                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(ChokePanelBg).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("ACTIVE LEVEL", color = ChokeTextWht, fontSize = 9.sp,
                             fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Text(
                            if (activeChokeLevel != 0) "Level $activeChokeLevel is live" else "None — pick a level below",
                            color = if (activeChokeLevel != 0) ChokeAccent else ChokeTextMuted, fontSize = 8.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // NEW: None/1/2/3/4 — trimmed from 1..6, and NONE
                            // is now an explicit tappable option instead of
                            // only being reachable by tapping an already-
                            // active level to deselect it. onSelectActiveChokeLevel
                            // toggles (sets to 0 if the same level is already
                            // active, otherwise sets that level) — calling it
                            // with 0 always lands on 0 either way (0==0 stays
                            // 0; anything else != 0 so it sets to 0), so NONE
                            // just calls it directly, same as every other tile.
                            listOf(0, 1, 2, 3, 4).forEach { level ->
                                val selected = level == activeChokeLevel
                                Box(
                                    modifier = Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(6.dp))
                                        .background(if (selected) ChokeGreen else Color(0xFF2A2A2A))
                                        .clickable(remember { MutableInteractionSource() }, null) {
                                            onSelectActiveChokeLevel(level)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (level == 0) "NONE" else "$level", color = if (selected) Color.Black else ChokeTextMuted,
                                         fontSize = if (level == 0) 8.sp else 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(ChokePanelBg).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("CHOKE LEVELS", color = ChokeTextWht, fontSize = 9.sp,
                             fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Text("Each pad belongs to at most one level — tap a folder to open it", color = ChokeTextMuted, fontSize = 8.sp)
                        (1..4).forEach { level ->
                            val padsInLevel = (0..7).filter { pad -> level in allPadChokeGroups[pad] }
                            val isOpen = expandedLevel == level
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF141414))
                            ) {
                                // Folder header — tap to expand/collapse.
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable(remember { MutableInteractionSource() }, null) {
                                            expandedLevel = if (isOpen) null else level
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("LEVEL $level", color = ChokeAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            if (padsInLevel.isEmpty()) "No pads"
                                            else "Pad " + padsInLevel.joinToString(", ") { (it + 1).toString() },
                                            color = ChokeTextMuted, fontSize = 8.sp
                                        )
                                    }
                                    Text(if (isOpen) "▲" else "▼", color = ChokeTextMuted, fontSize = 9.sp)
                                }

                                if (isOpen) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        (0..7).forEach { padIndex ->
                                            val sel = level in allPadChokeGroups[padIndex]
                                            Box(
                                                modifier = Modifier.weight(1f).height(24.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(if (sel) ChokeAccent else Color(0xFF2A2A2A))
                                                    .clickable(remember { MutableInteractionSource() }, null) { onToggleChokeGroup(padIndex, level) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("${padIndex + 1}", color = if (sel) Color.Black else ChokeTextMuted,
                                                     fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EqDividerLine() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ChokeDivider))
}

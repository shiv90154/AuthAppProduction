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
private val ChokeDivider   = Color(0xFF2A2A2A)
private val ChokeTextMuted = Color(0xFF888888)
private val ChokeTextWht   = Color(0xFFEEEEEE)

/**
 * ChokePanel — dedicated top-level CHOKE button/panel, next to CROP.
 *
 * Simplified to just NONE/1/2/3/4: no EXCLUSIVE MODE toggle and no ACTIVE
 * LEVEL selector — those were an extra layer of gating on top of choke-
 * group membership that mostly just meant choke silently did nothing until
 * both were set correctly (reported as "choke kaam nahi kar raha hai").
 * Now choke groups are simply always live: any two pads sharing the same
 * non-NONE level always choke each other on hit, reciprocally, with
 * nothing else to turn on first.
 *
 * A pad belongs to at most ONE level at a time — picking a level for a pad
 * is a single choice, not a multi-select toggle. "CHOKE LEVELS" below is
 * organized as one collapsible folder per level (1-4): collapsed it just
 * shows which pads are currently assigned, tap to expand and change that
 * level's pads — instead of one long list showing every level's full pad
 * grid at once.
 */
@Composable
fun ChokePanel(
    visible: Boolean,
    allPadChokeGroups: List<List<Int>> = List(8) { emptyList() },
    onToggleChokeGroup: (Int, Int) -> Unit = { _, _ -> },
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
                            .size(28.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF2A2A2A))
                            .clickable(remember { MutableInteractionSource() }, null) { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✕", color = ChokeTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ChokeDivider))

                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(ChokePanelBg).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("CHOKE LEVELS", color = ChokeTextWht, fontSize = 9.sp,
                         fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    Text("Pads in the same level always choke each other — tap a folder to open it", color = ChokeTextMuted, fontSize = 8.sp)
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

@Composable
private fun EqDividerLine() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ChokeDivider))
}

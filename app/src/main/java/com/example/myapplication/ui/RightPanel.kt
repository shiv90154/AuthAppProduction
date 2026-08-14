package com.example.myapplication.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource

@Composable
fun RightPanel(
    selectedPad: Int,
    padVolume: Float,
    padPitch: Float,
    onVolumeChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    kits: List<Kit>,
    currentKit: Int,
    // Which kit the LCD name label should actually show — Bank A's
    // currentKit when Bank A is (part of) the active selection, otherwise
    // whichever bank's kit the VOL/PITCH sliders are currently editing.
    // Defaults to currentKit so any other call site keeps its old behavior.
    bankKitIdx: Int = currentKit,
    onKitAdd: () -> Unit,
    onKitDelete: () -> Unit,
    onKitPrev: () -> Unit,
    onKitNext: () -> Unit,
    onOpenKitList: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenAudios: () -> Unit,
    onOpenExport: () -> Unit,
    onRenameKit: () -> Unit,
    playingPadUri: Uri? = null,
    playingDefaultResId: Int = 0,       // NEW: factory kit raw res for waveform fallback
    playbackPositionMs: Long = 0L,
    playbackDurationMs: Long = 0L,
    waveformVersion: Int = 0,
    // ── NEW: EQ panel state ───────────────────────────────────────────────────
    loopEnabled: Boolean = false,
    exclusiveMode: Boolean = false,
    velocityOn: Boolean = true,
    onLoopChange: (Boolean) -> Unit = {},
    onExclusiveChange: (Boolean) -> Unit = {},
    onVelocityChange: (Boolean) -> Unit = {},
    allPadChokeGroups: List<List<Int>> = List(8) { emptyList() },
    onToggleChokeGroup: (Int, Int) -> Unit = { _, _ -> },
    // NEW: single active choke level (0 = none)
    activeChokeLevel: Int = 0,
    onSelectActiveChokeLevel: (Int) -> Unit = {},
    isRecording: Boolean,
    onRecordClick: () -> Unit,
    onOpenEdit: () -> Unit = {},
    onImportToPad: () -> Unit = {},
    // NEW: EDIT MODE — round button below DLY. When on, tapping any pad
    // opens a contextual edit menu (Clear Sound / Add Sound) instead of
    // playing it — see OctapadScreen's editModeOn gate in onPadHit().
    editModeOn: Boolean = false,
    onEditModeChange: (Boolean) -> Unit = {},
    onOpenMapMidi: () -> Unit = {},
    onOpenLoadKit: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenImportPatch: () -> Unit = {},
    bpm: Int = 120,
    onBpmChange: (Int) -> Unit = {},

    // Delay — now surfaced inside the FX panel (EQPanel), not a standalone panel
    delayEnabled: Boolean = false,
    delayChokePad: Int = -1,
    onDelayEnabledChange: (Boolean) -> Unit = {},
    onDelayChokePadChange: (Int) -> Unit = {},
    delayTimeMs: Int = 300,
    onDelayTimeChange: (Int) -> Unit = {},
    delayLevel: Float = 0.5f,
    onDelayLevelChange: (Float) -> Unit = {},
    padLengthPct: Float = 1f,
    onPadLengthChange: (Float) -> Unit = {},

    // NEW: LOOP group's SPEED control
    speed: Float = 1f,
    onSpeedChange: (Float) -> Unit = {},

    // EQ + Level
    masterLevel: Float = 1f,
    eqLow: Float = 1f,
    eqMid: Float = 1f,
    eqHigh: Float = 1f,
    onMasterLevelChange: (Float) -> Unit = {},
    onEqLowChange: (Float) -> Unit = {},
    onEqMidChange: (Float) -> Unit = {},
    onEqHighChange: (Float) -> Unit = {},

    // Per-pad FX: Reverse + playback mode + explicit Save
    padReverse: Boolean = false,
    onReverseChange: (Boolean) -> Unit = {},
    padPlayMode: String = "ONESHOT",
    onPlayModeChange: (String) -> Unit = {},
    onSaveClick: () -> Unit = {},

    // NEW: per-pad Pan (-1f..1f, center 0f) + Gain (0f..2f, unity 1f)
    padPan: Float = 0f,
    onPanChange: (Float) -> Unit = {},
    padGain: Float = 1f,
    onGainChange: (Float) -> Unit = {},

    // A/B/C Bank — bankMode is a string containing any subset of "ABC";
    // NEW: single-select (tapping A/B/C plays ONLY that bank, replacing
    // whatever was active) + a separate ALL button (A+B+C together) —
    // onBankModeSelect sets bankMode directly rather than toggling one
    // letter's membership in a multi-select set.
    bankMode: String = "A",
    onBankModeSelect: (String) -> Unit = {},
    kitBName: String = "",
    onKitBPrev: () -> Unit = {},
    onKitBNext: () -> Unit = {},
    kitCName: String = "",
    onKitCPrev: () -> Unit = {},
    onKitCNext: () -> Unit = {},
    // Responsive width (see BoxWithConstraints in OctapadScreen) — ~20% of
    // screen width, clamped — instead of one fixed dp tuned for one device.
    controlPanelWidth: Dp = 190.dp
) {
    var activeBtn by remember { mutableStateOf("") }

    var showEqPanel  by remember { mutableStateOf(false) }
    var showMusicPanel by remember { mutableStateOf(false) }
    var showTempoPanel by remember { mutableStateOf(false) }
    // NEW: CHOKE now has its own dedicated panel/button next to CROP,
    // instead of living buried inside FX behind EXCLUSIVE MODE.
    var showChokePanel by remember { mutableStateOf(false) }

    // Same proportions the original fixed dp values had relative to the
    // 190dp main column (220/200/170), now scaled off the responsive
    // controlPanelWidth instead of hardcoded per-panel constants. Delay
    // controls live inside the FX (EQPanel) panel now — no separate width.
    val eqPanelWidth    = controlPanelWidth * 1.16f
    val tempoPanelWidth = controlPanelWidth * 1.05f
    val musicPanelWidth = controlPanelWidth * 0.89f

    // Wrap everything in a Row so EQPanel slides in to the LEFT of RightPanel
    Row(modifier = Modifier.fillMaxHeight()) {

        // ── EQ Panel — animates in from the left side ─────────────────────────
        AnimatedVisibility(
            visible = showEqPanel,
            enter   = slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(220)) + fadeIn(tween(220)),
            exit    = slideOutHorizontally(targetOffsetX  = { -it }, animationSpec = tween(180)) + fadeOut(tween(180))
        ) {
            EQPanel(
                width = eqPanelWidth,
                visible = showEqPanel,
                selectedPad = selectedPad,
                velocityOn = velocityOn,
                onVelocityChange = onVelocityChange,
                isRecording = isRecording,
                onRecordClick = onRecordClick,
                masterLevel = masterLevel,
                eqLow = eqLow, eqMid = eqMid, eqHigh = eqHigh,
                delayTimeMs = delayTimeMs,
                padLengthPct = padLengthPct,
                onPadLengthChange = onPadLengthChange,
                onMasterLevelChange = onMasterLevelChange,
                onEqLowChange = onEqLowChange,
                onEqMidChange = onEqMidChange,
                onEqHighChange = onEqHighChange,
                onDelayTimeMsChange = onDelayTimeChange,
                padReverse = padReverse,
                onReverseChange = onReverseChange,
                padPan = padPan,
                onPanChange = onPanChange,
                padGain = padGain,
                onGainChange = onGainChange,
                delayEnabled = delayEnabled,
                onDelayEnabledChange = onDelayEnabledChange,
                delayLevel = delayLevel,
                onDelayLevelChange = onDelayLevelChange,
                delayChokePad = delayChokePad,
                onDelayChokePadChange = onDelayChokePadChange,
                onImportToPad = {
                    showEqPanel = false
                    onImportToPad()
                },
                onSaveClick = onSaveClick,
                onClose = { showEqPanel = false }
            )
        }

        // ── Choke Panel — dedicated top-level panel next to CROP ───────────────
        AnimatedVisibility(
            visible = showChokePanel,
            enter   = slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(220)) + fadeIn(tween(220)),
            exit    = slideOutHorizontally(targetOffsetX  = { -it }, animationSpec = tween(180)) + fadeOut(tween(180))
        ) {
            ChokePanel(
                width = eqPanelWidth,
                visible = showChokePanel,
                exclusiveMode = exclusiveMode,
                onExclusiveChange = onExclusiveChange,
                allPadChokeGroups = allPadChokeGroups,
                onToggleChokeGroup = onToggleChokeGroup,
                activeChokeLevel = activeChokeLevel,
                onSelectActiveChokeLevel = onSelectActiveChokeLevel,
                onClose = { showChokePanel = false }
            )
        }

        AnimatedVisibility(
            visible = showTempoPanel,
            enter   = slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(220)) + fadeIn(tween(220)),
            exit    = slideOutHorizontally(targetOffsetX  = { -it }, animationSpec = tween(180)) + fadeOut(tween(180))
        ) {
            TempoPanel(
                width = tempoPanelWidth,
                bpm = bpm,
                loopEnabled = loopEnabled,           // MOVED from EQPanel
                onBpmChange = onBpmChange,
                onLoopChange = onLoopChange,         // MOVED from EQPanel
                exclusiveMode = exclusiveMode,       // NEW: CHOKE quick-toggle
                onExclusiveChange = onExclusiveChange,
                speed = speed,
                onSpeedChange = onSpeedChange,
                padPlayMode = padPlayMode,           // MOVED from EQ/FX panel
                onPlayModeChange = onPlayModeChange,
                onClose = { showTempoPanel = false }
            )
        }

        AnimatedVisibility(
            visible = showMusicPanel,
            enter = slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(220)
            ) + fadeIn(tween(220)),
            exit = slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = tween(180)
            ) + fadeOut(tween(180))
        ) {
            MusicPanel(
                width = musicPanelWidth,
                onImport  = onOpenImport,
                onAudios  = onOpenAudios,
                onExport  = onOpenExport,
                onLoadKit = {
                    showMusicPanel = false
                    onOpenLoadKit()
                },
                onMapMidi = {
                    showMusicPanel = false
                    onOpenMapMidi()
                },
                onBackup = {
                    showMusicPanel = false
                    onOpenBackup()
                },
                onImportPatch = {
                    showMusicPanel = false
                    onOpenImportPatch()
                },
                onRename  = onRenameKit,
                onClose   = {
                    showMusicPanel = false
                    activeBtn = ""
                }
            )
        }

        // ── Main right column ───────────────────────────────────────────────
        // BUG FIX: verticalScroll removed per explicit request — the panel
        // must read as a fixed hardware LCD/control strip, not a scrollable
        // list. If content ever gets clipped on very short screens again,
        // fix that by trimming spacing/sizes, not by re-adding scroll here.
        Column(
            modifier = Modifier
                .width(controlPanelWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(PanelBg)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {

            // ── PATCH LIST / EDITxx PADS / REC ──────────────────────────────────


            // ── EQ / MIDI / MUSIC ─────────────────────────────────────────────
            // EQ button: toggle EQPanel; other buttons: close EQPanel
            Text(
                text = "ARUN SPD 30",
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            CtrlBtnRow(
                labels = listOf("CROP", "CHOKE"),
                active = activeBtn,
                onSelect = { label ->
                    activeBtn = label
                    showEqPanel = false
                    showMusicPanel = false
                    showTempoPanel = false
                    showChokePanel = false
                    when (label) {
                        "CROP" -> onOpenEdit()   // open WaveformEditorScreen
                        "CHOKE" -> {
                            showChokePanel = true
                            // Choke groups do nothing without Exclusive Mode on, and it
                            // used to require a separate manual tap inside the panel —
                            // turn it on the moment CHOKE is opened so the level/group
                            // picker (gated on exclusiveMode below) is usable immediately.
                            onExclusiveChange(true)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // NEW: SAVE + LOAD quick-access on the main display, so both are
            // reachable without a detour through FX/SETTINGS.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                var savedFlash by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (savedFlash) Color(0xFF00C853) else BtnBg)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                onSaveClick()
                                savedFlash = true
                            }
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (savedFlash) "SAVED ✓" else "SAVE",
                        color = if (savedFlash) Color.Black else Color(0xFFCCCCCC),
                        fontSize = 8.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                    )
                }
                LaunchedEffect(savedFlash) {
                    if (savedFlash) {
                        kotlinx.coroutines.delay(1200)
                        savedFlash = false
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BtnBg)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                activeBtn = ""
                                showEqPanel = false
                                showMusicPanel = false
                                showTempoPanel = false
                                showChokePanel = false
                                onOpenLoadKit()
                            }
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("LOAD", color = Color(0xFFCCCCCC), fontSize = 8.sp,
                         fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            CtrlBtnRow(
                labels = listOf("FX", "LOOP", "SETTINGS"),
                active = activeBtn,
                onSelect = { label ->
                    activeBtn = label

                    when(label){

                        "FX" ->{
                            showEqPanel = !showEqPanel
                            showMusicPanel = false
                            showTempoPanel = false
                            showChokePanel = false
                        }

                        "LOOP" ->{
                            showTempoPanel = !showTempoPanel
                            showEqPanel = false
                            showMusicPanel = false
                            showChokePanel = false
                        }

                        "SETTINGS" ->{
                            showMusicPanel = !showMusicPanel
                            showEqPanel = false
                            showTempoPanel = false
                            showChokePanel = false
                        }

                        else->{
                            showEqPanel = false
                            showMusicPanel = false
                            showTempoPanel = false
                            showChokePanel = false
                        }
                    }
                }
            )


            // ── Pad volume / pitch knobs ───────────────────────────────────────
            // BUG FIX: gap tightened (padding/spacers shrunk) — was taking up
            // more vertical room than needed now that the panel can't scroll.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1A1A))
                    .padding(4.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "PAD ${selectedPad + 1}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // NEW: kit name — the old LCD block used to show this;
                    // brought back as a plain text line (no waveform) so the
                    // active kit is still visible without the removed panel.
                    // Renders whatever's in kits[bankKitIdx].name as-is,
                    // including Hindi/Devanagari — Text() uses the system
                    // default font here, which already covers Devanagari via
                    // Android's normal font-fallback, no extra font needed.
                    //
                    // BUG FIX: this used to always read kits[currentKit]
                    // (Bank A's name) regardless of which bank was selected —
                    // with Bank B/C active (and the VOL/PITCH sliders right
                    // below correctly editing that bank's kit), the LCD
                    // showed the wrong kit's name entirely. bankKitIdx is
                    // passed in from OctapadScreen (same value the sliders
                    // use) instead of the raw currentKit param.
                    if (bankKitIdx in kits.indices) {
                        Text(
                            text = kits[bankKitIdx].name,
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {

                            // BUG FIX: track height trimmed (96dp -> 68dp) —
                            // was taller than needed for this compact panel.
                            LinearSlider(
                                title = "VOL",
                                value = padVolume,
                                onValueChange = onVolumeChange,
                                min = 0f,
                                max = 2f,
                                displayText = "${(padVolume * 100).toInt()}%",
                                accentColor = Color(0xFF00E5FF),
                                trackHeight = 68.dp
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (delayEnabled) Color(0xFF00E5FF) else Color(0xFF3A3A3A))
                                    // NEW: direct one-tap on/off, no need to open FX first —
                                    // asked for explicitly for live/stage use, where digging
                                    // into a submenu mid-performance isn't practical. Long-press
                                    // still opens FX for timing/level tweaks. Single gesture
                                    // detector handling both, not two competing ones.
                                    .pointerInput(delayEnabled) {
                                        detectTapGestures(
                                            onTap = { onDelayEnabledChange(!delayEnabled) },
                                            onLongPress = {
                                                showEqPanel = true
                                                showMusicPanel = false
                                                showTempoPanel = false
                                                showChokePanel = false
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "DLY",
                                    color = if (delayEnabled) Color.Black else Color(0xFFCCCCCC),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(6.dp))

                            // NEW: EDIT MODE — round button, right below DLY.
                            // While on, tapping any pad opens a contextual
                            // Clear/Add-Sound menu instead of playing it.
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (editModeOn) Color(0xFFFFB74D) else Color(0xFF3A3A3A))
                                    .pointerInput(Unit) {
                                        detectTapGestures { onEditModeChange(!editModeOn) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "EDIT",
                                    color = if (editModeOn) Color.Black else Color(0xFFCCCCCC),
                                    fontSize = 7.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {

                            LinearSlider(
                                title = "PITCH",
                                value = padPitch,
                                onValueChange = onPitchChange,
                                min = 0.5f,
                                max = 2f,
                                displayText = "${"%.2f".format(padPitch)}x",
                                accentColor = Color(0xFFFFB74D),
                                trackHeight = 68.dp
                            )
                        }
                    }
                }
            }



            // NEW: LCD waveform/timer block removed per explicit request —
            // was taking up vertical space the panel doesn't have room for
            // now that scrolling is gone.

            // ── A/B/C Bank selector — A/B/C are single-select (tapping one
            // plays ONLY that bank), plus two explicit combos: A+B and ALL
            // (A+B+C). Split into two rows — "BANK A/B/C" + "A+B"/"ALL" all
            // in one row was too cramped for this panel's width.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf('A', 'B', 'C').forEach { letter ->
                    val selected = bankMode == letter.toString()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) BtnActive else BtnBg)
                            .pointerInput(Unit) { detectTapGestures { onBankModeSelect(letter.toString()) } }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "BANK $letter",
                            color = if (selected) Color.Black else Color(0xFFCCCCCC),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // NEW: A+B — plays Bank A and Bank B together, C stays off.
                run {
                    val abSelected = bankMode == "AB"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (abSelected) BtnActive else BtnBg)
                            .pointerInput(Unit) { detectTapGestures { onBankModeSelect("AB") } }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "A+B",
                            color = if (abSelected) Color.Black else Color(0xFFCCCCCC),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                run {
                    val allSelected = bankMode == "ABC"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (allSelected) BtnActive else BtnBg)
                            .pointerInput(Unit) { detectTapGestures { onBankModeSelect("ABC") } }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "ALL",
                            color = if (allSelected) Color.Black else Color(0xFFCCCCCC),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Bank B kit selector — only shown while Bank B is actually audible
            if ('B' in bankMode) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(50))
                            .background(NavRed)
                            .pointerInput(Unit) { detectTapGestures { onKitBPrev() } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("<", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text(
                        "B: $kitBName",
                        color = Color(0xFFFFB74D),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        // BUG FIX: without a bounded width, a long kit name
                        // pushed this Row past the panel's edge — the ">"
                        // button on the right could end up clipped off-screen
                        // entirely, un-tappable. weight(1f) keeps both nav
                        // buttons always visible regardless of name length.
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(50))
                            .background(NavRed)
                            .pointerInput(Unit) { detectTapGestures { onKitBNext() } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(">", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            // Bank C kit selector — only shown while Bank C is actually audible
            if ('C' in bankMode) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(50))
                            .background(NavRed)
                            .pointerInput(Unit) { detectTapGestures { onKitCPrev() } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("<", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text(
                        "C: $kitCName",
                        color = Color(0xFFFFB74D),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(50))
                            .background(NavRed)
                            .pointerInput(Unit) { detectTapGestures { onKitCNext() } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(">", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Navigation Row  < PATCH > ──────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(NavRed)
                        .pointerInput(Unit) { detectTapGestures { onKitPrev() } },
                    contentAlignment = Alignment.Center
                ) {
                    Text("<", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(BtnBg)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                // Force-close every side panel first so the
                                // PATCH LIST full-screen overlay is never
                                // left underneath a stale panel on top of it.
                                activeBtn = ""
                                showEqPanel = false
                                showMusicPanel = false
                                showTempoPanel = false
                                showChokePanel = false
                                onOpenKitList()
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "PATCH LIST",
                        color = Color(0xFFCCCCCC),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(NavRed)
                        .pointerInput(Unit) { detectTapGestures { onKitNext() } },
                    contentAlignment = Alignment.Center
                ) {
                    Text(">", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

// NEW: the waveform/LCD block that used to render here was removed per
// explicit request (RightPanel.kt no longer calls WaveformDisplay), so the
// WaveformDisplay composable and its toTimerStr() helper were dead code —
// deleted rather than left unreferenced.

// ── Menu option ───────────────────────────────────────────────────────────────

@Composable
private fun MenuOption(icon: String, label: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(icon, fontSize = 12.sp)
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
fun CtrlBtnRow(labels: List<String>, active: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        labels.forEach { label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (label == active) BtnActive else BtnBg)
                    .pointerInput(Unit) { detectTapGestures { onSelect(label) } }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color      = if (label == active) Color.Black else Color(0xFFCCCCCC),
                    fontSize   = 8.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun LcdRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 8.sp, color = Color(0xFF001A33))
        Text(value, fontSize = 8.sp, color = Color(0xFF001A33))
    }
}

// ── LinearSlider — elongated vertical fader replacing the old round knobs ─────
// Shows a tall track with a fill bar + numeric readout; drag anywhere on the
// track (or tap directly on a point) to set the value.

@Composable
fun LinearSlider(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    displayText: String,
    accentColor: Color = Color(0xFF00E5FF),
    trackHeight: androidx.compose.ui.unit.Dp = 96.dp,
    trackWidth: androidx.compose.ui.unit.Dp = 22.dp,
    onValueChange: (Float) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (title.isNotEmpty()) {
            Text(text = title, color = Color.White, fontSize = 8.sp)
        }
        Text(text = displayText, color = accentColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(3.dp))

        val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)

        Canvas(
            modifier = Modifier
                .width(trackWidth)
                .height(trackHeight)
                .pointerInput(min, max) {
                    fun applyAtY(y: Float) {
                        val f = (1f - (y / size.height)).coerceIn(0f, 1f)
                        onValueChange(min + f * (max - min))
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.pressed) {
                                applyAtY(change.position.y)
                                change.consume()
                            }
                        }
                    }
                }
        ) {
            val trackColor = Color(0xFF2A2A2A)
            drawRoundRect(
                color = trackColor,
                cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
            )
            val fillHeight = size.height * fraction
            drawRoundRect(
                color = accentColor,
                topLeft = Offset(0f, size.height - fillHeight),
                size = androidx.compose.ui.geometry.Size(size.width, fillHeight),
                cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
            )
            // Thumb line
            val thumbY = size.height - fillHeight
            drawLine(
                color = Color.White,
                start = Offset(0f, thumbY),
                end = Offset(size.width, thumbY),
                strokeWidth = 3f
            )
        }
    }
}


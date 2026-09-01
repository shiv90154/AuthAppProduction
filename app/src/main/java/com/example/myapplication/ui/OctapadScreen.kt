// OctapadScreen.kt
package com.example.myapplication.ui
import com.example.myapplication.LatencyTracker
import com.example.myapplication.MidiEventBus
import com.example.myapplication.MidiLearnRepository
import android.media.SoundPool
import android.net.Uri
import com.example.myapplication.NativeBridge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.audio.AudioListScreen
import com.example.myapplication.ui.audio.AudioRepository
import com.example.myapplication.ui.audio.ExportScreen
import com.example.myapplication.ui.audio.ImportScreen
import com.example.myapplication.ui.audio.WaveformEditorScreen
import com.example.myapplication.ui.audio.LoadKitScreen
import com.example.myapplication.ui.kit.KitListScreen
import com.example.myapplication.ui.pads.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.myapplication.ui.drag.DragPadOverlay
import com.example.myapplication.ui.drag.PadActionMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.example.myapplication.ui.audio.PadRecorder
import com.example.myapplication.ui.audio.DrumEngine
import androidx.compose.runtime.snapshots.SnapshotStateList

inline fun <T> Iterable<T>.anyIndexed(
    predicate: (Int, T) -> Boolean
): Boolean {

    forEachIndexed { index, item ->

        if (predicate(index, item)) {
            return true
        }
    }

    return false
}

// ─── Global Color Tokens ───────────────────────────────────────────────────────
val PadDark     = Color(0xFF2A2A2A)
val PadPressed  = Color(0xFF3A3A3A)
val PanelBg     = Color(0xFF111111)
val LedActive   = Color(0xFF00E5FF)
val LedInactive = Color(0xFF444444)
val BtnBg       = Color(0xFF2A2A2A)
val BtnActive   = Color(0xFF00E5FF)
val LcdBg       = Color(0xFFB8D4E8)
val NavRed      = Color(0xFFC0392B)

// ─── Data Model ───────────────────────────────────────────────────────────────
data class Kit(
    var name: String,
    val volumes: MutableList<Float> =
        mutableStateListOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
    val pitches: MutableList<Float> =
        mutableStateListOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
    // Per-pad EQ + level + delay time (8 values each, one per pad)
    val padLevels:    MutableList<Float> = mutableStateListOf(1f,1f,1f,1f,1f,1f,1f,1f),
    val padEqLow:     MutableList<Float> = mutableStateListOf(1f,1f,1f,1f,1f,1f,1f,1f),
    val padEqMid:     MutableList<Float> = mutableStateListOf(1f,1f,1f,1f,1f,1f,1f,1f),
    val padEqHigh:    MutableList<Float> = mutableStateListOf(1f,1f,1f,1f,1f,1f,1f,1f),
    val padDelayMs:   MutableList<Int>   = mutableStateListOf(300,300,300,300,300,300,300,300),
    // NEW: fraction (0.05..1.0) of each pad's sample that actually plays —
    // the "LENGTH" trim control.
    val padLengthPct: MutableList<Float> = mutableStateListOf(1f,1f,1f,1f,1f,1f,1f,1f),
    // NEW: non-destructive CROP start handle — fraction (0f..0.95f) of the
    // sample to skip before playback begins. Pairs with padLengthPct (end
    // trim): the pad plays the window [padCropStartPct, padLengthPct] of its
    // sample without ever rewriting the underlying file, so the full-length
    // audio is preserved and the crop stays re-editable.
    val padCropStartPct: MutableList<Float> = mutableStateListOf(0f,0f,0f,0f,0f,0f,0f,0f),
    // NEW: per-pad Reverse toggle + playback mode ("ONESHOT" | "LOOP" | "MIX")
    val padReverse:   MutableList<Boolean> = mutableStateListOf(false,false,false,false,false,false,false,false),
    val padPlayMode:  MutableList<String>  = mutableStateListOf("ONESHOT","ONESHOT","ONESHOT","ONESHOT","ONESHOT","ONESHOT","ONESHOT","ONESHOT"),
    // NEW: per-pad Pan (-1f full left .. 1f full right, 0f = center) and
    // Gain (0f..2f multiplicative trim on top of Volume, 1f = unity) — new
    // FX controls, defaults keep old saved kits sounding exactly as before.
    val padPan:  MutableList<Float> = mutableStateListOf(0f,0f,0f,0f,0f,0f,0f,0f),
    val padGain: MutableList<Float> = mutableStateListOf(1f,1f,1f,1f,1f,1f,1f,1f),
    // NEW: delay on/off is per-pad-per-kit now, not a single global toggle —
    // switching patches used to leave delay stuck on/off from whatever the
    // last patch had it set to. Each pad in each kit remembers its own.
    val padDelayEnabled: MutableList<Boolean> = mutableStateListOf(false,false,false,false,false,false,false,false),
    val sounds: MutableList<Int> =
        MutableList(8) { -1 },
    val factoryKitNumber: Int = -1,
    val chokeGroups: List<SnapshotStateList<Int>> = List(8) { mutableStateListOf() },
    val activeChokeLevelState: MutableState<Int> = mutableStateOf(0)
)

// NEW: MIDI CC -> target mappings (Volume/Pitch/EQ/Patch/Edit/Save) are now
// user-learnable via the MIDI Mapping screen and stored in CcMapRepository
// (which keeps CC 11/12 as the Volume/Pitch defaults so existing hardware
// setups keep working without re-learning).

// NEW: default pad1..pad8 raw sample files have been removed from res/raw.
// This constant replaces the per-file duration lookup so loop/exclusive-mode
// timing logic keeps working exactly as before for pads with no custom audio.
const val DEFAULT_PAD_DURATION_MS = 500L

// BUG FIX (B bank kit isolation): Bank A and Bank B used to index into the
// SAME shared `kits` list (one 200-slot pool) — landing on the same kit
// number on both banks meant literally the same Kit object (same
// volumes/pitches/chokeGroups/sounds SnapshotStateLists), so
// editing/importing/cropping a pad from Bank B silently mutated Bank A's
// pre-built kit too ("B bank use karne par A bank bigad jata hai"). `kits`
// is now always kept at a minimum of BANK_A_KIT_CAPACITY + BANK_B_KIT_CAPACITY
// (400) slots: 0..(BANK_A_KIT_CAPACITY-1) stays Bank A's pool exactly as
// before (25 factory kits + blanks), BANK_B_KIT_START..BANK_B_KIT_END is a
// second, always-blank pool reserved exclusively for Bank B. The two ranges
// never overlap, so the two banks can never land on the same Kit object.
const val BANK_A_KIT_CAPACITY = 200
const val BANK_B_KIT_START = BANK_A_KIT_CAPACITY
const val BANK_B_KIT_END = BANK_A_KIT_CAPACITY * 2 - 1

// ─── Main Screen ──────────────────────────────────────────────────────────────
@Composable
fun OctapadScreen(soundPool: SoundPool, sounds: List<Int>, onDeactivated: () -> Unit = {}) {

    val context = LocalContext.current

    KitRepository.init(context)
    PreferencesRepository.init(context)

    // NEW: while the app is open, periodically re-check the license status
    // with the admin panel so a remote deactivation (or a MIDI purchase
    // being granted) actually takes effect without the user doing anything.
    // isUsable()'s offline grace period already covers no-internet launches;
    // this just keeps that state fresh whenever the network IS reachable.
    var midiPurchased by remember { mutableStateOf(com.example.myapplication.license.LicenseRepository.isMidiPurchased(context)) }
    LaunchedEffect(Unit) {
        val serverUrl = com.example.myapplication.license.LicenseRepository.getServerUrl(context)
        val code = com.example.myapplication.license.LicenseRepository.getCode(context)
        if (serverUrl.isBlank() || code.isBlank()) return@LaunchedEffect
        val deviceId = com.example.myapplication.license.DeviceId.get(context)
        while (true) {
            val result = com.example.myapplication.license.LicenseApi.status(serverUrl, code, deviceId)
            com.example.myapplication.license.LicenseRepository.applyStatusCheck(context, result)
            if (!result.httpFailure) {
                midiPurchased = result.midiPurchased
                if (!result.active) {
                    onDeactivated()
                    return@LaunchedEffect
                }
            }
            delay(30 * 60 * 1000L) // recheck every 30 minutes while the app stays open
        }
    }

    var loopEnabled by remember { mutableStateOf(PreferencesRepository.loadLoopEnabled()) }
    var velocityOn by remember { mutableStateOf(PreferencesRepository.loadVelocityOn()) }
    var midiChannel by remember { mutableStateOf(PreferencesRepository.loadMidiChannel()) }
    var currentStreamId by remember { mutableStateOf(0) }

    var isRecording by remember {
        mutableStateOf(false)
    }


    val padRecorder = remember {
        PadRecorder(context)
    }

    var dragVisible by remember { mutableStateOf(false) }
    var dragPad by remember { mutableStateOf(-1) }

    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }

    var pad1X by remember { mutableStateOf(0f) }
    var pad1Y by remember { mutableStateOf(0f) }

    var pad2X by remember { mutableStateOf(0f) }
    var pad2Y by remember { mutableStateOf(0f) }

    var pad3X by remember { mutableStateOf(0f) }
    var pad3Y by remember { mutableStateOf(0f) }

    var pad4X by remember { mutableStateOf(0f) }
    var pad4Y by remember { mutableStateOf(0f) }

    var pad5X by remember { mutableStateOf(0f) }
    var pad5Y by remember { mutableStateOf(0f) }

    var pad6X by remember { mutableStateOf(0f) }
    var pad6Y by remember { mutableStateOf(0f) }

    var pad7X by remember { mutableStateOf(0f) }
    var pad7Y by remember { mutableStateOf(0f) }

    var pad8X by remember { mutableStateOf(0f) }
    var pad8Y by remember { mutableStateOf(0f) }

    var showPadMenu by remember { mutableStateOf(false) }

    var sourcePad by remember { mutableStateOf(-1) }
    var targetPad by remember { mutableStateOf(-1) }
    // Swap/Mix/Add-to-Last used to show the instant a 2-finger drag crossed
    // onto another pad and released — far too easy to trigger by accident.
    // Now gated on a hold: see beginTwoFingerHold()/endTwoFingerHold() below.
    var dragHoldJob by remember { mutableStateOf<Job?>(null) }
    // Becomes true once the 2-finger press has been held continuously for
    // 2s — only then does the drag/drop preview appear at all and start
    // tracking the fingers. See beginTwoFingerHold()/endTwoFingerHold().
    var holdArmed by remember { mutableStateOf(false) }


    val padVolumes = remember {
        mutableStateListOf(
            1f,1f,1f,1f,
            1f,1f,1f,1f
        )
    }

    val padPitches = remember {
        mutableStateListOf(
            1f,1f,1f,1f,
            1f,1f,1f,1f
        )
    }

    var selectedPad by remember {
        mutableStateOf(PreferencesRepository.loadSelectedPad().coerceIn(0, 7))
    }

    val pressedPads  = remember {
        mutableStateListOf(false, false, false, false, false, false, false, false)
    }

    var bpm by remember { mutableStateOf(PreferencesRepository.loadBpm()) }

    // NEW: Delay effect state. delayEnabled itself is per-pad-per-kit now
    // (kits[currentKit].padDelayEnabled) — see curPadDelayEnabled below —
    // not a single global toggle, so switching patches doesn't leave delay
    // stuck on/off from whatever the last patch had it set to.
    var delayChokePad by remember { mutableStateOf(PreferencesRepository.loadDelayChokePad()) }
    // NEW: delay decay/feedback amount (0f..1f) — was a hardcoded 0.5f, now a
    // FX-panel knob ("DELAY LEVEL").
    var delayLevel by remember { mutableStateOf(PreferencesRepository.loadDelayLevel()) }
    // NEW: global delay master kill switch (DelayPanel.kt's MASTER toggle).
    // OFF mutes delay on every pad in every kit immediately, without erasing
    // any individual pad's own padDelayEnabled flag — turning it back ON
    // restores exactly whichever pads had their own flag set. Reuses a
    // PreferencesRepository field (loadDelayEnabled/saveDelayEnabled) that
    // was already there but unused since delay became per-pad-per-kit.
    var delayMasterEnabled by remember { mutableStateOf(PreferencesRepository.loadDelayEnabled()) }
    // NEW: LOOP group's SPEED control — global tempo-synced playback-rate
    // multiplier (0.5x..2x), separate from BPM.
    var speed by remember { mutableStateOf(PreferencesRepository.loadSpeed()) }
    // delayLevel (decay factor) is fixed at 0.5 — controlled by EQ panel's DLY TIME knob per pad

    // EQ state is now stored per-pad inside Kit.padLevels / padEqLow / padEqMid / padEqHigh / padDelayMs
    // No global EQ vars needed — we read directly from kits[currentKit].padXxx[selectedPad]

    // ── NEW: Exclusive Mode ke liye — track karta he kaunsa pad abhi baj rha he ──
    // ── NEW: Exclusive Mode ke liye — track karta he kaunsa pad abhi baj rha he ──
    var currentlyPlayingPad by remember { mutableStateOf(-1) }
    val loopTokens = remember { mutableStateMapOf<Int, Long>() }

    // NEW: tracks which pads are currently sounding for choke-group logic
    val chokeActivePads = remember { mutableStateMapOf<Int, Long>() }

    // NEW: tracks which pads currently have an active per-pad PLAY MODE =
    // LOOP hold running. Per-pad LOOP mode has no natural end (it always
    // retriggers, ignoring the global Loop toggle by design), so without
    // this there was no way to actually silence one short of switching its
    // PLAY MODE away from LOOP in the LOOP panel — hitting the pad again
    // just restarted the same infinite loop. See onPadHit's tap-to-stop
    // check below, which uses this to make a second tap on an already-
    // looping LOOP-mode pad stop it instead.
    val loopModeActive = remember { mutableStateMapOf<Int, Boolean>() }

// ── NEW: Recording jis pad pe start hui thi, usi ko lock karke rakhta he ──
    var recordingPad by remember { mutableStateOf(-1) }
    var recordingToken by remember { mutableStateOf(0L) }

    // ── NEW: 25 default kits auto-load hote hain, har kit ke 8 pads apne
    // "kitN_padM" naam ke raw resource se automatically bharte hain.
    // Agar koi resource file missing ho to us slot pe -1 (no sound) rehta he
    // (crash nahi hota, bas wo ek slot khaali rehta he — default pad1-8
    // sample files ab res/raw mein exist nahi karte, isliye unko fallback
    // ke roop mein use nahi kar sakte). ──
    fun buildKitSounds(factoryKitNumber: Int): MutableList<Int> {
        val kitSounds = mutableStateListOf<Int>()
        for (padNumber in 1..8) {
            val resName = "kit${factoryKitNumber}_pad${padNumber}"
            val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
            kitSounds.add(if (resId != 0) resId else -1)
        }
        return kitSounds
    }

    val kits = remember {

        val kitList = mutableStateListOf<Kit>()
        val savedEntries = KitRepository.load()

        if (savedEntries.isNotEmpty()) {

            // NEW: rebuild exactly what the user had (adds/deletes/renames survive restart)
            savedEntries.forEach { entry ->
                kitList.add(
                    Kit(
                        entry.name,
                        volumes = mutableStateListOf<Float>().apply { addAll(entry.volumes) },
                        pitches = mutableStateListOf<Float>().apply { addAll(entry.pitches) },
                        padLevels  = mutableStateListOf<Float>().apply { addAll(entry.padLevels) },
                        padEqLow   = mutableStateListOf<Float>().apply { addAll(entry.padEqLow) },
                        padEqMid   = mutableStateListOf<Float>().apply { addAll(entry.padEqMid) },
                        padEqHigh  = mutableStateListOf<Float>().apply { addAll(entry.padEqHigh) },
                        padDelayMs = mutableStateListOf<Int>().apply   { addAll(entry.padDelayMs) },
                        padLengthPct = mutableStateListOf<Float>().apply { addAll(entry.padLengthPct) },
                        padCropStartPct = mutableStateListOf<Float>().apply { addAll(entry.padCropStartPct) },
                        padReverse = mutableStateListOf<Boolean>().apply { addAll(entry.padReverse) },
                        padPlayMode = mutableStateListOf<String>().apply { addAll(entry.padPlayMode) },
                        padPan  = mutableStateListOf<Float>().apply { addAll(entry.padPan) },
                        padGain = mutableStateListOf<Float>().apply { addAll(entry.padGain) },
                        padDelayEnabled = mutableStateListOf<Boolean>().apply { addAll(entry.padDelayEnabled) },
                        sounds = if (entry.factoryKitNumber != -1)
                            buildKitSounds(entry.factoryKitNumber)
                        else
                            mutableStateListOf(-1, -1, -1, -1, -1, -1, -1, -1),
                        factoryKitNumber = entry.factoryKitNumber,
                        chokeGroups = entry.chokeGroups.map { levels ->
                            mutableStateListOf<Int>().apply { addAll(levels) }
                        },
                        activeChokeLevelState = mutableStateOf(entry.activeChokeLevel)
                    )
                )
            }

        } else {

            // First-ever launch — generate the original 25 factory kits
            for (kitNumber in 1..25) {
                kitList.add(
                    Kit(
                        "KIT %03d".format(kitNumber),
                        sounds = buildKitSounds(kitNumber),
                        factoryKitNumber = kitNumber
                    )
                )
            }

            // NEW: pre-populate the remaining slots up to the 200-kit cap as
            // genuinely empty/unassigned kits (all pads = -1, no factory
            // source) instead of leaving them simply not existing. The
            // Patch List now shows all 200 slots from first launch, with
            // 26-200 visibly blank until the user loads/saves into one —
            // rather than kits only appearing once explicitly created via
            // Copy, which is what happened before.
            for (kitNumber in 26..200) {
                kitList.add(
                    Kit(
                        "EMPTY %03d".format(kitNumber),
                        sounds = mutableStateListOf(-1, -1, -1, -1, -1, -1, -1, -1),
                        factoryKitNumber = -1
                    )
                )
            }
        }

        // BUG FIX (B bank kit isolation): guarantee a dedicated, always-blank
        // 200-slot pool for Bank B at indices BANK_B_KIT_START..BANK_B_KIT_END,
        // separate from Bank A's 0..(BANK_A_KIT_CAPACITY-1) pool above — this
        // runs both on first launch (kitList already has exactly 200 A-bank
        // entries from the branches above) and as a one-time migration for
        // installs saved before this fix (which also only ever had 200
        // entries, since the old "New Kit"/copy 200-cap made growing past 200
        // via those paths impossible — only ImportPatchScreen/LoadKitScreen
        // could organically grow past 200, so padding up to at least 400 here
        // is always safe and never drops any existing kit).
        while (kitList.size < BANK_B_KIT_END + 1) {
            val slot = kitList.size - BANK_B_KIT_START + 1
            kitList.add(
                Kit(
                    "EMPTY B %03d".format(slot),
                    sounds = mutableStateListOf(-1, -1, -1, -1, -1, -1, -1, -1),
                    factoryKitNumber = -1
                )
            )
        }

        kitList
    }

    var currentKit by remember { mutableStateOf(KitRepository.loadLastSelectedKit()) }

    // ── A/B Bank: currentKit above is Bank A's kit. Bank B has its own kit
    // slot; bankMode is a string containing any subset of the letters
    // 'A'/'B' — every letter present layers that bank's sound on top of the
    // others on a pad hit ("A", "B", "AB"). Bank C was removed entirely (it
    // used to push the patch-list nav row off the bottom of this
    // non-scrolling panel whenever it was active) — strip any leftover 'C'
    // from a value persisted before the removal so an old install doesn't
    // come back up in a now-nonexistent bank mode.
    var currentKitB by remember { mutableStateOf(PreferencesRepository.loadKitB()) }
    var bankMode by remember { mutableStateOf(PreferencesRepository.loadBankMode().replace("C", "").ifEmpty { "A" }) }

    LaunchedEffect(currentKitB) { PreferencesRepository.saveKitB(currentKitB) }
    LaunchedEffect(bankMode)    { PreferencesRepository.saveBankMode(bankMode) }

    // BUG FIX (B bank kit isolation): currentKitB must always point inside
    // Bank B's own reserved BANK_B_KIT_START..BANK_B_KIT_END range — never
    // into Bank A's 0..(BANK_A_KIT_CAPACITY-1) range, since `kits` is now
    // guaranteed (see the `kits = remember {}` block above) to hold at least
    // BANK_B_KIT_END+1 entries with that upper range permanently reserved
    // for Bank B. This also migrates installs saved before this fix, whose
    // persisted currentKitB still points into the old shared 0..199 range
    // (including the old loadKitB() default of 25) back onto Bank B's own
    // pool instead of leaving it aliasing whatever Bank A kit used to sit
    // at that index.
    LaunchedEffect(Unit) {
        if (currentKitB !in BANK_B_KIT_START..BANK_B_KIT_END) currentKitB = BANK_B_KIT_START
    }

    // BUG FIX: the pad VOL/PITCH controls (and the matching MIDI CC handlers)
    // used to always read/write kits[currentKit] — Bank A's kit — no matter
    // which bank was actually selected via the A/B/C buttons. Moving the
    // slider while Bank B or C was active silently edited Bank A's data
    // instead (which is why B/C "didn't work": the value shown/changed was
    // never the one actually sounding for those banks). This mirrors the
    // same A/B/C → kit precedence already used by nativeSlotsFor()/fire()
    // below for playback, so editing now targets whichever kit is actually
    // audible for the current bank selection.
    //
    // BUG FIX 2: this was a plain `val` (a one-time Int snapshot), which is
    // fine for lambdas that get recreated every recomposition (all the
    // on-screen knobs/sliders), but MidiEventBus.onPadHit/onControlChange
    // below are installed inside `LaunchedEffect(Unit)` — a block that runs
    // exactly ONCE for the whole composable's lifetime. That one-time
    // closure captured whatever bank was active at first composition
    // (typically Bank A at launch) forever; switching banks afterward had
    // zero effect on anything triggered via MIDI note/CC, only on touch
    // (whose onPress lambdas are recreated fresh every recomposition). Made
    // this a function instead of a val: `bankMode`/`currentKit*` are
    // `by remember { mutableStateOf(...) }` delegates, so referencing them
    // inside a function body always reads the CURRENT value at call time —
    // even from a closure that was itself created once and never rebuilt —
    // because delegated property reads aren't snapshotted, unlike a captured
    // plain Int.
    fun bankKitIdx(): Int = when {
        'A' in bankMode -> currentKit
        'B' in bankMode -> currentKitB
        else -> currentKit
    }

    // A/B Bank: which native slot(s) a logical pad (0-7) should sound/be
    // controlled on for the current bankMode. Shared by onPadHit() (playback)
    // and the live VOLUME/PITCH MIDI CC handlers below, so both agree on
    // which native voice(s) a given bank selection actually maps to.
    fun nativeSlotsFor(padIdx: Int): List<Int> {
        val slots = mutableListOf<Int>()
        if ('A' in bankMode) slots.add(padIdx)
        if ('B' in bankMode) slots.add(padIdx + 8)
        return if (slots.isEmpty()) listOf(padIdx) else slots
    }

    // Sync per-pad EQ to native whenever currentKit or selectedPad changes
    // (handled by separate LaunchedEffect further below — just save kit index here)
    LaunchedEffect(currentKit) {
        KitRepository.saveLastSelectedKit(currentKit)
    }

    // NEW: safety clamp — agar last-selected kit delete ho chuka ho ya
    // list chhoti ho gayi ho, to out-of-range crash na ho
    LaunchedEffect(Unit) {
        if (currentKit !in kits.indices) {
            currentKit = 0
        }
        // BUG FIX: an install affected by the old "new kit appended past slot
        // 400" bug can come back up with currentKit pointing at an overflow
        // index (>= BANK_A_KIT_CAPACITY) that Bank A's `>` nav and Patch List
        // can't reach — pull it back into Bank A's own 0..199 pool.
        if (currentKit >= BANK_A_KIT_CAPACITY) {
            currentKit = BANK_A_KIT_CAPACITY - 1
        }
    }

    // NEW: saves current kit list (name + factory source) so it survives restart
    fun persistKits() {
        KitRepository.save(
            kits.map {
                KitRepository.KitEntry(
                    it.name,
                    it.factoryKitNumber,
                    chokeGroups      = it.chokeGroups.map { levels -> levels.toList() },
                    activeChokeLevel = it.activeChokeLevelState.value,
                    volumes   = it.volumes.toList(),
                    pitches   = it.pitches.toList(),
                    padLevels = it.padLevels.toList(),
                    padEqLow  = it.padEqLow.toList(),
                    padEqMid  = it.padEqMid.toList(),
                    padEqHigh = it.padEqHigh.toList(),
                    padDelayMs = it.padDelayMs.toList(),
                    padLengthPct = it.padLengthPct.toList(),
                    padCropStartPct = it.padCropStartPct.toList(),
                    padReverse = it.padReverse.toList(),
                    padPlayMode = it.padPlayMode.toList(),
                    padPan  = it.padPan.toList(),
                    padGain = it.padGain.toList(),
                    padDelayEnabled = it.padDelayEnabled.toList()
                )
            }
        )
    }

    val scope = rememberCoroutineScope()

    // BUG FIX: persistKits() serializes every kit slot (up to 200) to JSON
    // synchronously — every continuous knob (VOLUME/PITCH/EQ/PAN/GAIN/DLY
    // TIME/LENGTH, both on-screen drag AND MIDI CC) used to call it on EVERY
    // single value change. A MIDI knob sweep can fire dozens of CC messages
    // per second; calling this full-kits-list JSON serialize+disk-write on
    // every one of them — synchronously, on the thread receiving MIDI or
    // handling the touch drag — is exactly what read as "volume/pitch knob
    // gets stuck after a while of testing, via MIDI and on the phone too".
    // Debounced version used at every continuous-drag call site below:
    // still saves the final value, just ~300ms after the last change instead
    // of on every intermediate tick. One-shot actions (rename, swap, delete,
    // explicit SAVE button, reverse/play-mode toggles) keep calling
    // persistKits() directly — those aren't high-frequency, and should
    // persist immediately.
    var persistDebounceJob by remember { mutableStateOf<Job?>(null) }
    fun persistKitsDebounced() {
        persistDebounceJob?.cancel()
        persistDebounceJob = scope.launch {
            delay(300L)
            persistKits()
        }
    }

    LaunchedEffect(currentKit) {
        KitRepository.saveLastSelectedKit(currentKit)
    }

    // Shared by the EDIT (crop) screen's Clear button and the FX panel's
    // quick "EDIT PAD" menu — wipes both a custom AudioRepository assignment
    // (if any) AND the factory sound reference, so CLEAR always actually
    // leaves the pad silent regardless of which kind of sound it had.
    //
    // BUG FIX: used to always target kits[currentKit] (Bank A) regardless of
    // which bank was actually selected — clearing a pad while Bank B/C was
    // active silently cleared Bank A's (inaudible) sound instead, so the pad
    // you were looking at kept playing. Routed through bankKitIdx(), same
    // fix pattern as the FX knobs.
    fun clearPadSound(padIndex: Int) {
        val kitIdx = bankKitIdx()
        AudioRepository.audioForPad(kitIdx, padIndex)?.let { item ->
            AudioRepository.unassignPad(item.id)
        }
        kits[kitIdx].sounds[padIndex] = -1
        DrumEngine.invalidatePad(padIndex)
        persistKits()
    }

    var showKitList  by remember { mutableStateOf(false) }
    // NEW: which bank the open KitListScreen selects into — false = Bank A's
    // currentKit (the original behavior), true = Bank B's currentKitB (so
    // Bank B can jump straight to a kit number instead of only stepping
    // through neighbors with < / >).
    var kitListTargetsBankB by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    // BUG FIX: the rename dialog used to reuse `currentKit` (Bank A's
    // selection) as the index of whichever kit was being renamed. Opening
    // rename from Bank B's patch list therefore had to set currentKit =
    // index just to make the dialog work — silently switching Bank A's
    // active kit as a side effect of renaming something in Bank B. A
    // dedicated index decouples "which kit is being renamed" from "which
    // kit Bank A currently has selected".
    var renameKitIndex by remember { mutableStateOf(-1) }
    // NEW: which kit's single-patch export dialog is open, -1 = none
    var exportPatchKitIndex by remember { mutableStateOf(-1) }
    var newKitName by remember { mutableStateOf("") }

    // ── NEW: Audio screen states ───────────────────────────────────────────────
    var topPanel by remember { mutableStateOf("") }
    // NEW: when set, ImportScreen assigns a successful import straight to
    // this pad instead of leaving it unassigned in the library — the
    // one-tap "IMPORT TO THIS PAD" entry point in the FX panel.
    var importTargetPad by remember { mutableStateOf<Int?>(null) }

    // ── NEW: EDIT MODE — round button below DLY in the right panel. While
    // on, tapping a pad opens a contextual menu (Clear Sound / Add Sound)
    // instead of playing it — see the gate at the top of onPadHit() and the
    // menu overlay rendered near the bottom of this composable.
    var editModeOn by remember { mutableStateOf(false) }
    var editMenuPad by remember { mutableStateOf<Int?>(null) }

    // ── NEW: Live waveform/timer tracking for the LCD screen ──────────────────
    var playingPadUri      by remember { mutableStateOf<android.net.Uri?>(null) }
    var waveformVersion by remember {
        mutableIntStateOf(0)
    }
    var playbackPositionMs by remember { mutableStateOf(0L) }
    var playbackDurationMs by remember { mutableStateOf(0L) }
    // Each pad hit gets a unique token; only the MOST RECENT token is allowed
    // to update/clear the waveform — older hits become "background" sounds
    // that keep playing but no longer own the LCD display.
    var latestHitToken      by remember { mutableStateOf(0L) }

    val padSounds = remember {
        sounds.toMutableList()
    }

    fun updatePadDisplay(index: Int) {

        val assigned = AudioRepository.audioForPad(
            currentKit,
            index
        )

        if (assigned != null) {

            playingPadUri = assigned.uri
            playbackDurationMs = assigned.durationMs

        } else {

            // NEW: default pad1-8 raw sample files removed — no fallback
            // waveform URI available anymore, so LCD just stays blank for
            // this pad, but timing still uses a fixed fallback duration.
            playingPadUri = null
            playbackDurationMs = DEFAULT_PAD_DURATION_MS
        }

        playbackPositionMs = 0L
        waveformVersion++
    }

    // ── NEW: Recording stop + save karne ka shared logic —
// button click aur auto-timeout dono isi function ko use karenge ──
    // BUG FIX: always saved into kits[currentKit] (Bank A) regardless of the
    // active bank — recording into a pad while Bank B/C was selected saved
    // the take into Bank A's (inaudible) kit instead of the one actually
    // being played, so it looked like the recording silently vanished.
    fun stopRecordingAndSave() {
        val file = padRecorder.stopRecording()

        if (file != null) {
            AudioRepository.assignRecordedAudio(
                kitIndex = bankKitIdx(),
                padIndex = recordingPad,
                file = file
            )
        }

        android.util.Log.d(
            "REC",
            "Saved = ${file?.absolutePath}"
        )

        isRecording = false
        recordingPad = -1
    }



    /**
     * @param velocityMultiplier  0f..1f — how hard the pad was hit.
     *   1f = full volume (touch-triggered pads always use 1f).
     *   MIDI-triggered pads pass the actual velocity so softer hits are quieter.
     */
    fun onPadHit(index: Int, velocityMultiplier: Float = 1f) {
        // EDIT MODE: pads don't play — they open the Clear/Add contextual
        // menu instead. Gate first, before any playback/state bookkeeping.
        if (editModeOn) {
            editMenuPad = index
            return
        }
        // NEW: when Velocity is OFF, every hit (touch or MIDI) plays at full
        // volume regardless of how hard/soft the physical hit was.
        val effectiveVelocity = if (velocityOn) velocityMultiplier else 1f
        // Per-pad FX playback mode: ONESHOT (default, obeys the global Loop
        // toggle), LOOP (always repeats at tempo), MIX (layers on top of any
        // already-sounding voice for this pad instead of cutting it off).
        val playMode = kits[bankKitIdx()].padPlayMode.getOrElse(index) { "ONESHOT" }
        val allowOverlap = playMode == "MIX"
        // NOTE: must stay a function reading the pad's CURRENT play mode and
        // the CURRENT loopEnabled — not values captured once — because a
        // ONESHOT pad's loop can run for many seconds/iterations inside the
        // coroutine below. Two independent staleness bugs lived here before:
        // (1) a captured `loopEnabled` val froze the global toggle's value at
        // trigger time, so flipping Loop off mid-playback never stopped an
        // already-looping pad; (2) a captured `playMode` val meant switching
        // a pad's PLAY MODE away from LOOP in the LOOP panel while it was
        // actively looping had no effect either — the running coroutine kept
        // reading its own stale snapshot forever. Re-reading both live on
        // every check fixes both.
        fun effectiveLoop(): Boolean {
            val currentMode = kits[bankKitIdx()].padPlayMode.getOrElse(index) { "ONESHOT" }
            return when (currentMode) {
                "LOOP" -> true
                "MIX"  -> false
                else   -> loopEnabled
            }
        }
        // A/B/C Bank: which native slot(s) this logical pad should sound on.
        // Each letter present in bankMode contributes its own slot, layered
        // together — e.g. "AC" plays Bank A's + Bank C's sound at once.
        // (nativeSlotsFor is now shared — declared above, near bankMode.)
        val bankSlots = nativeSlotsFor(index)
        // BUG FIX: this Log.d used to run synchronously on every single pad
        // hit, before the trigger below — a real (if small) per-hit cost for
        // zero shipped benefit (ProGuard/R8 is disabled per CLAUDE.md, so
        // logging is never stripped from a release build either). Removed
        // from this hot path along with the MIDI receive/CC-tick logging in
        // MidiReceiver.kt and the per-CC-message latency logs above.

        // Tap-to-stop: a PLAY MODE = LOOP pad that's already actively holding
        // its loop gets silenced by a second tap instead of restarting the
        // loop — otherwise a LOOP-mode pad could only ever be stopped by
        // switching its PLAY MODE away from LOOP in the LOOP panel, which
        // read as "loop mode never turns off".
        if (playMode == "LOOP" && loopModeActive[index] == true) {
            bankSlots.forEach { DrumEngine.stop(it) }
            loopTokens[index] = -1L
            loopModeActive.remove(index)
            pressedPads[index] = false
            if (currentlyPlayingPad == index) {
                currentlyPlayingPad = -1
                playingPadUri = null
                playbackPositionMs = 0L
                playbackDurationMs = 0L
            }
            chokeActivePads.remove(index)
            return
        }

        // ── Everything below through the first DrumEngine.trigger() call
        // runs SYNCHRONOUSLY, on this exact call stack — i.e. inside the
        // same pointer-down event that started this function, before any
        // coroutine dispatch. This used to all be wrapped in scope.launch{},
        // which on Compose's frame-synced dispatcher defers execution to the
        // next Choreographer frame (up to ~16ms at 60Hz) even though we're
        // already on the UI thread — a real, silent latency tax on every
        // single hit. Everything here is fast in-memory work (list lookups,
        // JNI calls) with no suspension, so there's no reason to defer it.
        // Only the "wait for playback to finish / loop again / clear the
        // LCD" bookkeeping below — which doesn't affect what you actually
        // hear — is handed off to a coroutine.
        val assigned = AudioRepository.audioForPad(bankKitIdx(), index)
        val uriToShow: Uri?
        val rawDurationToShow: Long
        if (currentStreamId != 0) {
            soundPool.stop(currentStreamId)
        }
        if (assigned != null) {
            uriToShow      = assigned.uri
            rawDurationToShow = assigned.durationMs
        } else {
            uriToShow      = null
            // BUG FIX: this used to always guess DEFAULT_PAD_DURATION_MS
            // (500ms) for factory samples. Any factory sample actually
            // longer than that got cut short every time it retriggered
            // (LOOP mode) and had a wrong/truncated LCD progress bar —
            // reported as "tone cut" and "loop still broken". Now uses the
            // real decoded duration (cached by DrumEngine.loadPad the first
            // time this pad's sample was loaded) when available.
            val factoryResId = kits[bankKitIdx()].sounds.getOrElse(index) { -1 }
            rawDurationToShow = com.example.myapplication.ui.audio.PadDurationCache.get(factoryResId)
                ?: DEFAULT_PAD_DURATION_MS
        }

        // Non-destructive CROP: the pad plays only the window
        // [padCropStartPct, padLengthPct] of its sample (see the native
        // startFraction plumbing). The LCD timer and every LOOP-mode
        // retrigger interval below should track that sliced length, not the
        // full untrimmed file — otherwise a cropped looping pad would
        // retrigger with a long silent tail.
        val cropSpanFraction = ((kits[bankKitIdx()].padLengthPct.getOrElse(index) { 1f }
                - kits[bankKitIdx()].padCropStartPct.getOrElse(index) { 0f })).coerceIn(0.02f, 1f)
        val durationToShow: Long = (rawDurationToShow * cropSpanFraction).toLong().coerceAtLeast(1L)

        // Stop any current voice for this pad + cancel its loop token so any
        // in-flight coroutine for a previous hit exits on its next check.
        // Skipped in MIX mode so the new hit layers on top instead of
        // cutting the previous one off.
        if (!allowOverlap) {
            bankSlots.forEach { DrumEngine.stop(it) }
            loopTokens[index] = -1L
        }

        val myLoopToken = System.nanoTime()
        loopTokens[index] = myLoopToken

        // BUG FIX: choke used to be gated behind a separate "Exclusive Mode"
        // toggle AND an "Active Level" selector — two extra switches on top
        // of a pad's own choke-group membership, both of which had to be
        // set correctly before choke did anything at all. That's exactly
        // what read as "choke kaam nahi kar raha hai" (choke doesn't work).
        // Simplified per request: choke groups are just always live — any
        // two pads sharing the same non-NONE level (1-4) always choke each
        // other reciprocally, nothing else to turn on first.
        //
        // Also: choke used to be read only from kits[currentKit] (Bank A),
        // so hitting a pad while Bank B was active never choked anything —
        // the membership check was against a kit that might not even be
        // one of the currently-active banks. Every ACTIVE bank's own group
        // membership is checked, so a pad hit on Bank A layered with Bank B
        // chokes correctly across the combination.
        val bankKitIdxs = buildList {
            if ('A' in bankMode) add(currentKit)
            if ('B' in bankMode) add(currentKitB)
        }.filter { it in kits.indices }

        fun chokesTogether(padA: Int, padB: Int): Boolean = bankKitIdxs.any { bankKit ->
            kits[bankKit].chokeGroups[padA].any { level ->
                level != 0 && level in kits[bankKit].chokeGroups[padB]
            }
        }

        if (bankKitIdxs.isNotEmpty()) {
            chokeActivePads.keys.toList().forEach { otherPad ->
                if (otherPad != index && chokesTogether(index, otherPad)) {
                    nativeSlotsFor(otherPad).forEach { DrumEngine.stop(it) }
                    loopTokens[otherPad] = -1L
                    chokeActivePads.remove(otherPad)
                }
            }
        }

        // Pushes this pad's delay config to native synchronously, right
        // before its trigger fires. BUG FIX: delay's native params used to
        // be synced only by a reactive LaunchedEffect keyed on `selectedPad`
        // — a coroutine that doesn't actually run until the next
        // recomposition frame, roughly a frame AFTER `selectedPad = index`
        // gets set on tap. The trigger fires synchronously, same call stack,
        // before that frame — so the very first hit on a freshly-selected
        // pad read whatever delay config was still live from the PREVIOUS
        // pad, and only the second+ hit on the same pad (by which point the
        // LaunchedEffect had caught up) got the correct one. That's exactly
        // "first hit no delay, works after multiple hits on the same pad,
        // breaks again on switching pads".
        //
        // BUG FIX 2: this used to run inside fire() itself, which is also
        // called from the loop-retrigger coroutine below — delay is a
        // single GLOBAL native effect (not per-voice), so an already-looping
        // pad's automatic retrigger re-pushing ITS OWN delay config on every
        // beat would clobber whatever a *different* pad's hit had just set
        // moments earlier, making that other pad's delay tap fire with the
        // looping pad's timing/on-off state instead of its own. Only the
        // initial hit (call site below, not fire() itself) needs this sync —
        // a retrigger is the same pad repeating, so its delay config hasn't
        // changed since the hit that started the loop.
        fun syncDelayForHit() {
            val delayPadEnabled = if (bankKitIdx() in kits.indices)
                kits[bankKitIdx()].padDelayEnabled.getOrElse(index) { false } else false
            NativeBridge.setDelayEnabled(delayPadEnabled && delayMasterEnabled)
            NativeBridge.setDelayParams(delayLevel, 1)
            val delayMsForPad = if (bankKitIdx() in kits.indices)
                kits[bankKitIdx()].padDelayMs.getOrElse(index) { 300 } else 300
            NativeBridge.setDelayTapIntervalFrames(DrumEngine.sampleRate().toLong() * delayMsForPad.toLong() / 1000L)
            NativeBridge.setDelayChokePad(delayChokePad)
        }

        // Fires the actual native trigger + updates the LCD/choke bookkeeping
        // for one hit. Used both for the immediate first hit (below) and for
        // each subsequent loop re-trigger inside the coroutine.
        fun fire(token: Long) {
            latestHitToken     = token
            playingPadUri      = uriToShow
            playbackDurationMs = durationToShow
            playbackPositionMs = 0L

            bankSlots.forEach { slot ->
                val kitForSlot = when {
                    slot >= 8  -> currentKitB
                    else       -> currentKit
                }
                if (kitForSlot in kits.indices) {
                    DrumEngine.trigger(
                        slot,
                        kits[kitForSlot].volumes[index] * effectiveVelocity,
                        kits[kitForSlot].pitches[index],
                        stopExisting = !allowOverlap,
                        lengthFraction = kits[kitForSlot].padLengthPct.getOrElse(index) { 1f },
                        pan = kits[kitForSlot].padPan.getOrElse(index) { 0f },
                        gain = kits[kitForSlot].padGain.getOrElse(index) { 1f },
                        startFraction = kits[kitForSlot].padCropStartPct.getOrElse(index) { 0f }
                    )
                }
            }
            currentlyPlayingPad = index
            chokeActivePads[index] = token
        }

        pressedPads[index] = true

        syncDelayForHit()
        var myToken = System.nanoTime()
        fire(myToken)   // ← the sound actually starts here, synchronously
        if (playMode == "LOOP") loopModeActive[index] = true

        // ── From here on it's just waiting/looping/UI bookkeeping ──────────
        scope.launch {
            try {
                var keepGoing = true

                while (keepGoing) {
                    // SPEED (LOOP panel) scales the tempo-synced interval —
                    // >1x plays the beat grid faster, <1x slower, independent of BPM.
                    // BUG FIX: this used to divide as Long/Int first (`60_000L
                    // / bpm`), truncating to a whole millisecond *before* the
                    // Speed division was ever applied — e.g. at 127 BPM,
                    // 60000/127 truncates to 472 instead of the true 472.44,
                    // and every subsequent Speed scaling compounded that
                    // rounding. Doing the whole calculation in Float first,
                    // then truncating once at the very end, keeps BPM and
                    // Speed both accurate instead of BPM silently losing
                    // precision before Speed ever sees it.
                    val beatIntervalMs = (60_000f / bpm.coerceAtLeast(1) / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(50L)
                    // BUG FIX: per-pad LOOP play mode used to be BPM-gated
                    // exactly like the global Loop toggle (wait for
                    // max(beatInterval, duration) before retriggering) — so
                    // a short sample at a slow BPM played once then sat in
                    // silence for the rest of the beat before repeating,
                    // which reads as "loop is broken" (spec asks for LOOP to
                    // just play continuously the moment it's hit). The
                    // global Loop toggle is intentionally tempo-synced (the
                    // Tempo panel says so directly — "Pad loops at BPM
                    // rate") so that one still uses the BPM-gated window;
                    // only the explicit per-pad LOOP mode is now a true
                    // immediate back-to-back loop.
                    val currentMode = kits[bankKitIdx()].padPlayMode.getOrElse(index) { "ONESHOT" }
                    // BUG FIX: per-pad LOOP mode used to ignore SPEED entirely
                    // (always durationToShow, the sample's own raw length) —
                    // meaning the LOOP panel's SPEED knob had literally zero
                    // effect unless a pad was ONESHOT *and* the global Loop
                    // toggle was on, which reads as "Speed control doesn't
                    // work" for the very common case of testing it against a
                    // PLAY MODE = LOOP pad. BPM is deliberately NOT applied
                    // here — re-gating LOOP mode to the beat grid would
                    // reintroduce the "silence gap" bug fixed above. Only the
                    // sample's own natural gap is scaled by the playback-
                    // rate-like SPEED multiplier, same idea as the beat-grid
                    // scaling above, just applied to LOOP mode's own interval.
                    val loopModeIntervalMs = (durationToShow / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(50L)
                    // BUG FIX: the global Loop toggle's retrigger window used
                    // to be maxOf(beatIntervalMs, durationToShow) — so once
                    // SPEED made a beat shorter than the sample, raising SPEED
                    // further did nothing at all ("loop mode me speed kaam
                    // nahi kar raha"). beatIntervalMs is already BPM/SPEED
                    // scaled; use it directly (floored at 50ms) so SPEED
                    // always tightens/loosens the loop, cutting the sample
                    // short when the beat is shorter than it — which is the
                    // whole point of a loop speed control.
                    val waitWindowMs = when {
                        currentMode == "LOOP" -> loopModeIntervalMs
                        effectiveLoop()        -> beatIntervalMs
                        else                    -> durationToShow
                    }

                    val startTime = System.currentTimeMillis()
                    var elapsed = 0L
                    while (true) {
                        // Superseded by a newer hit on this same pad, or choked
                        // by another pad's hit — the audio is already stopped
                        // synchronously wherever that happened, so there's no
                        // reason for this stale coroutine to keep waiting out
                        // the rest of waitWindowMs before noticing.
                        if (loopTokens[index] != myLoopToken) break

                        elapsed = System.currentTimeMillis() - startTime
                        // Recomputed live every tick, not just once before this
                        // loop started — if the global Loop toggle (or per-pad
                        // LOOP hold) gets turned off mid-wait, an ONESHOT pad
                        // sitting in the extra tempo-sync padding beyond its own
                        // sample length (waitWindowMs > durationToShow) should
                        // finish out its own natural length and stop, not keep
                        // lingering for the rest of a — possibly multi-second at
                        // a slow BPM — beat interval before the toggle actually
                        // takes effect.
                        val target = if (effectiveLoop()) waitWindowMs else durationToShow
                        if (elapsed >= target) break

                        if (latestHitToken == myToken) {
                            playbackPositionMs = elapsed.coerceAtMost(durationToShow)
                        }
                        delay(50)
                    }

                    if (latestHitToken == myToken) {
                        playbackPositionMs = elapsed.coerceAtMost(durationToShow)
                    }

                    // ✅ NEW: agla loop chalega ya nahi — Loop toggle ON he
                    // AND koi doosre pad-hit/exclusive-stop ne isse invalidate nahi kiya
                    keepGoing = effectiveLoop() && loopTokens[index] == myLoopToken

                    if (keepGoing) {
                        // Timer-driven re-trigger (not touch-driven), so firing it
                        // from inside the coroutine is fine — it's tempo-locked
                        // via waitWindowMs above regardless of dispatch timing.
                        myToken = System.nanoTime()
                        fire(myToken)
                    } else {
                        loopModeActive.remove(index)
                        if (latestHitToken == myToken) {
                            delay(150)
                            if (latestHitToken == myToken) {
                                playingPadUri = null
                                playbackPositionMs = 0L
                                playbackDurationMs = 0L
                                if (currentlyPlayingPad == index) {
                                    currentlyPlayingPad = -1
                                }
                                // NEW: pad naturally finished — no longer choke-active
                                if (chokeActivePads[index] == myToken) {
                                    chokeActivePads.remove(index)
                                }
                            }
                        }
                    }
                }

                delay(100)
                pressedPads[index] = false

            } catch (e: Exception) {
                android.util.Log.e("PADHIT", "onPadHit FAILED for index=$index: ${e.message}", e)
                pressedPads[index] = false
                loopModeActive.remove(index)
            }
        }
    }





    // ── NEW: MIDI knob (Control Change) → live volume/pitch on selected pad ───
    LaunchedEffect(Unit) {
        MidiEventBus.onPadHit = { pad, velocity ->
            when (pad) {
                1 -> { selectedPad = 0; onPadHit(0, velocity) }
                2 -> { selectedPad = 1; onPadHit(1, velocity) }
                3 -> { selectedPad = 2; onPadHit(2, velocity) }
                4 -> { selectedPad = 3; onPadHit(3, velocity) }
                5 -> { selectedPad = 4; onPadHit(4, velocity) }
                6 -> { selectedPad = 5; onPadHit(5, velocity) }
                7 -> { selectedPad = 6; onPadHit(6, velocity) }
                8 -> { selectedPad = 7; onPadHit(7, velocity) }
            }
        }
    }
    // ── NEW: MIDI knob (Control Change) → live volume/pitch on selected pad ───
    LaunchedEffect(Unit) {
        AudioRepository.init(context)
        DrumEngine.ensureStarted()
        // Restore any saved Note->pad mappings into native (pads with no
        // saved mapping keep native's GM default) — this is the only way a
        // MIDI controller triggers a pad (CC-based PAD_1..PAD_8 mapping was
        // removed; a physical pad hit is always a Note-On, never a CC).
        MidiLearnRepository.init(context)
        MidiLearnRepository.loadAll().forEach { (pad, note) ->
            NativeBridge.assignMidiNote(pad, note)
        }
    }

    // MIDI Program Change → direct patch/kit select (0-63), distinct from
    // the CC-based Next/Prev patch nav which already works.
    LaunchedEffect(Unit) {
        MidiEventBus.onProgramChange = { program ->
            if (kits.isNotEmpty()) {
                // Bank A only (Program Change has always targeted currentKit)
                // — coerce into Bank A's own range so it can't jump into
                // Bank B's reserved pool.
                currentKit = program.coerceIn(0, BANK_A_KIT_CAPACITY - 1)
            }
        }
    }

    // Hardware Tab key (MainActivity.dispatchKeyEvent) → force EDIT MODE off.
    LaunchedEffect(Unit) {
        MidiEventBus.onExitEditMode = {
            editModeOn = false
        }
    }

    // ── Sync delay params to native ──────────────────────────────────────────
    // BUG FIX: this used to only re-read padDelayMs when delayEnabled/
    // selectedPad/currentKit/delayChokePad changed — dragging the DLY TIME
    // knob itself changes padDelayMs but wasn't in the key list, so the knob
    // moved on screen but the actual echo timing sent to native stayed stuck
    // at whatever it was when you last switched pads. That's exactly what
    // reads as "uncontrollable echo instead of a proper delay". Fixed by
    // reading the live value into its own variable and keying on THAT.
    val curPadDelayMs = if (bankKitIdx() in kits.indices)
        kits[bankKitIdx()].padDelayMs.getOrElse(selectedPad) { 300 } else 300

    // NEW: delay on/off read from the CURRENT pad's own kit — per-pad-per-kit,
    // not a single global toggle, so it never leaks into a different patch.
    // BUG FIX: was hardcoded to kits[currentKit] (Bank A) — reading/writing
    // this while Bank B/C was active silently edited Bank A's data instead
    // of whichever bank was actually audible. Same bankKitIdx() fix as
    // volume/pitch above.
    val curPadDelayEnabled = if (bankKitIdx() in kits.indices)
        kits[bankKitIdx()].padDelayEnabled.getOrElse(selectedPad) { false } else false

    fun setCurPadDelayEnabled(enabled: Boolean) {
        if (bankKitIdx() in kits.indices) {
            kits[bankKitIdx()].padDelayEnabled[selectedPad] = enabled
            persistKits()
        }
    }

    LaunchedEffect(curPadDelayEnabled, selectedPad, bankKitIdx(), delayChokePad, curPadDelayMs, delayLevel, delayMasterEnabled) {
        NativeBridge.setDelayEnabled(curPadDelayEnabled && delayMasterEnabled)
        NativeBridge.setDelayParams(delayLevel, 1)   // decay per tap (FX panel's DELAY LEVEL knob), 1 tap — client wants exactly 2 sounds total per hit (original + 1 echo)
        // BUG FIX: this used to hardcode 48000Hz to convert the DLY TIME knob
        // (ms) into frames, but AudioEngine deliberately opens the stream at
        // the device's native rate (no longer forced to 48000) — on any
        // device whose native rate differs, echoes fired at the wrong time.
        // DrumEngine.sampleRate() reports the engine's actual opened rate.
        val sr = DrumEngine.sampleRate().toLong()
        NativeBridge.setDelayTapIntervalFrames(sr * curPadDelayMs.toLong() / 1000L)
        NativeBridge.setDelayChokePad(delayChokePad)
    }

    LaunchedEffect(delayMasterEnabled) { PreferencesRepository.saveDelayEnabled(delayMasterEnabled) }

    // Sync per-pad EQ+level to native whenever selected pad or kit changes
    val curPadLevel = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padLevels[selectedPad] else 1f
    val curPadEqLow = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padEqLow[selectedPad]  else 1f
    val curPadEqMid = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padEqMid[selectedPad]  else 1f
    val curPadEqHigh= if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padEqHigh[selectedPad] else 1f

    LaunchedEffect(selectedPad, bankKitIdx(), curPadLevel, curPadEqLow, curPadEqMid, curPadEqHigh) {
        NativeBridge.setMasterLevel(curPadLevel)
        NativeBridge.setEqBands(curPadEqLow, curPadEqMid, curPadEqHigh)
    }

    // Sync per-pad Pan/Gain to native — unlike EQ/Level (global/master in the
    // native engine), Pan and Gain are true per-voice fields keyed by pad
    // index, so this updates whatever's currently playing on THIS pad, not
    // the whole mix.
    val curPadPan  = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padPan.getOrElse(selectedPad) { 0f }  else 0f
    val curPadGain = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padGain.getOrElse(selectedPad) { 1f } else 1f

    // BUG FIX: these used to call DrumEngine.setPan/setGain(selectedPad, ...)
    // directly with the raw 0-7 pad index — always Bank A's native slots
    // (0-7) no matter which bank was actually active, so nudging Pan/Gain
    // while Bank B/C was selected silently updated the wrong (or a silent,
    // unloaded) native voice. Routed through nativeSlotsFor(), same as every
    // other live per-pad control here, so it hits whichever native slot(s)
    // are actually audible for the current bank selection.
    LaunchedEffect(selectedPad, bankKitIdx(), bankMode, curPadPan) {
        nativeSlotsFor(selectedPad).forEach { DrumEngine.setPan(it, curPadPan) }
    }
    LaunchedEffect(selectedPad, bankKitIdx(), bankMode, curPadGain) {
        nativeSlotsFor(selectedPad).forEach { DrumEngine.setGain(it, curPadGain) }
    }

    // NEW: Persist BPM whenever it changes
    LaunchedEffect(bpm) {
        PreferencesRepository.saveBpm(bpm)
    }
    LaunchedEffect(delayLevel) {
        PreferencesRepository.saveDelayLevel(delayLevel)
    }
    LaunchedEffect(speed) {
        PreferencesRepository.saveSpeed(speed)
    }

    // NEW: Persist remaining app state so re-opening the app resumes exactly
    // where it was left off, instead of resetting to defaults.
    LaunchedEffect(loopEnabled)     { PreferencesRepository.saveLoopEnabled(loopEnabled) }
    LaunchedEffect(velocityOn)      { PreferencesRepository.saveVelocityOn(velocityOn) }
    LaunchedEffect(midiChannel) {
        PreferencesRepository.saveMidiChannel(midiChannel)
        com.example.myapplication.MidiChannelState.selectedChannel = midiChannel
    }
    LaunchedEffect(delayChokePad)   { PreferencesRepository.saveDelayChokePad(delayChokePad) }
    LaunchedEffect(selectedPad)     { PreferencesRepository.saveSelectedPad(selectedPad) }



    // Bank A -> native slots 0-7
    LaunchedEffect(
        currentKit,
        AudioRepository.audios.map {
            Triple(
                it.id,
                it.assignedPad,
                it.assignedKit
            )
        },
        kits[currentKit].padReverse.toList()
    ) {

        for (pad in 0 until 8) {

            DrumEngine.loadPad(
                context,
                currentKit,
                pad,
                kits[currentKit].sounds[pad],
                reversed = kits[currentKit].padReverse.getOrElse(pad) { false }
            )
        }
    }

    // Bank B -> native slots 8-15, kept loaded in parallel with Bank A so
    // switching bank mode (A/B/AB) is instant with no reload delay.
    LaunchedEffect(
        currentKitB,
        AudioRepository.audios.map {
            Triple(
                it.id,
                it.assignedPad,
                it.assignedKit
            )
        },
        if (currentKitB in kits.indices) kits[currentKitB].padReverse.toList() else emptyList()
    ) {
        if (currentKitB in kits.indices) {
            for (pad in 0 until 8) {
                DrumEngine.loadPad(
                    context,
                    currentKitB,
                    pad,
                    kits[currentKitB].sounds[pad],
                    reversed = kits[currentKitB].padReverse.getOrElse(pad) { false },
                    nativeSlot = pad + 8
                )
            }
        }
    }



    LaunchedEffect(Unit) {
        com.example.myapplication.CcMapRepository.init(context)
    }

    LaunchedEffect(Unit) {
        MidiEventBus.onControlChange = onCc@{ ccNumber, ccValue ->
            val normalized = (ccValue / 127f).coerceIn(0f, 1f)

            // If the MIDI CC mapping screen is waiting to learn a target,
            // bind this CC to it instead of acting on it.
            val listening = com.example.myapplication.CcLearnState.listeningForTarget.value
            if (listening != null) {
                com.example.myapplication.CcMapRepository.save(listening, ccNumber)
                com.example.myapplication.CcLearnState.lastAssigned.value = listening to ccNumber
                com.example.myapplication.CcLearnState.listeningForTarget.value = null
                return@onCc
            }

            val cc = com.example.myapplication.CcMapRepository
            when (ccNumber) {

                cc.getCc("VOLUME") -> {
                    val newVolume = normalized
                    // BUG FIX: was always writing kits[currentKit] (Bank A) and
                    // pushing the live value to native slot `selectedPad`
                    // (0-7, i.e. Bank A's native slots) no matter which bank
                    // was actually selected — so the knob silently edited Bank
                    // A while Bank B/C was playing. Use bankKitIdx() for the
                    // stored value and nativeSlotsFor() for the live update,
                    // same as onPadHit()/the on-screen sliders.
                    kits[bankKitIdx()].volumes[selectedPad] = newVolume
                    nativeSlotsFor(selectedPad).forEach { DrumEngine.setVolume(it, newVolume) }
                    // BUG FIX: was persistKits() (a synchronous full-kits-list
                    // JSON serialize + disk write) called on every single CC
                    // tick — a fast knob sweep can send dozens of CC messages
                    // per second, so this stacked expensive synchronous work
                    // on the MIDI callback thread on every one of them. That's
                    // what read as "volume/pitch knob gets stuck in MIDI after
                    // testing for a while" — the callback thread falls further
                    // behind the longer a sweep continues. Debounced version
                    // still saves the final value, just ~300ms after the last
                    // change instead of on every intermediate tick.
                    persistKitsDebounced()
                }

                cc.getCc("PITCH") -> {
                    val newPitch = 0.5f + normalized * 1.5f
                    kits[bankKitIdx()].pitches[selectedPad] = newPitch
                    nativeSlotsFor(selectedPad).forEach { DrumEngine.setPitch(it, newPitch) }
                    persistKitsDebounced()
                }

                cc.getCc("EQ_LOW") -> {
                    // BUG FIX: same Bank A-only bug as VOLUME/PITCH above —
                    // was always kits[currentKit], now targets whichever bank
                    // is actually active.
                    kits[bankKitIdx()].padEqLow[selectedPad] = normalized * 2f
                    NativeBridge.setEqBands(kits[bankKitIdx()].padEqLow[selectedPad], kits[bankKitIdx()].padEqMid[selectedPad], kits[bankKitIdx()].padEqHigh[selectedPad])
                    persistKitsDebounced()
                }

                cc.getCc("EQ_MID") -> {
                    kits[bankKitIdx()].padEqMid[selectedPad] = normalized * 2f
                    NativeBridge.setEqBands(kits[bankKitIdx()].padEqLow[selectedPad], kits[bankKitIdx()].padEqMid[selectedPad], kits[bankKitIdx()].padEqHigh[selectedPad])
                    persistKitsDebounced()
                }

                cc.getCc("EQ_HIGH") -> {
                    kits[bankKitIdx()].padEqHigh[selectedPad] = normalized * 2f
                    NativeBridge.setEqBands(kits[bankKitIdx()].padEqLow[selectedPad], kits[bankKitIdx()].padEqMid[selectedPad], kits[bankKitIdx()].padEqHigh[selectedPad])
                    persistKitsDebounced()
                }

            }
        }
    }

    // ── MIDI Note (button/action targets) ────────────────────────────────────
    // PATCH_NEXT/PATCH_PREV/EDIT/SAVE/DELAY_TOGGLE/BANK_A/BANK_B/BANK_AB used
    // to be CC-learned (see CcMapRepository's comment for why that moved) —
    // now driven by a raw Note-On via NoteMapRepository/NoteLearnState,
    // exactly parallel to the CC handler above but keyed by MIDI note number
    // instead of CC number. Pad triggering itself stays on the existing
    // native Note-based path (MidiLearnRepository/MidiProcessor) — untouched
    // here, this only covers the non-pad button/action targets.
    LaunchedEffect(Unit) {
        com.example.myapplication.NoteMapRepository.init(context)
    }

    LaunchedEffect(Unit) {
        MidiEventBus.onRawNoteOn = onNote@{ note, velocity ->
            // If the MIDI mapping screen is waiting to learn a target, bind
            // this note to it instead of acting on it.
            val listening = com.example.myapplication.NoteLearnState.listeningForTarget.value
            if (listening != null) {
                com.example.myapplication.NoteMapRepository.save(listening, note)
                com.example.myapplication.NoteLearnState.lastAssigned.value = listening to note
                com.example.myapplication.NoteLearnState.listeningForTarget.value = null
                return@onNote
            }

            val nm = com.example.myapplication.NoteMapRepository
            when (note) {
                nm.getNote("PATCH_NEXT") -> {
                    // Bank A only — stay inside Bank A's own range so
                    // stepping can't walk into Bank B's reserved pool.
                    if (currentKit < BANK_A_KIT_CAPACITY - 1) currentKit++
                }

                nm.getNote("PATCH_PREV") -> {
                    if (currentKit > 0) currentKit--
                }

                nm.getNote("EDIT") -> {
                    topPanel = "EDIT"
                }

                nm.getNote("SAVE") -> {
                    persistKits()
                }

                nm.getNote("DELAY_TOGGLE") -> {
                    setCurPadDelayEnabled(!curPadDelayEnabled)
                }

                nm.getNote("BANK_A") -> {
                    bankMode = "A"
                }

                nm.getNote("BANK_B") -> {
                    bankMode = "B"
                }

                nm.getNote("BANK_AB") -> {
                    bankMode = "AB"
                }
            }
        }
    }



    fun generateNextKitName(): String {

        var nextNumber = 1

        while (
            kits.any {
                it.name.equals(
                    "KIT %03d".format(nextNumber),
                    ignoreCase = true
                )
            }
        ) {
            nextNumber++
        }

        return "KIT %03d".format(nextNumber)
    }

    // BUG FIX (B bank kit isolation): a plain `kits.removeAt(index)` shifts
    // every kit after `index` down by one slot — for an index inside either
    // bank's fixed BANK_A/BANK_B reserved range, that would silently move
    // every other reserved-range kit (including all of the OTHER bank's
    // kits) into a different absolute index, eventually letting the two
    // banks' ranges drift into each other again. Deleting a kit inside
    // either reserved range (0..BANK_B_KIT_END) now resets that slot back
    // to a blank placeholder IN PLACE instead of removing it, so every
    // other kit — in both banks — keeps its absolute index. Only kits
    // beyond BANK_B_KIT_END (organic overflow from Import Patch/Load Kit,
    // which have never had a slot cap) are still physically removed, since
    // nothing else depends on their exact position.
    fun deleteKit(index: Int) {

        if (kits.size <= 1) return
        if (index !in kits.indices) return

        if (index <= BANK_B_KIT_END) {
            val blankName = if (index < BANK_A_KIT_CAPACITY)
                "EMPTY %03d".format(index + 1)
            else
                "EMPTY B %03d".format(index - BANK_B_KIT_START + 1)
            kits[index] = Kit(
                blankName,
                sounds = mutableStateListOf(-1, -1, -1, -1, -1, -1, -1, -1),
                factoryKitNumber = -1
            )
        } else {
            kits.removeAt(index)
        }

        if (currentKit >= kits.size) {
            currentKit = kits.lastIndex
        }

        if (currentKit < 0) {
            currentKit = 0
        }

        if (currentKitB !in BANK_B_KIT_START..BANK_B_KIT_END) {
            currentKitB = BANK_B_KIT_START
        }

        persistKits()   // NEW
    }

    // Bank B's pool is a fixed 200 slots (BANK_B_KIT_START..BANK_B_KIT_END)
    // that never grows — "free" means still an untouched blank placeholder
    // (factoryKitNumber == -1 and never renamed off its auto-generated
    // "EMPTY B ###" name).
    fun firstFreeBankBSlot(): Int? =
        (BANK_B_KIT_START..BANK_B_KIT_END).firstOrNull {
            kits[it].factoryKitNumber == -1 && kits[it].name.startsWith("EMPTY B ")
        }

    // BUG FIX: `kits` is permanently padded to >= 400 entries (Bank A pool
    // 0..199, Bank B pool 200..399), so every `kits.add(...)` for a new
    // Bank A kit landed at index 400+ — outside Bank A's Patch List range
    // (0..199) and outside the `onKitNext` cap (BANK_A_KIT_CAPACITY-1), so
    // the new/loaded kit was invisible in the list and `>` (next patch)
    // stopped working (only `<` did, walking back into Bank B's pool). Every
    // Bank A "create kit" path (NEW KIT button, Patch List + NEW KIT, Load
    // Kit From Folder, Import Patch) now writes into the first still-blank
    // slot inside Bank A's own 0..199 pool instead of appending past it.
    fun firstFreeBankASlot(): Int? =
        (0 until BANK_A_KIT_CAPACITY).firstOrNull {
            kits[it].factoryKitNumber == -1 && kits[it].name.startsWith("EMPTY ")
        }

    // NEW: duplicates kits[index] (all volumes/pitches/EQ/choke groups/custom
    // audio). `intoBankB` = true writes the copy into a free slot inside
    // Bank B's own reserved pool (never appended past it — appending would
    // land outside the range currentKitB is confined to); false (Bank A)
    // keeps the original behavior of appending a new kit at the end.
    fun copyKit(index: Int, intoBankB: Boolean = false) {
        if (index !in kits.indices) return

        val source = kits[index]
        val newKit = Kit(
            name = generateNextKitName(),
            volumes    = mutableStateListOf<Float>().apply { addAll(source.volumes) },
            pitches    = mutableStateListOf<Float>().apply { addAll(source.pitches) },
            padLevels  = mutableStateListOf<Float>().apply { addAll(source.padLevels) },
            padEqLow   = mutableStateListOf<Float>().apply { addAll(source.padEqLow) },
            padEqMid   = mutableStateListOf<Float>().apply { addAll(source.padEqMid) },
            padEqHigh  = mutableStateListOf<Float>().apply { addAll(source.padEqHigh) },
            padDelayMs = mutableStateListOf<Int>().apply { addAll(source.padDelayMs) },
            padLengthPct = mutableStateListOf<Float>().apply { addAll(source.padLengthPct) },
            padCropStartPct = mutableStateListOf<Float>().apply { addAll(source.padCropStartPct) },
            padReverse = mutableStateListOf<Boolean>().apply { addAll(source.padReverse) },
            padPlayMode = mutableStateListOf<String>().apply { addAll(source.padPlayMode) },
            padPan  = mutableStateListOf<Float>().apply { addAll(source.padPan) },
            padGain = mutableStateListOf<Float>().apply { addAll(source.padGain) },
            padDelayEnabled = mutableStateListOf<Boolean>().apply { addAll(source.padDelayEnabled) },
            sounds = mutableStateListOf<Int>().apply { addAll(source.sounds) },
            factoryKitNumber = source.factoryKitNumber,
            chokeGroups = source.chokeGroups.map { levels -> mutableStateListOf<Int>().apply { addAll(levels) } },
            activeChokeLevelState = mutableStateOf(source.activeChokeLevelState.value)
        )

        if (intoBankB) {
            val freeSlot = firstFreeBankBSlot() ?: return
            kits[freeSlot] = newKit
            AudioRepository.copyForKit(index, freeSlot)
            currentKitB = freeSlot
        } else {
            // Appends past the end — always lands beyond BANK_B_KIT_END
            // since `kits` never shrinks below BANK_B_KIT_END+1 (see
            // deleteKit), so this can never collide with Bank B's reserved
            // range. No hard cap here, same as Import Patch/Load Kit's
            // existing (uncapped) kits.add() calls.
            kits.add(newKit)
            val newIndex = kits.lastIndex
            AudioRepository.copyForKit(index, newIndex)
            currentKit = newIndex
        }
        persistKits()
    }

    // Sets padIndex's choke level (1..4) for the current kit — a pad now
    // belongs to at most ONE level at a time (like picking from a
    // None/1/2/3/4 dropdown), not a multi-select set. Tapping the level a
    // pad is already assigned to clears it back to None; tapping a
    // different level replaces whatever it was on before. Storage is still
    // `chokeGroups: List<SnapshotStateList<Int>>` (a list per pad) for
    // backward compatibility with existing saved kits/backups — this just
    // never lets that list hold more than one entry going forward.
    //
    // BUG FIX: the "already contains this level" branch used to call
    // groups.clear() — fine for a pad that only ever had one level, but a
    // kit saved before this None/1-4 single-select redesign could still
    // have a pad in MULTIPLE legacy levels (the old grid allowed it,
    // including levels 5/6 which no longer have any UI). clear() silently
    // wiped every other legacy level too the first time that pad's UI-
    // visible level was toggled off — real, silent data loss. remove() only
    // takes off the one level actually tapped.
    fun toggleChokeGroup(padIndex: Int, level: Int) {
        val groups = kits[bankKitIdx()].chokeGroups[padIndex]
        if (groups.contains(level)) {
            groups.remove(level)
        } else {
            groups.clear()
            groups.add(level)
        }
        persistKits()
    }




    // BUG FIX: every field below used to unconditionally target
    // kits[currentKit] (Bank A) and reload native slots 0-7 (loadPad's
    // nativeSlot defaults to the raw pad index), regardless of which bank
    // was actually selected — dragging a swap while Bank B/C was active
    // silently rewrote Bank A's (inaudible) kit and native buffers instead
    // of the one actually being played, so the swap looked like it did
    // nothing. Routed kit reads/writes through bankKitIdx() and reloads
    // through nativeSlotsFor(), same fix pattern as the FX knobs.
    fun swapPads(source: Int, target: Int) {

        val kitIdx = bankKitIdx()
        val s = source - 1
        val t = target - 1

        // ---------- Volume ----------
        val tempVolume = kits[kitIdx].volumes[s]
        kits[kitIdx].volumes[s] = kits[kitIdx].volumes[t]
        kits[kitIdx].volumes[t] = tempVolume

        // ---------- Pitch ----------
        val tempPitch = kits[kitIdx].pitches[s]
        kits[kitIdx].pitches[s] = kits[kitIdx].pitches[t]
        kits[kitIdx].pitches[t] = tempPitch

        // ---------- Imported / Recorded Audio ----------

        val tempSound = kits[kitIdx].sounds[s]

        kits[kitIdx].sounds[s] =
            kits[kitIdx].sounds[t]

        kits[kitIdx].sounds[t] =
            tempSound

        AudioRepository.swapPads(
            kitIdx,
            s,
            t
        )

        // ---------- Remaining per-pad settings ----------
        // BUG FIX: a "swap" used to only move volume/pitch/sound, leaving
        // choke group membership, EQ, delay, length, reverse and play mode
        // behind on the old pad index — the sound moved but its behavior
        // didn't, which reads as a broken/partial swap. Everything per-pad
        // now moves together.
        val tempLevel = kits[kitIdx].padLevels[s]
        kits[kitIdx].padLevels[s] = kits[kitIdx].padLevels[t]
        kits[kitIdx].padLevels[t] = tempLevel

        val tempEqLow = kits[kitIdx].padEqLow[s]
        kits[kitIdx].padEqLow[s] = kits[kitIdx].padEqLow[t]
        kits[kitIdx].padEqLow[t] = tempEqLow

        val tempEqMid = kits[kitIdx].padEqMid[s]
        kits[kitIdx].padEqMid[s] = kits[kitIdx].padEqMid[t]
        kits[kitIdx].padEqMid[t] = tempEqMid

        val tempEqHigh = kits[kitIdx].padEqHigh[s]
        kits[kitIdx].padEqHigh[s] = kits[kitIdx].padEqHigh[t]
        kits[kitIdx].padEqHigh[t] = tempEqHigh

        val tempDelayMs = kits[kitIdx].padDelayMs[s]
        kits[kitIdx].padDelayMs[s] = kits[kitIdx].padDelayMs[t]
        kits[kitIdx].padDelayMs[t] = tempDelayMs

        val tempLengthPct = kits[kitIdx].padLengthPct[s]
        kits[kitIdx].padLengthPct[s] = kits[kitIdx].padLengthPct[t]
        kits[kitIdx].padLengthPct[t] = tempLengthPct

        val tempCropStartPct = kits[kitIdx].padCropStartPct[s]
        kits[kitIdx].padCropStartPct[s] = kits[kitIdx].padCropStartPct[t]
        kits[kitIdx].padCropStartPct[t] = tempCropStartPct

        val tempReverse = kits[kitIdx].padReverse[s]
        kits[kitIdx].padReverse[s] = kits[kitIdx].padReverse[t]
        kits[kitIdx].padReverse[t] = tempReverse

        val tempPlayMode = kits[kitIdx].padPlayMode[s]
        kits[kitIdx].padPlayMode[s] = kits[kitIdx].padPlayMode[t]
        kits[kitIdx].padPlayMode[t] = tempPlayMode

        val tempPan = kits[kitIdx].padPan[s]
        kits[kitIdx].padPan[s] = kits[kitIdx].padPan[t]
        kits[kitIdx].padPan[t] = tempPan

        val tempGain = kits[kitIdx].padGain[s]
        kits[kitIdx].padGain[s] = kits[kitIdx].padGain[t]
        kits[kitIdx].padGain[t] = tempGain

        val tempDelayEnabled = kits[kitIdx].padDelayEnabled[s]
        kits[kitIdx].padDelayEnabled[s] = kits[kitIdx].padDelayEnabled[t]
        kits[kitIdx].padDelayEnabled[t] = tempDelayEnabled

        val sGroups = kits[kitIdx].chokeGroups[s].toList()
        val tGroups = kits[kitIdx].chokeGroups[t].toList()
        kits[kitIdx].chokeGroups[s].clear()
        kits[kitIdx].chokeGroups[s].addAll(tGroups)
        kits[kitIdx].chokeGroups[t].clear()
        kits[kitIdx].chokeGroups[t].addAll(sGroups)

        // ---------- Reload Native Engine ----------
        // Reload every native slot the currently active bank combination
        // actually maps to (could be more than one, e.g. bank mode "AB").
        nativeSlotsFor(s).forEach { DrumEngine.invalidatePad(it) }
        nativeSlotsFor(t).forEach { DrumEngine.invalidatePad(it) }

        nativeSlotsFor(s).forEach { slot ->
            DrumEngine.loadPad(context, kitIdx, s, kits[kitIdx].sounds[s], nativeSlot = slot)
        }
        nativeSlotsFor(t).forEach { slot ->
            DrumEngine.loadPad(context, kitIdx, t, kits[kitIdx].sounds[t], nativeSlot = slot)
        }
        if (selectedPad == s || selectedPad == t) {
            updatePadDisplay(selectedPad)
        }
    }

    // BUG FIX: this used to require dragX/dragY to land within a fixed
    // 250-raw-pixel window of a pad's stored top-left position, checked
    // independently per pad in order — on any device whose density made
    // pads bigger or smaller than whatever screen this constant was tuned
    // on, the drag could easily end up matching NO pad at all (menu never
    // shows) or ambiguously falling in the gap between two pads. That's
    // exactly what reads as "swap/mix/add-to-end still doesn't work".
    // Replaced with nearest-neighbor: always resolves to whichever pad's
    // stored position is closest, so it can't silently fail to match.
    fun detectTargetPad(): Int {
        val positions = listOf(
            1 to (pad1X to pad1Y), 2 to (pad2X to pad2Y),
            3 to (pad3X to pad3Y), 4 to (pad4X to pad4Y),
            5 to (pad5X to pad5Y), 6 to (pad6X to pad6Y),
            7 to (pad7X to pad7Y), 8 to (pad8X to pad8Y)
        )
        return positions.minByOrNull { (_, pos) ->
            val (px, py) = pos
            val dx = dragX - px
            val dy = dragY - py
            dx * dx + dy * dy
        }?.first ?: -1
    }

    // ── 2-finger hold gate for Swap/Mix/Add-to-Last ─────────────────────────
    // A 2-finger press on a pad used to open the Swap/Mix/Add-to-Last menu
    // the instant the fingers were lifted over a different pad — no hold
    // required, so it fired on any quick 2-finger tap/brush, not just a
    // deliberate drag.
    //
    // BUG FIX: an earlier version of this gate set dragVisible = true (the
    // floating drag/drop preview) IMMEDIATELY on the 2-finger press, before
    // any delay — only the final menu-on-release was actually gated, so the
    // drag visual itself still popped up the instant 2 fingers touched
    // down, which read as "hold isn't doing anything." Simplified: the
    // drag preview and live position tracking don't start at all until 2s
    // of continuous holding have passed — releasing before that cancels
    // silently and nothing was ever shown. Once armed, this goes back to
    // exactly the original pre-gate behavior (drag to another pad, release
    // to open the menu if the target differs from the source) — no extra
    // "hover" tracking logic layered on top.
    fun beginTwoFingerHold(padNum: Int, startX: Float, startY: Float) {
        dragPad = padNum
        sourcePad = padNum
        dragX = startX
        dragY = startY
        holdArmed = false
        dragHoldJob?.cancel()
        dragHoldJob = scope.launch {
            delay(2000L)
            holdArmed = true
            dragVisible = true
        }
    }

    fun endTwoFingerHold() {
        dragHoldJob?.cancel()
        dragHoldJob = null
        dragVisible = false
        // Only honor the release if the press lasted the full 2s — a
        // release before that (holdArmed still false) cancels silently,
        // nothing was ever shown and the menu never appears.
        if (holdArmed) {
            targetPad = detectTargetPad()
            if (targetPad != -1 && targetPad != sourcePad) {
                showPadMenu = true
            }
        }
        holdArmed = false
    }

    // BoxWithConstraints instead of Box: reading maxWidth lets the control
    // column (and every side panel that slides out from it) scale with the
    // actual screen instead of using one fixed dp width tuned for a single
    // reference device — small phones no longer lose most of their pad area
    // to an oversized panel, and tablets no longer get a control column that
    // looks stranded and tiny.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFD0D0D0))
            // BUG FIX: fullscreen immersive mode (system bars hidden — see
            // MainActivity.hideSystemBars()) plus no display-cutout handling
            // meant a notch/camera-cutout was left to whatever each OEM
            // defaults to, which isn't consistent — on some phones part of
            // the fixed pad-grid/control-panel layout ended up letterboxed
            // out or rendered under the cutout, reported as "full screen on
            // some phones, half on others". windowInsetsPadding(displayCutout)
            // here means `maxWidth`/`maxHeight` below (and therefore
            // controlPanelWidth and every weighted child under it) are
            // computed from the actual safe usable area on every device,
            // not the raw screen size.
            .windowInsetsPadding(WindowInsets.displayCutout),
        contentAlignment = Alignment.Center
    ) {
        // ~20% of the available width, clamped to a sane hardware-control-panel range.
        val controlPanelWidth = (maxWidth * 0.20f).coerceIn(150.dp, 260.dp)

        // ── Main layout ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFE2E2E2))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // ── Left: 8 Drum Pads ─────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    // Pads sit closer together — was 8.dp, tightened per the
                    // reference layout's tightly-packed pad grid.
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    // Row 1 — Pad 1..4
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Pad1(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            pressed = pressedPads[0],
                            onPress = {
                                selectedPad = 0
                                onPadHit(0)
                            },
                            onRelease = { pressedPads[0] = false },
                            onDragStart = { beginTwoFingerHold(1, pad1X, pad1Y) },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = { endTwoFingerHold() },
                            onPadPositionChanged = { x, y ->
                                pad1X = x
                                pad1Y = y
                            },

                            onRecordStart = {
                                android.util.Log.d("REC", "Pad1 Start")
                            },

                            onRecordStop = {
                                android.util.Log.d("REC", "Pad1 Stop")
                            }
                        )

                        Pad2(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            pressed = pressedPads[1],
                            onPress = {
                                selectedPad = 1
                                onPadHit(1)
                            },
                            onRelease = { pressedPads[1] = false },
                            onDragStart = { beginTwoFingerHold(2, pad2X, pad2Y) },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = { endTwoFingerHold() },
                            onPadPositionChanged = { x, y ->
                                pad2X = x
                                pad2Y = y
                            },

                            onRecordStart = {
                                android.util.Log.d("REC", "Pad2 Start")
                            },

                            onRecordStop = {
                                android.util.Log.d("REC", "Pad2 Stop")
                            }
                        )

                        Pad3(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            pressed = pressedPads[2],
                            onPress = {
                                selectedPad = 2
                                onPadHit(2)
                            },
                            onRelease = { pressedPads[2] = false },
                            onDragStart = { beginTwoFingerHold(3, pad3X, pad3Y) },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = { endTwoFingerHold() },
                            onPadPositionChanged = { x, y ->
                                pad3X = x
                                pad3Y = y
                            },

                            onRecordStart = {
                                android.util.Log.d("REC", "Pad3 Start")
                            },

                            onRecordStop = {
                                android.util.Log.d("REC", "Pad3 Stop")
                            }
                        )


                        Pad4(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            pressed = pressedPads[3],
                            onPress = {
                                selectedPad = 3
                                onPadHit(3)
                            },
                            onRelease = { pressedPads[3] = false },
                            onDragStart = { beginTwoFingerHold(4, pad4X, pad4Y) },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = { endTwoFingerHold() },
                            onPadPositionChanged = { x, y ->
                                pad4X = x
                                pad4Y = y
                            },

                            onRecordStart = {
                                android.util.Log.d("REC", "Pad4 Start")
                            },

                            onRecordStop = {
                                android.util.Log.d("REC", "Pad4 Stop")
                            }
                        )
                    }

                    // Row 2 — Pad 5..8
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Pad5(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            pressed = pressedPads[4],
                            onPress = {
                                selectedPad = 4
                                onPadHit(4)
                            },
                            onRelease = { pressedPads[4] = false },
                            onDragStart = { beginTwoFingerHold(5, pad5X, pad5Y) },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = { endTwoFingerHold() },
                            onPadPositionChanged = { x, y ->
                                pad5X = x
                                pad5Y = y
                            },

                            onRecordStart = {
                                android.util.Log.d("REC", "Pad5 Start")
                            },

                            onRecordStop = {
                                android.util.Log.d("REC", "Pad5 Stop")
                            }
                        )

                        Pad6(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            pressed = pressedPads[5],
                            onPress = {
                                selectedPad = 5
                                onPadHit(5)
                            },
                            onRelease = { pressedPads[5] = false },
                            onDragStart = { beginTwoFingerHold(6, pad6X, pad6Y) },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = { endTwoFingerHold() },
                            onPadPositionChanged = { x, y ->
                                pad6X = x
                                pad6Y = y
                            },

                            onRecordStart = {
                                android.util.Log.d("REC", "Pad6 Start")
                            },

                            onRecordStop = {
                                android.util.Log.d("REC", "Pad6 Stop")
                            }
                        )

                        Pad7(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            pressed = pressedPads[6],
                            onPress = {
                                selectedPad = 6
                                onPadHit(6)
                            },
                            onRelease = { pressedPads[6] = false },
                            onDragStart = { beginTwoFingerHold(7, pad7X, pad7Y) },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = { endTwoFingerHold() },
                            onPadPositionChanged = { x, y ->
                                pad7X = x
                                pad7Y = y
                            },

                            onRecordStart = {
                                android.util.Log.d("REC", "Pad7 Start")
                            },

                            onRecordStop = {
                                android.util.Log.d("REC", "Pad7 Stop")
                            }
                        )

                        Pad8(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            pressed = pressedPads[7],
                            onPress = {
                                selectedPad = 7
                                onPadHit(7)
                            },
                            onRelease = { pressedPads[7] = false },
                            onDragStart = { beginTwoFingerHold(8, pad8X, pad8Y) },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = { endTwoFingerHold() },
                            onPadPositionChanged = { x, y ->
                                pad8X = x
                                pad8Y = y
                            },

                            onRecordStart = {
                                android.util.Log.d("REC", "Pad8 Start")
                            },

                            onRecordStop = {
                                android.util.Log.d("REC", "Pad8 Stop")
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            // ── Right: Control Panel ──────────────────────────────────────────
            RightPanel(

                controlPanelWidth = controlPanelWidth,
                loopEnabled = loopEnabled,
                onLoopChange = {
                    loopEnabled = it

                    if (!it && currentStreamId != 0) {
                        soundPool.stop(currentStreamId)
                        currentStreamId = 0
                    }
                },
                bpm = bpm,
                onBpmChange = { bpm = it },
                velocityOn = velocityOn,
                onVelocityChange = { velocityOn = it },
                // NEW: pass ALL pads' choke-group membership (not just selected pad)
                // so the EQ panel can show a level-first picker.

                // BUG FIX: allPadChokeGroups/toggleChokeGroup and every per-pad
                // control below used to read/write kits[currentKit] (Bank A)
                // unconditionally — the same bug the volume/pitch sliders had.
                // All now target bankKitIdx() so the CHOKE grid, FX knobs and
                // LOOP panel controls actually affect whichever bank is
                // selected.
                allPadChokeGroups = kits[bankKitIdx()].chokeGroups.map { it.toList() },
                onToggleChokeGroup = { padIndex, level -> toggleChokeGroup(padIndex, level) },

                delayEnabled  = curPadDelayEnabled,
                delayChokePad = delayChokePad,
                onDelayEnabledChange  = { setCurPadDelayEnabled(it) },
                onDelayChokePadChange = { delayChokePad = it },
                delayLevel = delayLevel,
                onDelayLevelChange = { delayLevel = it },
                delayMasterEnabled = delayMasterEnabled,
                onDelayMasterEnabledChange = { delayMasterEnabled = it },
                speed = speed,
                onSpeedChange = { speed = it },
                masterLevel = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padLevels[selectedPad] else 1f,
                eqLow  = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padEqLow[selectedPad]  else 1f,
                eqMid  = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padEqMid[selectedPad]  else 1f,
                eqHigh = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padEqHigh[selectedPad] else 1f,
                onMasterLevelChange = { v ->
                    if (bankKitIdx() in kits.indices) {
                        kits[bankKitIdx()].padLevels[selectedPad] = v
                        NativeBridge.setMasterLevel(v)
                        persistKitsDebounced()
                    }
                },
                onEqLowChange = { v ->
                    if (bankKitIdx() in kits.indices) {
                        kits[bankKitIdx()].padEqLow[selectedPad] = v
                        NativeBridge.setEqBands(v, kits[bankKitIdx()].padEqMid[selectedPad], kits[bankKitIdx()].padEqHigh[selectedPad])
                        persistKitsDebounced()
                    }
                },
                onEqMidChange = { v ->
                    if (bankKitIdx() in kits.indices) {
                        kits[bankKitIdx()].padEqMid[selectedPad] = v
                        NativeBridge.setEqBands(kits[bankKitIdx()].padEqLow[selectedPad], v, kits[bankKitIdx()].padEqHigh[selectedPad])
                        persistKitsDebounced()
                    }
                },
                onEqHighChange = { v ->
                    if (bankKitIdx() in kits.indices) {
                        kits[bankKitIdx()].padEqHigh[selectedPad] = v
                        NativeBridge.setEqBands(kits[bankKitIdx()].padEqLow[selectedPad], kits[bankKitIdx()].padEqMid[selectedPad], v)
                        persistKitsDebounced()
                    }
                },
                delayTimeMs = if (bankKitIdx() in kits.indices)
                    kits[bankKitIdx()].padDelayMs[selectedPad] else 300,
                onDelayTimeChange = { ms ->
                    if (bankKitIdx() in kits.indices) {
                        kits[bankKitIdx()].padDelayMs[selectedPad] = ms
                        persistKitsDebounced()
                    }
                },
                padLengthPct = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padLengthPct[selectedPad] else 1f,
                onPadLengthChange = { v ->
                    if (bankKitIdx() in kits.indices) {
                        kits[bankKitIdx()].padLengthPct[selectedPad] = v
                        persistKitsDebounced()
                    }
                },
                padReverse = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padReverse[selectedPad] else false,
                onReverseChange = { v ->
                    if (bankKitIdx() in kits.indices) {
                        kits[bankKitIdx()].padReverse[selectedPad] = v
                        persistKits()
                    }
                },
                padPlayMode = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padPlayMode[selectedPad] else "ONESHOT",
                onPlayModeChange = { mode ->
                    if (bankKitIdx() in kits.indices) {
                        kits[bankKitIdx()].padPlayMode[selectedPad] = mode
                        persistKits()
                    }
                },
                // NEW: per-pad Pan/Gain FX knobs
                padPan = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padPan.getOrElse(selectedPad) { 0f } else 0f,
                onPanChange = { v ->
                    if (bankKitIdx() in kits.indices) {
                        kits[bankKitIdx()].padPan[selectedPad] = v
                        nativeSlotsFor(selectedPad).forEach { DrumEngine.setPan(it, v) }
                        persistKitsDebounced()
                    }
                },
                padGain = if (bankKitIdx() in kits.indices) kits[bankKitIdx()].padGain.getOrElse(selectedPad) { 1f } else 1f,
                onGainChange = { v ->
                    if (bankKitIdx() in kits.indices) {
                        kits[bankKitIdx()].padGain[selectedPad] = v
                        nativeSlotsFor(selectedPad).forEach { DrumEngine.setGain(it, v) }
                        persistKitsDebounced()
                    }
                },
                onSaveClick = { persistKits() },
                bankMode = bankMode,
                // NEW: single-select — tapping A/B/C replaces bankMode with
                // just that letter; ALL sets it to "ABC". Simple direct
                // assignment now that RightPanel handles which button was
                // tapped (no more toggle-membership logic needed here).
                onBankModeSelect = { mode -> bankMode = mode },
                kitBName = if (currentKitB in kits.indices) kits[currentKitB].name else "",
                // Bank B stepping stays inside its own reserved
                // BANK_B_KIT_START..BANK_B_KIT_END pool — never Bank A's.
                onKitBPrev = { if (currentKitB > BANK_B_KIT_START) currentKitB-- },
                onKitBNext = { if (currentKitB < BANK_B_KIT_END) currentKitB++ },
                selectedPad = selectedPad,
                // BUG FIX: read/write the kit for the CURRENTLY SELECTED BANK
                // (bankKitIdx()), not always Bank A's currentKit — see the
                // bankKitIdx() comment near bankMode for why.
                padVolume = kits[bankKitIdx()].volumes[selectedPad],
                padPitch = kits[bankKitIdx()].pitches[selectedPad],


                onVolumeChange = {
                    kits[bankKitIdx()].volumes[selectedPad] = it
                    persistKitsDebounced()
                },

                onPitchChange = {
                    kits[bankKitIdx()].pitches[selectedPad] = it
                    persistKitsDebounced()
                },
                kits          = kits,
                currentKit    = currentKit,
                bankKitIdx    = bankKitIdx(),
                onKitAdd = {

                    // ── NEW: user khud jo naya kit add karega uske pads
                    // khaali (-1 = no sound) rahenge, default pad1-8 sound nahi bharega ──
                    val freeSlot = firstFreeBankASlot()
                    if (freeSlot != null) {

                        kits[freeSlot] = Kit(
                            generateNextKitName(),
                            sounds = mutableStateListOf(
                                -1, -1, -1, -1, -1, -1, -1, -1
                            ),
                            factoryKitNumber = -1
                        )

                        currentKit = freeSlot
                        persistKits()   // NEW
                    } else {
                        android.widget.Toast.makeText(
                            context, "All 200 kit slots are in use", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onKitDelete   = { deleteKit(currentKit) },
                onKitPrev     = { if (currentKit > 0) currentKit-- },
                // Bank A stepping stays inside 0..(BANK_A_KIT_CAPACITY-1) —
                // never crosses into Bank B's reserved pool. A kit an older
                // install may have organically grown past 200 via
                // Import Patch/Load Kit (uncapped there) is still reachable
                // through the Patch List's search, just not via +/- stepping.
                onKitNext     = { if (currentKit < BANK_A_KIT_CAPACITY - 1) currentKit++ },
                onOpenKitList = { kitListTargetsBankB = false; showKitList = true },
                onOpenKitListB = { kitListTargetsBankB = true; showKitList = true },
                // ── NEW callbacks ──────────────────────────────────────────────
                onOpenImport = { importTargetPad = null; topPanel = "IMPORT" },
                onOpenAudios = { topPanel = "AUDIOS" },
                onOpenExport = { topPanel = "EXPORT" },
                onOpenEdit   = { topPanel = "EDIT" },
                onImportToPad = { importTargetPad = selectedPad; topPanel = "IMPORT" },
                editModeOn = editModeOn,
                onEditModeChange = { editModeOn = it },
                onOpenMapMidi = { topPanel = "MIDI_LEARN" },
                onOpenLoadKit = { topPanel = "LOAD_KIT" },
                onOpenBackup = { topPanel = "BACKUP" },
                onOpenImportPatch = { topPanel = "IMPORT_PATCH" },

                onRenameKit = {
                    renameKitIndex = currentKit
                    newKitName = kits[currentKit].name
                    showRenameDialog = true
                },
                // ── NEW: live waveform/timer ────────────────────────────────────
                playingPadUri       = playingPadUri,
                playingDefaultResId = if (playingPadUri == null)
                    kits[bankKitIdx()].sounds[selectedPad].takeIf { it > 0 } ?: 0
                else 0,
                playbackPositionMs  = playbackPositionMs,
                playbackDurationMs  = playbackDurationMs,
                waveformVersion = waveformVersion,
                isRecording = isRecording,

                onRecordClick = {

                    if (isRecording) {

                        stopRecordingAndSave()

                    } else {

                        recordingPad = selectedPad   // ✅ start hote hi pad ko lock kar do

                        val myRecordingToken = System.nanoTime()
                        recordingToken = myRecordingToken   // ✅ is session ka unique ID

                        // BUG FIX: startRecording() can fail (mic busy with
                        // another app, hardware error) and now returns null
                        // instead of throwing — only flip isRecording=true
                        // (which drives the "recording…" UI) if it actually
                        // started, otherwise the UI used to show a live
                        // recording indicator for a recording that silently
                        // never happened.
                        val started = padRecorder.startRecording()

                        if (started != null) {
                            android.util.Log.d("REC", "Recording Started")
                            isRecording = true

                            // ✅ NEW: 30 second baad agar user ne khud stop nahi kiya to auto-stop + save
                            scope.launch {
                                delay(30_000)
                                // Sirf tabhi stop karo agar yehi recording session abhi bhi chal rahi he
                                // (agar user pehle hi stop kar chuka he, ya naya recording start ho gaya he, to skip)
                                if (recordingToken == myRecordingToken && isRecording) {
                                    android.util.Log.d("REC", "Auto-stopped after 30 seconds")
                                    stopRecordingAndSave()
                                }
                            }
                        } else {
                            android.util.Log.e("REC", "Recording failed to start (mic busy or unavailable)")
                            recordingPad = -1
                            android.widget.Toast.makeText(
                                context,
                                "Couldn't start recording — microphone may be in use by another app",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
            )
        }

        // ── KitListScreen overlay ─────────────────────────────────────────────
        // Shared between Bank A and Bank B (kitListTargetsBankB picks which
        // bank's selection this screen edits) — same list, same 200 slots,
        // just a different currentKit/currentKitB write target so Bank B can
        // jump straight to a kit number instead of only stepping with < / >.
        if (showKitList) {
            KitListScreen(
                kits       = kits,
                currentKit = if (kitListTargetsBankB) currentKitB else currentKit,
                // BUG FIX (B bank kit isolation): Bank B's list must only
                // show/act on its own reserved pool, never Bank A's — see
                // BANK_B_KIT_START/BANK_B_KIT_END near the top of this file.
                visibleRange = if (kitListTargetsBankB) BANK_B_KIT_START..BANK_B_KIT_END else 0 until BANK_A_KIT_CAPACITY,
                onSelect   = { index ->
                    if (kitListTargetsBankB) currentKitB = index else currentKit = index
                    showKitList = false
                },

                onAdd = {
                    if (kitListTargetsBankB) {
                        // Bank B's pool is fixed-size — "add" jumps to the
                        // next still-blank slot inside it instead of
                        // growing the array (which would land outside the
                        // range currentKitB is confined to).
                        val freeSlot = firstFreeBankBSlot()
                        if (freeSlot != null) {
                            kits[freeSlot] = kits[freeSlot].copy(name = generateNextKitName())
                            currentKitB = freeSlot
                            persistKits()
                        }
                    } else {
                        // ── NEW: yaha se add kiya gaya kit bhi khaali (-1) pads ke saath banega ──
                        val freeSlot = firstFreeBankASlot()
                        if (freeSlot != null) {
                            kits[freeSlot] = Kit(
                                generateNextKitName(),
                                sounds = mutableStateListOf(
                                    -1, -1, -1, -1, -1, -1, -1, -1
                                ),
                                factoryKitNumber = -1
                            )
                            currentKit = freeSlot
                            persistKits()   // NEW
                        } else {
                            android.widget.Toast.makeText(
                                context, "All 200 kit slots are in use", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },


                onDelete   = { index -> deleteKit(index) },
                onCopy     = { index -> copyKit(index, intoBankB = kitListTargetsBankB) },
                onRename   = { index ->
                    // Direct/inline patch editing: rename right from the
                    // Patch List instead of needing Settings → Rename Kit.
                    renameKitIndex = index
                    newKitName = kits[index].name
                    showRenameDialog = true
                },
                onExportPatch = { index ->
                    // PatchExportScreen reads from KitRepository's persisted
                    // storage, so make sure it's fresh first.
                    persistKits()
                    exportPatchKitIndex = index
                    showKitList = false
                    topPanel = "EXPORT_PATCH"
                },
                onClose    = { showKitList = false }
            )
        }

        if (showRenameDialog) {

            fun saveRenamedKit() {
                if (newKitName.isNotBlank() && renameKitIndex in kits.indices) {

                    val alreadyExists = kits.anyIndexed { index, kit ->
                        index != renameKitIndex &&
                                kit.name.equals(
                                    newKitName.trim(),
                                    ignoreCase = true
                                )
                    }

                    if (alreadyExists) {
                        android.widget.Toast.makeText(
                            context,
                            "Kit name must be unique",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return
                    }

                    kits[renameKitIndex] = kits[renameKitIndex].copy(
                        name = newKitName.trim()
                    )
                    persistKits()
                }

                showRenameDialog = false
            }

            DisposableEffect(Unit) {
                com.example.myapplication.KeyboardPlayState.textInputActive = true
                onDispose { com.example.myapplication.KeyboardPlayState.textInputActive = false }
            }

            AlertDialog(
                // BUG FIX: since MainActivity now runs edge-to-edge
                // (WindowCompat.setDecorFitsSystemWindows(window, false) for
                // the fullscreen/immersive look), the OS no longer
                // automatically resizes/pans content to avoid the on-screen
                // keyboard — Compose has to handle that itself. Without
                // imePadding() here, typing a kit name pushed the keyboard up
                // over the SAVE/CANCEL buttons, making them unreachable.
                modifier = Modifier.imePadding(),
                onDismissRequest = {
                    showRenameDialog = false
                },

                title = {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Text("Rename Kit")
                        // Keyboard covers the bottom SAVE button once typing
                        // starts (imePadding keeps the dialog itself on
                        // screen, but the text field can still push the
                        // confirm button below the visible area) — a second
                        // SAVE action up here next to the title is always
                        // reachable regardless of keyboard height.
                        TextButton(onClick = { saveRenamedKit() }) {
                            androidx.compose.material3.Text("SAVE")
                        }
                    }
                },

                text = {
                    OutlinedTextField(
                        value = newKitName,
                        onValueChange = {
                            newKitName = it
                        },
                        label = {
                            androidx.compose.material3.Text("Kit Name")
                        }
                    )
                },

                confirmButton = {
                    TextButton(onClick = { saveRenamedKit() }) {
                        androidx.compose.material3.Text("SAVE")
                    }
                },

                dismissButton = {
                    TextButton(
                        onClick = {
                            showRenameDialog = false
                        }
                    ) {
                        androidx.compose.material3.Text("CANCEL")
                    }
                }
            )
        }

        when (topPanel) {

            // BUG FIX: these three used to always read/write kits[currentKit]
            // (Bank A) no matter which bank was actually selected — an
            // import/crop-edit/library view while Bank B/C was active
            // silently touched Bank A's (inaudible) kit instead of the one
            // the user was looking at/hearing. Routed through bankKitIdx(),
            // same fix pattern as the FX knobs and swap/clear/record.
            "IMPORT" -> {
                val importKitIdx = bankKitIdx()
                ImportScreen(
                    onClose = { topPanel = ""; importTargetPad = null },
                    currentKit = importKitIdx,
                    targetPad = importTargetPad,
                    targetPadDefaultResId = importTargetPad?.let {
                        kits[importKitIdx].sounds.getOrElse(it) { -1 }
                    } ?: -1
                )
            }

            "AUDIOS" -> {
                val audiosKitIdx = bankKitIdx()
                AudioListScreen(
                    currentKit = audiosKitIdx,
                    factoryResIds = kits[audiosKitIdx].sounds,
                    onClose = { topPanel = "" }
                )
            }

            "EXPORT" -> {
                ExportScreen(
                    onClose = { topPanel = "" }
                )
            }

            "EDIT" -> {
                val editKitIdx = bankKitIdx()
                WaveformEditorScreen(
                    kitIndex = editKitIdx,
                    padIndex = selectedPad,
                    factoryResId = kits[editKitIdx].sounds.getOrElse(selectedPad) { -1 },
                    onClose  = { topPanel = "" },
                    // Same one-tap import flow EQPanel's "IMPORT TO THIS PAD"
                    // already uses — no import logic duplicated.
                    onPickNewSound = { importTargetPad = selectedPad; topPanel = "IMPORT" },
                    onClearSound = {
                        clearPadSound(selectedPad)
                        topPanel = ""
                    },
                    initialCropStartPct = kits[editKitIdx].padCropStartPct.getOrElse(selectedPad) { 0f },
                    initialLengthPct = kits[editKitIdx].padLengthPct.getOrElse(selectedPad) { 1f },
                    onCommitCrop = { startPct, endPct, destructive ->
                        if (editKitIdx in kits.indices) {
                            if (destructive) {
                                // File was rewritten to the trimmed clip —
                                // the non-destructive window goes back to full.
                                kits[editKitIdx].padCropStartPct[selectedPad] = 0f
                                kits[editKitIdx].padLengthPct[selectedPad] = 1f
                            } else {
                                kits[editKitIdx].padCropStartPct[selectedPad] = startPct.coerceIn(0f, 0.95f)
                                kits[editKitIdx].padLengthPct[selectedPad] = endPct.coerceIn(0.05f, 1f)
                            }
                            persistKits()
                        }
                    }
                )
            }

            "MIDI_LEARN" -> {
                if (midiPurchased) {
                    MidiLearnScreen(
                        midiChannel = midiChannel,
                        onMidiChannelChange = { midiChannel = it },
                        onClose = { topPanel = "" }
                    )
                } else {
                    MidiPaywallScreen(onClose = { topPanel = "" })
                }
            }

            "BACKUP" -> {
                com.example.myapplication.ui.audio.BackupScreen(
                    onClose = { topPanel = "" }
                )
            }

            "EXPORT_PATCH" -> {
                if (exportPatchKitIndex in kits.indices) {
                    com.example.myapplication.ui.audio.PatchExportScreen(
                        kitIndex = exportPatchKitIndex,
                        kitName = kits[exportPatchKitIndex].name,
                        onClose = { topPanel = ""; exportPatchKitIndex = -1 }
                    )
                }
            }

            "IMPORT_PATCH" -> {
                com.example.myapplication.ui.audio.ImportPatchScreen(
                    onClose = { topPanel = "" },
                    onPatchImported = { entry, importedAudios ->
                        val sounds = if (entry.factoryKitNumber != -1) {
                            buildKitSounds(entry.factoryKitNumber)
                        } else {
                            mutableStateListOf(-1, -1, -1, -1, -1, -1, -1, -1)
                        }
                        val newKit = Kit(
                            name = entry.name,
                            volumes = mutableStateListOf<Float>().apply { addAll(entry.volumes) },
                            pitches = mutableStateListOf<Float>().apply { addAll(entry.pitches) },
                            padLevels = mutableStateListOf<Float>().apply { addAll(entry.padLevels) },
                            padEqLow = mutableStateListOf<Float>().apply { addAll(entry.padEqLow) },
                            padEqMid = mutableStateListOf<Float>().apply { addAll(entry.padEqMid) },
                            padEqHigh = mutableStateListOf<Float>().apply { addAll(entry.padEqHigh) },
                            padDelayMs = mutableStateListOf<Int>().apply { addAll(entry.padDelayMs) },
                            padLengthPct = mutableStateListOf<Float>().apply { addAll(entry.padLengthPct) },
                            padCropStartPct = mutableStateListOf<Float>().apply { addAll(entry.padCropStartPct) },
                            padReverse = mutableStateListOf<Boolean>().apply { addAll(entry.padReverse) },
                            padPlayMode = mutableStateListOf<String>().apply { addAll(entry.padPlayMode) },
                            padPan = mutableStateListOf<Float>().apply { addAll(entry.padPan) },
                            padGain = mutableStateListOf<Float>().apply { addAll(entry.padGain) },
                            padDelayEnabled = mutableStateListOf<Boolean>().apply { addAll(entry.padDelayEnabled) },
                            sounds = sounds,
                            factoryKitNumber = entry.factoryKitNumber,
                            chokeGroups = entry.chokeGroups.map { levels -> mutableStateListOf<Int>().apply { addAll(levels) } },
                            activeChokeLevelState = mutableStateOf(entry.activeChokeLevel)
                        )
                        val newKitIndex = firstFreeBankASlot()
                        if (newKitIndex == null) {
                            android.widget.Toast.makeText(
                                context, "All 200 kit slots are in use", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            kits[newKitIndex] = newKit
                            currentKit = newKitIndex

                            importedAudios.forEach { ia ->
                                AudioRepository.add(
                                    com.example.myapplication.ui.audio.AudioItem(
                                        id = System.currentTimeMillis() + ia.padIndex,
                                        name = ia.name,
                                        uri = Uri.fromFile(ia.file),
                                        durationMs = ia.durationMs
                                    ).also {
                                        it.assignedPad = ia.padIndex
                                        it.assignedKit = newKitIndex
                                    }
                                )
                                DrumEngine.invalidatePad(ia.padIndex)
                            }

                            persistKits()
                        }
                        topPanel = ""
                    }
                )
            }

            "LOAD_KIT" -> {
                LoadKitScreen(
                    currentKitCount = kits.size,
                    onKitLoaded = { name, files ->
                        val newKitIndex = firstFreeBankASlot()
                      if (newKitIndex == null) {
                        android.widget.Toast.makeText(
                            context, "All 200 kit slots are in use", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        topPanel = ""
                      } else {
                        // Create new empty kit
                        kits[newKitIndex] = Kit(
                            name   = name,
                            sounds = mutableStateListOf(-1,-1,-1,-1,-1,-1,-1,-1),
                            factoryKitNumber = -1
                        )
                        currentKit = newKitIndex

                        // Assign each file to its pad slot
                        files.forEachIndexed { padIndex, (uri, displayName) ->
                            val retriever = android.media.MediaMetadataRetriever()
                            val durationMs = try {
                                retriever.setDataSource(context, uri)
                                retriever.extractMetadata(
                                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                                )?.toLongOrNull() ?: 0L
                            } catch (e: Exception) { 0L }
                            finally { retriever.release() }

                            AudioRepository.add(
                                com.example.myapplication.ui.audio.AudioItem(
                                    id = System.currentTimeMillis() + padIndex,
                                    name = displayName,
                                    uri  = uri,
                                    durationMs = durationMs
                                ).also { item ->
                                    item.assignedPad = padIndex
                                    item.assignedKit = newKitIndex
                                }
                            )
                            DrumEngine.invalidatePad(padIndex)
                        }

                        persistKits()
                        topPanel = ""
                      }
                    },
                    onClose = { topPanel = "" }
                )
            }
        }

        DragPadOverlay(
            visible   = dragVisible,
            padNumber = dragPad,
            x         = dragX,
            y         = dragY
        )

        // ── EDIT MODE contextual menu — Clear Sound / Add Sound for the
        // pad that was just tapped while Edit Mode was on (see the gate at
        // the top of onPadHit()). Tapping outside the card dismisses it.
        if (editMenuPad != null) {
            val editPadIdx = editMenuPad!!
            // Clamp against maxWidth instead of a fixed 200.dp — a fixed width
            // isn't guaranteed to fit inside the available screen width on the
            // smallest supported landscape phones.
            val editMenuOptionWidth = (maxWidth * 0.55f).coerceIn(140.dp, 200.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000))
                    .clickable(remember { MutableInteractionSource() }, null) { editMenuPad = null },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF111111))
                        .clickable(remember { MutableInteractionSource() }, null) { /* consume, don't dismiss */ }
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    androidx.compose.material3.Text(
                        "EDIT PAD ${editPadIdx + 1}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Box(
                        modifier = Modifier
                            .width(editMenuOptionWidth)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1A1A1A))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                importTargetPad = editPadIdx
                                topPanel = "IMPORT"
                                editMenuPad = null
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Text(
                            "ADD SOUND", color = Color(0xFF00E5FF),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(editMenuOptionWidth)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF330000))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                clearPadSound(editPadIdx)
                                editMenuPad = null
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Text(
                            "CLEAR SOUND", color = Color(0xFFFF6666),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(editMenuOptionWidth)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2A2A2A))
                            .clickable(remember { MutableInteractionSource() }, null) { editMenuPad = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Text(
                            "CANCEL", color = Color(0xFFCCCCCC),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }

                    // NEW: explicit way out of Edit Mode right from the menu
                    // itself — closes this popup AND turns Edit Mode off, so
                    // pads go back to playing normally without having to find
                    // the round EDIT button again.
                    Box(
                        modifier = Modifier
                            .width(editMenuOptionWidth)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF3A2A00))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                editModeOn = false
                                editMenuPad = null
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Text(
                            "EXIT EDIT MODE", color = Color(0xFFFFB74D),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    PadActionMenu(
        visible = showPadMenu,
        // BUG FIX: onMix/onAddToEnd used to always target kits[currentKit]
        // (Bank A) and only invalidate native slot t (0-7) — mixing/
        // concatenating while Bank B/C was active silently wrote into Bank
        // A's (inaudible) kit and never invalidated the native slot that was
        // actually audible, so the result never seemed to apply. Routed
        // through bankKitIdx()/nativeSlotsFor(), same fix pattern as swap.
        onMix = {
            showPadMenu = false
            // sourcePad/targetPad are 1-based pad numbers (1..8) from the drag
            // gesture — AudioRepository/PcmMixer index pads 0-based, so convert.
            val s = sourcePad - 1
            val t = targetPad - 1
            if (s in 0..7 && t in 0..7 && s != t) {
                val kitIdx = bankKitIdx()
                scope.launch {
                    val file = com.example.myapplication.ui.audio.PcmMixer.mixPads(
                        context  = context,
                        kitIndex = kitIdx,
                        padA     = s,
                        padB     = t,
                        factoryResIds = kits[kitIdx].sounds
                    )
                    if (file != null) {
                        AudioRepository.assignRecordedAudio(
                            kitIndex = kitIdx,
                            padIndex = t,
                            file     = file
                        )
                        nativeSlotsFor(t).forEach { DrumEngine.invalidatePad(it) }
                        if (selectedPad == t) updatePadDisplay(t)
                    } else {
                        android.util.Log.w("MIX", "mixPads returned null for pads $s,$t in kit $kitIdx")
                    }
                }
            }
        },
        onAddToEnd = {
            showPadMenu = false
            // "Add To End" = play sourcePad's audio, then targetPad's audio,
            // back-to-back as a single new sample on targetPad.
            val s = sourcePad - 1
            val t = targetPad - 1
            if (s in 0..7 && t in 0..7 && s != t) {
                val kitIdx = bankKitIdx()
                scope.launch {
                    val file = com.example.myapplication.ui.audio.PcmMixer.concatPads(
                        context  = context,
                        kitIndex = kitIdx,
                        padA     = s,
                        padB     = t,
                        factoryResIds = kits[kitIdx].sounds
                    )
                    if (file != null) {
                        AudioRepository.assignRecordedAudio(
                            kitIndex = kitIdx,
                            padIndex = t,
                            file     = file
                        )
                        nativeSlotsFor(t).forEach { DrumEngine.invalidatePad(it) }
                        if (selectedPad == t) updatePadDisplay(t)
                    } else {
                        android.util.Log.w("MIX", "concatPads returned null for pads $s,$t in kit $kitIdx")
                    }
                }
            }
        },
        onSwap = {
            swapPads(sourcePad, targetPad)
            showPadMenu = false
        },
        onCancel = {
            showPadMenu = false
        }
    )
}
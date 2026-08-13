// OctapadScreen.kt
package com.example.myapplication.ui
import com.example.myapplication.LatencyTracker
import com.example.myapplication.MidiEventBus
import com.example.myapplication.MidiLearnRepository
import android.media.SoundPool
import android.net.Uri
import com.example.myapplication.NativeBridge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.audio.AudioListScreen
import com.example.myapplication.ui.audio.AudioRepository
import com.example.myapplication.ui.audio.ExportScreen
import com.example.myapplication.ui.audio.ImportScreen
import com.example.myapplication.ui.audio.WaveformEditorScreen
import com.example.myapplication.ui.audio.LoadKitScreen
import com.example.myapplication.ui.kit.KitListScreen
import com.example.myapplication.ui.pads.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.myapplication.ui.drag.DragPadOverlay
import com.example.myapplication.ui.drag.PadActionMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.example.myapplication.ui.audio.PadRecorder
import com.example.myapplication.ui.audio.DrumEngine
import android.media.ToneGenerator
import android.media.AudioManager
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
    var exclusiveMode by remember { mutableStateOf(PreferencesRepository.loadExclusiveMode()) }
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

    val metronomeToneGen = remember {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80)  // 80 = volume (0-100)
    }

    var metronomeOn by remember { mutableStateOf(PreferencesRepository.loadMetronomeOn()) }

    // NEW: Total beats counter (incremented on each pad hit)
    var totalBeats by remember { mutableStateOf(0) }

    // NEW: Delay effect state. delayEnabled itself is per-pad-per-kit now
    // (kits[currentKit].padDelayEnabled) — see curPadDelayEnabled below —
    // not a single global toggle, so switching patches doesn't leave delay
    // stuck on/off from whatever the last patch had it set to.
    var delayChokePad by remember { mutableStateOf(PreferencesRepository.loadDelayChokePad()) }
    // NEW: delay decay/feedback amount (0f..1f) — was a hardcoded 0.5f, now a
    // FX-panel knob ("DELAY LEVEL").
    var delayLevel by remember { mutableStateOf(PreferencesRepository.loadDelayLevel()) }
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

        kitList
    }

    var currentKit by remember { mutableStateOf(KitRepository.loadLastSelectedKit()) }

    // ── A/B/C Bank: currentKit above is Bank A's kit. Bank B and Bank C each
    // have their own kit slot; bankMode is a string containing any subset of
    // the letters 'A'/'B'/'C' — every letter present layers that bank's
    // sound on top of the others on a pad hit ("AB", "ABC", "C", etc.).
    var currentKitB by remember { mutableStateOf(PreferencesRepository.loadKitB()) }
    var currentKitC by remember { mutableStateOf(PreferencesRepository.loadKitC()) }
    var bankMode by remember { mutableStateOf(PreferencesRepository.loadBankMode()) }

    LaunchedEffect(currentKitB) { PreferencesRepository.saveKitB(currentKitB) }
    LaunchedEffect(currentKitC) { PreferencesRepository.saveKitC(currentKitC) }
    LaunchedEffect(bankMode)    { PreferencesRepository.saveBankMode(bankMode) }

    LaunchedEffect(Unit) {
        if (currentKitB !in kits.indices) currentKitB = 0
        if (currentKitC !in kits.indices) currentKitC = 0
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
                    padReverse = it.padReverse.toList(),
                    padPlayMode = it.padPlayMode.toList(),
                    padPan  = it.padPan.toList(),
                    padGain = it.padGain.toList(),
                    padDelayEnabled = it.padDelayEnabled.toList()
                )
            }
        )
    }

    LaunchedEffect(currentKit) {
        KitRepository.saveLastSelectedKit(currentKit)
    }

    var showKitList  by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    // NEW: which kit's single-patch export dialog is open, -1 = none
    var exportPatchKitIndex by remember { mutableStateOf(-1) }
    var newKitName by remember { mutableStateOf("") }

    // ── NEW: Audio screen states ───────────────────────────────────────────────
    var topPanel by remember { mutableStateOf("") }
    // NEW: when set, ImportScreen assigns a successful import straight to
    // this pad instead of leaving it unassigned in the library — the
    // one-tap "IMPORT TO THIS PAD" entry point in the FX panel.
    var importTargetPad by remember { mutableStateOf<Int?>(null) }

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

    val scope        = rememberCoroutineScope()

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
    fun stopRecordingAndSave() {
        val file = padRecorder.stopRecording()

        if (file != null) {
            AudioRepository.assignRecordedAudio(
                kitIndex = currentKit,
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
        // NEW: when Velocity is OFF, every hit (touch or MIDI) plays at full
        // volume regardless of how hard/soft the physical hit was.
        val effectiveVelocity = if (velocityOn) velocityMultiplier else 1f
        // Per-pad FX playback mode: ONESHOT (default, obeys the global Loop
        // toggle), LOOP (always repeats at tempo), MIX (layers on top of any
        // already-sounding voice for this pad instead of cutting it off).
        val playMode = kits[currentKit].padPlayMode.getOrElse(index) { "ONESHOT" }
        val allowOverlap = playMode == "MIX"
        val effectiveLoop = when (playMode) {
            "LOOP" -> true
            "MIX"  -> false
            else   -> loopEnabled
        }
        // A/B/C Bank: which native slot(s) this logical pad should sound on.
        // Each letter present in bankMode contributes its own slot, layered
        // together — e.g. "AC" plays Bank A's + Bank C's sound at once.
        fun nativeSlotsFor(padIdx: Int): List<Int> {
            val slots = mutableListOf<Int>()
            if ('A' in bankMode) slots.add(padIdx)
            if ('B' in bankMode) slots.add(padIdx + 8)
            if ('C' in bankMode) slots.add(padIdx + 16)
            return if (slots.isEmpty()) listOf(padIdx) else slots
        }
        val bankSlots = nativeSlotsFor(index)
        android.util.Log.d("PADHIT", "onPadHit called for index=$index vel=$effectiveVelocity")

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
        val assigned = AudioRepository.audioForPad(currentKit, index)
        val uriToShow: Uri?
        val durationToShow: Long
        if (exclusiveMode && currentStreamId != 0) {
            soundPool.stop(currentStreamId)
        }
        if (assigned != null) {
            uriToShow      = assigned.uri
            durationToShow = assigned.durationMs
        } else {
            uriToShow      = null
            // BUG FIX: this used to always guess DEFAULT_PAD_DURATION_MS
            // (500ms) for factory samples. Any factory sample actually
            // longer than that got cut short every time it retriggered
            // (LOOP mode) and had a wrong/truncated LCD progress bar —
            // reported as "tone cut" and "loop still broken". Now uses the
            // real decoded duration (cached by DrumEngine.loadPad the first
            // time this pad's sample was loaded) when available.
            val factoryResId = kits[currentKit].sounds.getOrElse(index) { -1 }
            durationToShow = com.example.myapplication.ui.audio.PadDurationCache.get(factoryResId)
                ?: DEFAULT_PAD_DURATION_MS
        }

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

        if (exclusiveMode) {
            val activeLevel = kits[currentKit].activeChokeLevelState.value
            if (activeLevel != 0) {
                val myGroups = kits[currentKit].chokeGroups[index]
                if (activeLevel in myGroups) {
                    chokeActivePads.keys.toList().forEach { otherPad ->
                        if (otherPad != index) {
                            val otherGroups = kits[currentKit].chokeGroups[otherPad]
                            if (activeLevel in otherGroups) {
                                nativeSlotsFor(otherPad).forEach { DrumEngine.stop(it) }
                                loopTokens[otherPad] = -1L
                                chokeActivePads.remove(otherPad)
                            }
                        }
                    }
                }
            }
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
                    slot >= 16 -> currentKitC
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
                        gain = kits[kitForSlot].padGain.getOrElse(index) { 1f }
                    )
                }
            }
            currentlyPlayingPad = index
            if (exclusiveMode) {
                chokeActivePads[index] = token
            }
        }

        pressedPads[index] = true
        totalBeats++

        var myToken = System.nanoTime()
        val padLatency = (System.nanoTime() - LatencyTracker.midiTime) / 1_000_000.0
        android.util.Log.d("LATENCY", "Pad ${index + 1} → Latency = $padLatency ms")
        fire(myToken)   // ← the sound actually starts here, synchronously

        // ── From here on it's just waiting/looping/UI bookkeeping ──────────
        scope.launch {
            try {
                var keepGoing = true

                while (keepGoing) {
                    // SPEED (LOOP panel) scales the tempo-synced interval —
                    // >1x plays the beat grid faster, <1x slower, independent of BPM.
                    val beatIntervalMs = ((60_000L / bpm.coerceAtLeast(1)) / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(50L)
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
                    val waitWindowMs = when {
                        playMode == "LOOP" -> durationToShow
                        effectiveLoop       -> maxOf(beatIntervalMs, durationToShow)
                        else                 -> durationToShow
                    }

                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < waitWindowMs) {
                        // ✅ FIX: sirf display update latestHitToken pe depend karta he,
                        // actual wait/timing kisi doosre pad ke hit se interrupt NAHI hoti —
                        // isse har pad apna full tempo-interval khud independently wait karta he
                        if (latestHitToken == myToken) {
                            playbackPositionMs = (System.currentTimeMillis() - startTime).coerceAtMost(durationToShow)
                        }
                        delay(50)
                    }

                    if (latestHitToken == myToken) {
                        playbackPositionMs = waitWindowMs.coerceAtMost(durationToShow)
                    }

                    // ✅ NEW: agla loop chalega ya nahi — Loop toggle ON he
                    // AND koi doosre pad-hit/exclusive-stop ne isse invalidate nahi kiya
                    keepGoing = effectiveLoop && loopTokens[index] == myLoopToken

                    if (keepGoing) {
                        // Timer-driven re-trigger (not touch-driven), so firing it
                        // from inside the coroutine is fine — it's tempo-locked
                        // via waitWindowMs above regardless of dispatch timing.
                        myToken = System.nanoTime()
                        fire(myToken)
                    } else {
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
    DisposableEffect(Unit) {
        onDispose {
            metronomeToneGen.release()
        }
    }


    // ── NEW: MIDI knob (Control Change) → live volume/pitch on selected pad ───
    LaunchedEffect(Unit) {
        AudioRepository.init(context)
        DrumEngine.ensureStarted()
        // Restore any saved Note->pad mappings into native (pads with no
        // saved mapping keep native's GM default) — coexists with CC-based
        // pad mapping via CcMapRepository's PAD_1..PAD_8 targets.
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
                currentKit = program.coerceIn(0, kits.lastIndex)
            }
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
    val curPadDelayMs = if (currentKit in kits.indices)
        kits[currentKit].padDelayMs.getOrElse(selectedPad) { 300 } else 300

    // NEW: delay on/off read from the CURRENT pad's own kit — per-pad-per-kit,
    // not a single global toggle, so it never leaks into a different patch.
    val curPadDelayEnabled = if (currentKit in kits.indices)
        kits[currentKit].padDelayEnabled.getOrElse(selectedPad) { false } else false

    fun setCurPadDelayEnabled(enabled: Boolean) {
        if (currentKit in kits.indices) {
            kits[currentKit].padDelayEnabled[selectedPad] = enabled
            persistKits()
        }
    }

    LaunchedEffect(curPadDelayEnabled, selectedPad, currentKit, delayChokePad, curPadDelayMs, delayLevel) {
        NativeBridge.setDelayEnabled(curPadDelayEnabled)
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

    // Sync per-pad EQ+level to native whenever selected pad or kit changes
    val curPadLevel = if (currentKit in kits.indices) kits[currentKit].padLevels[selectedPad] else 1f
    val curPadEqLow = if (currentKit in kits.indices) kits[currentKit].padEqLow[selectedPad]  else 1f
    val curPadEqMid = if (currentKit in kits.indices) kits[currentKit].padEqMid[selectedPad]  else 1f
    val curPadEqHigh= if (currentKit in kits.indices) kits[currentKit].padEqHigh[selectedPad] else 1f

    LaunchedEffect(selectedPad, currentKit, curPadLevel, curPadEqLow, curPadEqMid, curPadEqHigh) {
        NativeBridge.setMasterLevel(curPadLevel)
        NativeBridge.setEqBands(curPadEqLow, curPadEqMid, curPadEqHigh)
    }

    // Sync per-pad Pan/Gain to native — unlike EQ/Level (global/master in the
    // native engine), Pan and Gain are true per-voice fields keyed by pad
    // index, so this updates whatever's currently playing on THIS pad, not
    // the whole mix.
    val curPadPan  = if (currentKit in kits.indices) kits[currentKit].padPan.getOrElse(selectedPad) { 0f }  else 0f
    val curPadGain = if (currentKit in kits.indices) kits[currentKit].padGain.getOrElse(selectedPad) { 1f } else 1f

    LaunchedEffect(selectedPad, currentKit, curPadPan) {
        DrumEngine.setPan(selectedPad, curPadPan)
    }
    LaunchedEffect(selectedPad, currentKit, curPadGain) {
        DrumEngine.setGain(selectedPad, curPadGain)
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

    // NEW: Persist metronome state whenever it changes
    LaunchedEffect(metronomeOn) {
        PreferencesRepository.saveMetronomeOn(metronomeOn)
    }

    // NEW: Persist remaining app state so re-opening the app resumes exactly
    // where it was left off, instead of resetting to defaults.
    LaunchedEffect(loopEnabled)     { PreferencesRepository.saveLoopEnabled(loopEnabled) }
    LaunchedEffect(exclusiveMode)   { PreferencesRepository.saveExclusiveMode(exclusiveMode) }
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

    // Bank C -> native slots 16-23, same pattern as Bank B.
    LaunchedEffect(
        currentKitC,
        AudioRepository.audios.map {
            Triple(
                it.id,
                it.assignedPad,
                it.assignedKit
            )
        },
        if (currentKitC in kits.indices) kits[currentKitC].padReverse.toList() else emptyList()
    ) {
        if (currentKitC in kits.indices) {
            for (pad in 0 until 8) {
                DrumEngine.loadPad(
                    context,
                    currentKitC,
                    pad,
                    kits[currentKitC].sounds[pad],
                    reversed = kits[currentKitC].padReverse.getOrElse(pad) { false },
                    nativeSlot = pad + 16
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
            val buttonPressed = ccValue > 63   // treat as a momentary button for the non-continuous targets

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
                    kits[currentKit].volumes[selectedPad] = newVolume
                    DrumEngine.setVolume(selectedPad, newVolume)
                    persistKits()   // NEW: save immediately so it survives app close

                    // ✅ NEW: volume knob latency
                    val volLatency = (System.nanoTime() - LatencyTracker.midiTime) / 1_000_000.0
                    android.util.Log.d("LATENCY", "Pad ${selectedPad + 1} VOLUME → Latency = $volLatency ms")
                }

                cc.getCc("PITCH") -> {
                    val newPitch = 0.5f + normalized * 1.5f
                    kits[currentKit].pitches[selectedPad] = newPitch
                    DrumEngine.setPitch(selectedPad, newPitch)
                    persistKits()   // NEW: save immediately so it survives app close

                    // ✅ NEW: pitch knob latency
                    val pitchLatency = (System.nanoTime() - LatencyTracker.midiTime) / 1_000_000.0
                    android.util.Log.d("LATENCY", "Pad ${selectedPad + 1} PITCH → Latency = $pitchLatency ms")
                }

                cc.getCc("EQ_LOW") -> {
                    kits[currentKit].padEqLow[selectedPad] = normalized * 2f
                    NativeBridge.setEqBands(kits[currentKit].padEqLow[selectedPad], kits[currentKit].padEqMid[selectedPad], kits[currentKit].padEqHigh[selectedPad])
                    persistKits()
                }

                cc.getCc("EQ_MID") -> {
                    kits[currentKit].padEqMid[selectedPad] = normalized * 2f
                    NativeBridge.setEqBands(kits[currentKit].padEqLow[selectedPad], kits[currentKit].padEqMid[selectedPad], kits[currentKit].padEqHigh[selectedPad])
                    persistKits()
                }

                cc.getCc("EQ_HIGH") -> {
                    kits[currentKit].padEqHigh[selectedPad] = normalized * 2f
                    NativeBridge.setEqBands(kits[currentKit].padEqLow[selectedPad], kits[currentKit].padEqMid[selectedPad], kits[currentKit].padEqHigh[selectedPad])
                    persistKits()
                }

                cc.getCc("PATCH_NEXT") -> {
                    if (buttonPressed && currentKit < kits.lastIndex) currentKit++
                }

                cc.getCc("PATCH_PREV") -> {
                    if (buttonPressed && currentKit > 0) currentKit--
                }

                cc.getCc("EDIT") -> {
                    if (buttonPressed) topPanel = "EDIT"
                }

                cc.getCc("SAVE") -> {
                    if (buttonPressed) persistKits()
                }

                // Pad mapping is CC-only: note-based mapping was removed, so
                // this is the only way a physical controller triggers a pad.
                // Map PAD_1..PAD_8 via the MIDI Learn screen exactly like any
                // other CC target — there's no built-in default, so a pad
                // stays unmapped until it's explicitly learned once.
                else -> {
                    for (padNum in 1..8) {
                        if (ccNumber == cc.getCc("PAD_$padNum") && cc.getCc("PAD_$padNum") != -1) {
                            if (buttonPressed) {
                                selectedPad = padNum - 1
                                onPadHit(padNum - 1, normalized)
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    // ── Metronome click loop ──────────────────────────────────────────────────
    LaunchedEffect(metronomeOn, bpm, speed) {
        while (metronomeOn) {
            metronomeToneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 50)
            totalBeats++
            delay((60_000L / bpm.coerceAtLeast(1) / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(50L))
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

    fun deleteKit(index: Int) {

        if (kits.size <= 1) return
        if (index !in kits.indices) return

        kits.removeAt(index)

        if (currentKit >= kits.size) {
            currentKit = kits.lastIndex
        }

        if (currentKit < 0) {
            currentKit = 0
        }

        persistKits()   // NEW
    }

    // NEW: duplicates kits[index] (all volumes/pitches/EQ/choke groups/custom
    // audio) as a new kit appended at the end, then selects it.
    fun copyKit(index: Int) {
        if (index !in kits.indices) return
        if (kits.size >= 200) return

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

        kits.add(newKit)
        val newIndex = kits.lastIndex
        AudioRepository.copyForKit(index, newIndex)
        currentKit = newIndex
        persistKits()
    }

    // NEW: toggles padIndex's membership in choke-level `level` (1..6) for
    // the current kit, then persists the change.
    fun toggleChokeGroup(padIndex: Int, level: Int) {
        val groups = kits[currentKit].chokeGroups[padIndex]
        if (groups.contains(level)) {
            groups.remove(level)
        } else {
            groups.add(level)
        }
        persistKits()
    }

    // NEW: sets (or clears, if the same level is tapped again) the active
    // choke level for the current kit, then persists it.
    fun setActiveChokeLevel(level: Int) {
        val current = kits[currentKit].activeChokeLevelState
        current.value = if (current.value == level) 0 else level
        persistKits()
    }




    fun swapPads(source: Int, target: Int) {



        val s = source - 1
        val t = target - 1

        // ---------- Volume ----------
        val tempVolume = kits[currentKit].volumes[s]
        kits[currentKit].volumes[s] = kits[currentKit].volumes[t]
        kits[currentKit].volumes[t] = tempVolume

        // ---------- Pitch ----------
        val tempPitch = kits[currentKit].pitches[s]
        kits[currentKit].pitches[s] = kits[currentKit].pitches[t]
        kits[currentKit].pitches[t] = tempPitch

        // ---------- Imported / Recorded Audio ----------

        val tempSound = kits[currentKit].sounds[s]

        kits[currentKit].sounds[s] =
            kits[currentKit].sounds[t]

        kits[currentKit].sounds[t] =
            tempSound

        AudioRepository.swapPads(
            currentKit,
            s,
            t
        )

        // ---------- Remaining per-pad settings ----------
        // BUG FIX: a "swap" used to only move volume/pitch/sound, leaving
        // choke group membership, EQ, delay, length, reverse and play mode
        // behind on the old pad index — the sound moved but its behavior
        // didn't, which reads as a broken/partial swap. Everything per-pad
        // now moves together.
        val tempLevel = kits[currentKit].padLevels[s]
        kits[currentKit].padLevels[s] = kits[currentKit].padLevels[t]
        kits[currentKit].padLevels[t] = tempLevel

        val tempEqLow = kits[currentKit].padEqLow[s]
        kits[currentKit].padEqLow[s] = kits[currentKit].padEqLow[t]
        kits[currentKit].padEqLow[t] = tempEqLow

        val tempEqMid = kits[currentKit].padEqMid[s]
        kits[currentKit].padEqMid[s] = kits[currentKit].padEqMid[t]
        kits[currentKit].padEqMid[t] = tempEqMid

        val tempEqHigh = kits[currentKit].padEqHigh[s]
        kits[currentKit].padEqHigh[s] = kits[currentKit].padEqHigh[t]
        kits[currentKit].padEqHigh[t] = tempEqHigh

        val tempDelayMs = kits[currentKit].padDelayMs[s]
        kits[currentKit].padDelayMs[s] = kits[currentKit].padDelayMs[t]
        kits[currentKit].padDelayMs[t] = tempDelayMs

        val tempLengthPct = kits[currentKit].padLengthPct[s]
        kits[currentKit].padLengthPct[s] = kits[currentKit].padLengthPct[t]
        kits[currentKit].padLengthPct[t] = tempLengthPct

        val tempReverse = kits[currentKit].padReverse[s]
        kits[currentKit].padReverse[s] = kits[currentKit].padReverse[t]
        kits[currentKit].padReverse[t] = tempReverse

        val tempPlayMode = kits[currentKit].padPlayMode[s]
        kits[currentKit].padPlayMode[s] = kits[currentKit].padPlayMode[t]
        kits[currentKit].padPlayMode[t] = tempPlayMode

        val tempPan = kits[currentKit].padPan[s]
        kits[currentKit].padPan[s] = kits[currentKit].padPan[t]
        kits[currentKit].padPan[t] = tempPan

        val tempGain = kits[currentKit].padGain[s]
        kits[currentKit].padGain[s] = kits[currentKit].padGain[t]
        kits[currentKit].padGain[t] = tempGain

        val tempDelayEnabled = kits[currentKit].padDelayEnabled[s]
        kits[currentKit].padDelayEnabled[s] = kits[currentKit].padDelayEnabled[t]
        kits[currentKit].padDelayEnabled[t] = tempDelayEnabled

        val sGroups = kits[currentKit].chokeGroups[s].toList()
        val tGroups = kits[currentKit].chokeGroups[t].toList()
        kits[currentKit].chokeGroups[s].clear()
        kits[currentKit].chokeGroups[s].addAll(tGroups)
        kits[currentKit].chokeGroups[t].clear()
        kits[currentKit].chokeGroups[t].addAll(sGroups)

        // ---------- Reload Native Engine ----------
        DrumEngine.invalidatePad(s)
        DrumEngine.invalidatePad(t)

        DrumEngine.loadPad(
            context,
            currentKit,
            s,
            kits[currentKit].sounds[s]
        )

        DrumEngine.loadPad(
            context,
            currentKit,
            t,
            kits[currentKit].sounds[t]
        )
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

    // BoxWithConstraints instead of Box: reading maxWidth lets the control
    // column (and every side panel that slides out from it) scale with the
    // actual screen instead of using one fixed dp width tuned for a single
    // reference device — small phones no longer lose most of their pad area
    // to an oversized panel, and tablets no longer get a control column that
    // looks stranded and tiny.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFD0D0D0)),
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
                            onDragStart = {
                                dragVisible = true
                                dragPad = 1
                                sourcePad = 1
                                dragX = pad1X
                                dragY = pad1Y
                            },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = {
                                targetPad = detectTargetPad()
                                dragVisible = false
                                if (targetPad != -1 && targetPad != sourcePad) {
                                    showPadMenu = true
                                }
                            },
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
                            onDragStart = {
                                dragVisible = true
                                dragPad = 2
                                sourcePad = 2
                                dragX = pad2X
                                dragY = pad2Y
                            },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = {
                                targetPad = detectTargetPad()
                                dragVisible = false
                                if (targetPad != -1 && targetPad != sourcePad) {
                                    showPadMenu = true
                                }
                            },
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
                            onDragStart = {
                                dragVisible = true
                                dragPad = 3
                                sourcePad = 3
                                dragX = pad3X
                                dragY = pad3Y
                            },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = {
                                targetPad = detectTargetPad()
                                dragVisible = false
                                if (targetPad != -1 && targetPad != sourcePad) {
                                    showPadMenu = true
                                }
                            },
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
                            onDragStart = {
                                dragVisible = true
                                dragPad = 4
                                sourcePad = 4
                                dragX = pad4X
                                dragY = pad4Y
                            },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = {
                                targetPad = detectTargetPad()
                                dragVisible = false
                                if (targetPad != -1 && targetPad != sourcePad) {
                                    showPadMenu = true
                                }
                            },
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
                            onDragStart = {
                                dragVisible = true
                                dragPad = 5
                                sourcePad = 5
                                dragX = pad5X
                                dragY = pad5Y
                            },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = {
                                targetPad = detectTargetPad()
                                dragVisible = false
                                if (targetPad != -1 && targetPad != sourcePad) {
                                    showPadMenu = true
                                }
                            },
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
                            onDragStart = {
                                dragVisible = true
                                dragPad = 6
                                sourcePad = 6
                                dragX = pad6X
                                dragY = pad6Y
                            },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = {
                                targetPad = detectTargetPad()
                                dragVisible = false
                                if (targetPad != -1 && targetPad != sourcePad) {
                                    showPadMenu = true
                                }
                            },
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
                            onDragStart = {
                                dragVisible = true
                                dragPad = 7
                                sourcePad = 7
                                dragX = pad7X
                                dragY = pad7Y
                            },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = {
                                targetPad = detectTargetPad()
                                dragVisible = false
                                if (targetPad != -1 && targetPad != sourcePad) {
                                    showPadMenu = true
                                }
                            },
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
                            onDragStart = {
                                dragVisible = true
                                dragPad = 8
                                sourcePad = 8
                                dragX = pad8X
                                dragY = pad8Y
                            },
                            onDragMove = { dx, dy ->
                                dragX += dx
                                dragY += dy
                            },
                            onDragEnd = {
                                targetPad = detectTargetPad()
                                dragVisible = false
                                if (targetPad != -1 && targetPad != sourcePad) {
                                    showPadMenu = true
                                }
                            },
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
                exclusiveMode = exclusiveMode,
                onLoopChange = {
                    loopEnabled = it

                    if (!it && currentStreamId != 0) {
                        soundPool.stop(currentStreamId)
                        currentStreamId = 0
                    }
                },
                bpm = bpm,
                metronomeOn = metronomeOn,
                totalBeats = totalBeats,
                onBpmChange = { bpm = it },
                onMetronomeToggle = { metronomeOn = it },
                onResetTotal = { totalBeats = 0 },
                onExclusiveChange = { exclusiveMode = it },
                velocityOn = velocityOn,
                onVelocityChange = { velocityOn = it },
                // NEW: pass ALL pads' choke-group membership (not just selected pad)
                // so the EQ panel can show a level-first picker.

                allPadChokeGroups = kits[currentKit].chokeGroups.map { it.toList() },
                onToggleChokeGroup = { padIndex, level -> toggleChokeGroup(padIndex, level) },
                // NEW: which level is currently active/live for this kit
                activeChokeLevel = kits[currentKit].activeChokeLevelState.value,
                onSelectActiveChokeLevel = { level -> setActiveChokeLevel(level) },


                delayEnabled  = curPadDelayEnabled,
                delayChokePad = delayChokePad,
                onDelayEnabledChange  = { setCurPadDelayEnabled(it) },
                onDelayChokePadChange = { delayChokePad = it },
                delayLevel = delayLevel,
                onDelayLevelChange = { delayLevel = it },
                speed = speed,
                onSpeedChange = { speed = it },
                masterLevel = if (currentKit in kits.indices) kits[currentKit].padLevels[selectedPad] else 1f,
                eqLow  = if (currentKit in kits.indices) kits[currentKit].padEqLow[selectedPad]  else 1f,
                eqMid  = if (currentKit in kits.indices) kits[currentKit].padEqMid[selectedPad]  else 1f,
                eqHigh = if (currentKit in kits.indices) kits[currentKit].padEqHigh[selectedPad] else 1f,
                onMasterLevelChange = { v ->
                    if (currentKit in kits.indices) {
                        kits[currentKit].padLevels[selectedPad] = v
                        NativeBridge.setMasterLevel(v)
                        persistKits()
                    }
                },
                onEqLowChange = { v ->
                    if (currentKit in kits.indices) {
                        kits[currentKit].padEqLow[selectedPad] = v
                        NativeBridge.setEqBands(v, kits[currentKit].padEqMid[selectedPad], kits[currentKit].padEqHigh[selectedPad])
                        persistKits()
                    }
                },
                onEqMidChange = { v ->
                    if (currentKit in kits.indices) {
                        kits[currentKit].padEqMid[selectedPad] = v
                        NativeBridge.setEqBands(kits[currentKit].padEqLow[selectedPad], v, kits[currentKit].padEqHigh[selectedPad])
                        persistKits()
                    }
                },
                onEqHighChange = { v ->
                    if (currentKit in kits.indices) {
                        kits[currentKit].padEqHigh[selectedPad] = v
                        NativeBridge.setEqBands(kits[currentKit].padEqLow[selectedPad], kits[currentKit].padEqMid[selectedPad], v)
                        persistKits()
                    }
                },
                delayTimeMs = if (currentKit in kits.indices)
                    kits[currentKit].padDelayMs[selectedPad] else 300,
                onDelayTimeChange = { ms ->
                    if (currentKit in kits.indices) {
                        kits[currentKit].padDelayMs[selectedPad] = ms
                        persistKits()   // volume/pitch ki tarah turant save
                    }
                },
                padLengthPct = if (currentKit in kits.indices) kits[currentKit].padLengthPct[selectedPad] else 1f,
                onPadLengthChange = { v ->
                    if (currentKit in kits.indices) {
                        kits[currentKit].padLengthPct[selectedPad] = v
                        persistKits()
                    }
                },
                padReverse = if (currentKit in kits.indices) kits[currentKit].padReverse[selectedPad] else false,
                onReverseChange = { v ->
                    if (currentKit in kits.indices) {
                        kits[currentKit].padReverse[selectedPad] = v
                        persistKits()
                    }
                },
                padPlayMode = if (currentKit in kits.indices) kits[currentKit].padPlayMode[selectedPad] else "ONESHOT",
                onPlayModeChange = { mode ->
                    if (currentKit in kits.indices) {
                        kits[currentKit].padPlayMode[selectedPad] = mode
                        persistKits()
                    }
                },
                // NEW: per-pad Pan/Gain FX knobs
                padPan = if (currentKit in kits.indices) kits[currentKit].padPan.getOrElse(selectedPad) { 0f } else 0f,
                onPanChange = { v ->
                    if (currentKit in kits.indices) {
                        kits[currentKit].padPan[selectedPad] = v
                        DrumEngine.setPan(selectedPad, v)
                        persistKits()
                    }
                },
                padGain = if (currentKit in kits.indices) kits[currentKit].padGain.getOrElse(selectedPad) { 1f } else 1f,
                onGainChange = { v ->
                    if (currentKit in kits.indices) {
                        kits[currentKit].padGain[selectedPad] = v
                        DrumEngine.setGain(selectedPad, v)
                        persistKits()
                    }
                },
                onSaveClick = { persistKits() },
                bankMode = bankMode,
                onBankModeToggle = { letter ->
                    bankMode = if (letter in bankMode) {
                        // Never allow removing the last active letter — a pad
                        // must always sound on at least one bank.
                        if (bankMode.length > 1) bankMode.replace(letter.toString(), "") else bankMode
                    } else {
                        bankMode + letter
                    }
                },
                kitBName = if (currentKitB in kits.indices) kits[currentKitB].name else "",
                onKitBPrev = { if (currentKitB > 0) currentKitB-- },
                onKitBNext = { if (currentKitB < kits.lastIndex) currentKitB++ },
                kitCName = if (currentKitC in kits.indices) kits[currentKitC].name else "",
                onKitCPrev = { if (currentKitC > 0) currentKitC-- },
                onKitCNext = { if (currentKitC < kits.lastIndex) currentKitC++ },
                selectedPad = selectedPad,
                padVolume = kits[currentKit].volumes[selectedPad],
                padPitch = kits[currentKit].pitches[selectedPad],


                onVolumeChange = {
                    kits[currentKit].volumes[selectedPad] = it
                    persistKits()   // NEW: save immediately so it survives app close
                },

                onPitchChange = {
                    kits[currentKit].pitches[selectedPad] = it
                    persistKits()   // NEW: save immediately so it survives app close
                },
                kits          = kits,
                currentKit    = currentKit,
                onKitAdd = {

                    // ── NEW: user khud jo naya kit add karega uske pads
                    // khaali (-1 = no sound) rahenge, default pad1-8 sound nahi bharega ──
                    if (kits.size < 200) {

                        kits.add(
                            Kit(
                                generateNextKitName(),
                                sounds = mutableStateListOf(
                                    -1, -1, -1, -1, -1, -1, -1, -1
                                ),
                                factoryKitNumber = -1
                            )
                        )

                        currentKit = kits.lastIndex
                        persistKits()   // NEW
                    }
                },
                onKitDelete   = { deleteKit(currentKit) },
                onKitPrev     = { if (currentKit > 0) currentKit-- },
                onKitNext     = { if (currentKit < kits.lastIndex) currentKit++ },
                onOpenKitList = { showKitList = true },
                // ── NEW callbacks ──────────────────────────────────────────────
                onOpenImport = { importTargetPad = null; topPanel = "IMPORT" },
                onOpenAudios = { topPanel = "AUDIOS" },
                onOpenExport = { topPanel = "EXPORT" },
                onOpenEdit   = { topPanel = "EDIT" },
                onImportToPad = { importTargetPad = selectedPad; topPanel = "IMPORT" },
                onOpenMapMidi = { topPanel = "MIDI_LEARN" },
                onOpenLoadKit = { topPanel = "LOAD_KIT" },
                onOpenBackup = { topPanel = "BACKUP" },
                onOpenImportPatch = { topPanel = "IMPORT_PATCH" },

                onRenameKit = {
                    newKitName = kits[currentKit].name
                    showRenameDialog = true
                },
                // ── NEW: live waveform/timer ────────────────────────────────────
                playingPadUri       = playingPadUri,
                playingDefaultResId = if (playingPadUri == null)
                    kits[currentKit].sounds[selectedPad].takeIf { it > 0 } ?: 0
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
        if (showKitList) {
            KitListScreen(
                kits       = kits,
                currentKit = currentKit,
                onSelect   = { index ->
                    currentKit  = index
                    showKitList = false
                },

                onAdd = {

                    // ── NEW: yaha se add kiya gaya kit bhi khaali (-1) pads ke saath banega ──
                    if (kits.size < 200) {

                        kits.add(
                            Kit(
                                generateNextKitName(),
                                sounds = mutableStateListOf(
                                    -1, -1, -1, -1, -1, -1, -1, -1
                                ),
                                factoryKitNumber = -1
                            )
                        )

                        currentKit = kits.lastIndex
                        persistKits()   // NEW
                    }
                },


                onDelete   = { index -> deleteKit(index) },
                onCopy     = { index -> copyKit(index) },
                onRename   = { index ->
                    // Direct/inline patch editing: rename right from the
                    // Patch List instead of needing Settings → Rename Kit.
                    currentKit = index
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
                    androidx.compose.material3.Text("Rename Kit")
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
                    TextButton(
                        onClick = {

                            if (newKitName.isNotBlank()) {

                                val alreadyExists = kits.anyIndexed { index, kit ->
                                    index != currentKit &&
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

                                    return@TextButton
                                }

                                kits[currentKit] = kits[currentKit].copy(
                                    name = newKitName.trim()
                                )
                                persistKits()
                            }

                            showRenameDialog = false
                        }
                    ) {
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

            "IMPORT" -> {
                ImportScreen(
                    onClose = { topPanel = ""; importTargetPad = null },
                    currentKit = currentKit,
                    targetPad = importTargetPad,
                    targetPadDefaultResId = importTargetPad?.let {
                        kits[currentKit].sounds.getOrElse(it) { -1 }
                    } ?: -1
                )
            }

            "AUDIOS" -> {
                AudioListScreen(
                    currentKit = currentKit,
                    factoryResIds = kits[currentKit].sounds,
                    onClose = { topPanel = "" }
                )
            }

            "EXPORT" -> {
                ExportScreen(
                    onClose = { topPanel = "" }
                )
            }

            "EDIT" -> {
                WaveformEditorScreen(
                    kitIndex = currentKit,
                    padIndex = selectedPad,
                    factoryResId = kits[currentKit].sounds.getOrElse(selectedPad) { -1 },
                    onClose  = { topPanel = "" },
                    // Same one-tap import flow EQPanel's "IMPORT TO THIS PAD"
                    // already uses — no import logic duplicated.
                    onPickNewSound = { importTargetPad = selectedPad; topPanel = "IMPORT" }
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
                        kits.add(newKit)
                        val newKitIndex = kits.lastIndex
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
                        topPanel = ""
                    }
                )
            }

            "LOAD_KIT" -> {
                LoadKitScreen(
                    currentKitCount = kits.size,
                    onKitLoaded = { name, files ->
                        // Create new empty kit
                        val newKit = Kit(
                            name   = name,
                            sounds = mutableStateListOf(-1,-1,-1,-1,-1,-1,-1,-1),
                            factoryKitNumber = -1
                        )
                        kits.add(newKit)
                        val newKitIndex = kits.lastIndex
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
    }

    PadActionMenu(
        visible = showPadMenu,
        onMix = {
            showPadMenu = false
            // sourcePad/targetPad are 1-based pad numbers (1..8) from the drag
            // gesture — AudioRepository/PcmMixer index pads 0-based, so convert.
            val s = sourcePad - 1
            val t = targetPad - 1
            if (s in 0..7 && t in 0..7 && s != t) {
                scope.launch {
                    val file = com.example.myapplication.ui.audio.PcmMixer.mixPads(
                        context  = context,
                        kitIndex = currentKit,
                        padA     = s,
                        padB     = t,
                        factoryResIds = kits[currentKit].sounds
                    )
                    if (file != null) {
                        AudioRepository.assignRecordedAudio(
                            kitIndex = currentKit,
                            padIndex = t,
                            file     = file
                        )
                        DrumEngine.invalidatePad(t)
                        if (selectedPad == t) updatePadDisplay(t)
                    } else {
                        android.util.Log.w("MIX", "mixPads returned null for pads $s,$t in kit $currentKit")
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
                scope.launch {
                    val file = com.example.myapplication.ui.audio.PcmMixer.concatPads(
                        context  = context,
                        kitIndex = currentKit,
                        padA     = s,
                        padB     = t,
                        factoryResIds = kits[currentKit].sounds
                    )
                    if (file != null) {
                        AudioRepository.assignRecordedAudio(
                            kitIndex = currentKit,
                            padIndex = t,
                            file     = file
                        )
                        DrumEngine.invalidatePad(t)
                        if (selectedPad == t) updatePadDisplay(t)
                    } else {
                        android.util.Log.w("MIX", "concatPads returned null for pads $s,$t in kit $currentKit")
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
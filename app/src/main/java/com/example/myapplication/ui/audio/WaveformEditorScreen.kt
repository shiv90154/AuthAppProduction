package com.example.myapplication.ui.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.BtnActive
import com.example.myapplication.ui.PanelBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * WaveformEditorScreen
 *
 * Features:
 *  - Full waveform display of the assigned pad audio
 *  - One unified gesture recognizer on the canvas (see WaveformEditorCanvas):
 *    a single finger either grabs an existing start/end edge precisely (if
 *    the touch lands close to that edge's line) or drags out a brand new
 *    region from scratch; two fingers landing on the two existing edges at
 *    once move both independently in the same gesture (drag either forward
 *    or back), otherwise two-or-more fingers pinch to zoom / pan. None of
 *    these ever overlap or fight over the same touch — that was the root
 *    cause of the old "unnatural"/laggy feel.
 *  - Crop: drag draws/adjusts the kept region directly
 *  - Delete region: tap "DEL REGION", then drag to select a middle section
 *    to cut
 *  - Millisecond-precision display
 *  - PREVIEW: plays back just the current (unsaved) selection so you can
 *    hear it before it commits
 *  - No APPLY/SAVE button: an edit auto-commits ~1s after the selection
 *    stops changing (debounced, so it doesn't write mid-drag). RESET
 *    reverts the on-screen selection back to the full clip before that
 *    commit fires.
 *
 * @param kitIndex    current active kit
 * @param padIndex    which pad (0-7) is being edited
 * @param onClose     dismiss
 */
@Composable
fun WaveformEditorScreen(
    kitIndex: Int,
    padIndex: Int,
    factoryResId: Int = -1,
    onClose: () -> Unit,
    // NEW: lets the user pick a brand-new sound for this pad without
    // leaving Crop mode — reuses the exact same one-tap file-manager
    // import flow the FX panel's "IMPORT TO THIS PAD" already uses
    // (OctapadScreen sets importTargetPad + opens the Import screen).
    onPickNewSound: () -> Unit = {},
    // NEW: wipes whatever sound (custom or factory) this pad has, leaving
    // it silent — same "add + clear sound, both right here in Crop" flow
    // requested instead of having to dig through other screens to unassign.
    onClearSound: () -> Unit = {}
) {
    val context = LocalContext.current

    val audio = remember { AudioRepository.audioForPad(kitIndex, padIndex) }
    // Display name to save the edited file under when there's no custom
    // AudioItem yet (factory-kit sample being edited for the first time).
    val sourceName = audio?.name ?: "Pad${padIndex + 1}"

    // ── PCM state ─────────────────────────────────────────────────────────────
    var pcmResult by remember { mutableStateOf<PcmResult?>(null) }
    var amplitudes by remember { mutableStateOf<FloatArray>(FloatArray(0)) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Duration in ms derived from PCM
    val durationMs by remember(pcmResult) {
        derivedStateOf {
            val p = pcmResult ?: return@derivedStateOf audio?.durationMs ?: 0L
            (p.pcm.size.toLong() / p.channels * 1000L / p.sampleRate)
        }
    }

    // ── View state ────────────────────────────────────────────────────────────
    // zoom: 1f = full clip visible; 4f = 4x zoom
    var zoom by remember { mutableStateOf(1f) }
    // scrollOffset: fraction 0..1 of how far we are into the clip (left edge)
    var scrollFrac by remember { mutableStateOf(0f) }

    // Crop handles (ms values)
    var cropStartMs by remember { mutableStateOf(0L) }
    var cropEndMs by remember { mutableStateOf(0L) }

    // Delete region (ms values), -1 = not set
    var deleteStartMs by remember { mutableStateOf(-1L) }
    var deleteEndMs by remember { mutableStateOf(-1L) }

    // Edit mode: "CROP" = trim edges, "DELETE" = select region to cut
    var editMode by remember { mutableStateOf("CROP") }

    // Saving state
    var isSaving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    // Set true by any user-driven crop/delete change so the APPLY button
    // knows there's an unsaved selection — the initial PCM load also writes
    // cropStartMs/cropEndMs (full range) and must NOT count as an edit.
    var hasUserEdited by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var previewTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var isPreviewing by remember { mutableStateOf(false) }

    // Stop any in-flight preview the instant this screen goes away, so a
    // closed editor never leaves an AudioTrack playing/leaking in the
    // background.
    DisposableEffect(Unit) {
        onDispose { previewTrack?.let { it.stop(); it.release() } }
    }

    // ── Load PCM on entry ──────────────────────────────────────────────────────
    // BUG FIX: this used to hard-block editing entirely whenever the pad had
    // no custom AudioItem assigned — i.e. every factory-kit pad, which is
    // most pads in most kits. That's exactly the "tone cut / crop doesn't
    // work" complaint: the most common thing someone would try (trim a
    // factory kit's built-in sample) hit a dead end immediately. Now falls
    // back to decoding the kit's factory raw resource directly, same as
    // PcmMixer does for Mix/Add-To-End — only truly empty pads (no custom
    // audio AND no factory resource) are actually uneditable.
    LaunchedEffect(audio, factoryResId) {
        if (audio == null && factoryResId <= 0) {
            isLoading = false
            errorMsg = "PAD ${padIndex + 1} has no sound assigned yet.\n\nImport, record, or pick a kit with a sound on this pad first."
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            try {
                val result = if (audio != null) {
                    PcmDecoder.decode(context, audio.uri)
                } else {
                    PcmDecoder.decodeRawResource(context, factoryResId)
                }
                if (result != null) {
                    pcmResult = result
                    amplitudes = computeAmplitudes(result, bars = 512)
                    cropStartMs = 0L
                    cropEndMs = (result.pcm.size.toLong() / result.channels * 1000L / result.sampleRate)
                } else {
                    errorMsg = "Failed to decode this pad's audio."
                }
            } catch (e: Exception) {
                errorMsg = "Failed to load audio: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // ── Apply crop/delete edits ─────────────────────────────────────────────
    // Auto-committed ~1s after the selection stops changing (see the
    // LaunchedEffect debounce below) — there is no APPLY/SAVE button.
    // Dragging the waveform (onCropRegion/onDeleteRegion below) only
    // updates local UI state immediately; the actual commit is debounced so
    // a single in-progress drag doesn't write to disk on every frame, and
    // RESET (still present) can undo back to the original before the
    // debounce fires.
    suspend fun applyPending() {
        val result = pcmResult ?: return
        saveMsg = null
        isSaving = true
        try {
            val editedFile = applyEdits(
                context = context,
                pcm = result,
                durationMs = durationMs,
                cropStartMs = cropStartMs,
                cropEndMs = cropEndMs,
                deleteStartMs = if (deleteStartMs >= 0 && deleteEndMs > deleteStartMs) deleteStartMs else -1L,
                deleteEndMs = if (deleteStartMs >= 0 && deleteEndMs > deleteStartMs) deleteEndMs else -1L,
                originalName = sourceName
            )
            AudioRepository.assignRecordedAudio(
                kitIndex = kitIndex,
                padIndex = padIndex,
                file = editedFile
            )
            // BUG FIX: every other caller of assignRecordedAudio (Mix,
            // Add-To-End, mic recording) invalidates the native pad buffer
            // right after — this call site was the one exception, so the
            // edited/cropped audio was saved to the repository but the pad
            // kept playing the OLD pre-edit sound until something unrelated
            // (a kit switch, an app restart) forced a reload.
            DrumEngine.invalidatePad(padIndex)
            DrumEngine.loadPad(context, kitIndex, padIndex, factoryResId)
            saveMsg = "Saved"
            hasUserEdited = false
            // Refresh pcm + amplitudes for the newly saved audio, and reset
            // the crop/delete range to match its (now full) extent.
            val newPcm = PcmDecoder.decode(context, Uri.fromFile(editedFile))
            if (newPcm != null) {
                pcmResult = newPcm
                amplitudes = computeAmplitudes(newPcm, bars = 512)
                cropStartMs = 0L
                cropEndMs = (newPcm.pcm.size.toLong() / newPcm.channels * 1000L / newPcm.sampleRate)
                deleteStartMs = -1L
                deleteEndMs = -1L
                zoom = 1f
                scrollFrac = 0f
            }
        } catch (e: Exception) {
            saveMsg = "Error: ${e.message}"
        } finally {
            isSaving = false
        }
    }

    // Debounced auto-apply: restarts on every crop/delete-region change, so
    // an in-progress drag never writes mid-gesture — only once the
    // selection has been still for 1s does the pending edit actually
    // commit. Keyed off hasUserEdited too so RESET (which sets it back to
    // false) cancels any pending commit outright instead of racing it.
    LaunchedEffect(cropStartMs, cropEndMs, deleteStartMs, deleteEndMs, hasUserEdited) {
        if (hasUserEdited) {
            delay(1000L)
            applyPending()
        }
    }

    // ── Preview the current (unsaved) selection ─────────────────────────────
    // Plays the crop/delete selection directly from the in-memory PCM via
    // AudioTrack — no file write, no encode — so the user can hear exactly
    // what APPLY would commit before actually committing it.
    fun previewSelection() {
        val result = pcmResult ?: return
        previewTrack?.let { it.stop(); it.release() }
        previewTrack = null
        val samples = stitchEditedPcm(
            pcm = result,
            cropStartMs = cropStartMs,
            cropEndMs = cropEndMs,
            deleteStartMs = if (deleteStartMs >= 0 && deleteEndMs > deleteStartMs) deleteStartMs else -1L,
            deleteEndMs = if (deleteStartMs >= 0 && deleteEndMs > deleteStartMs) deleteEndMs else -1L
        )
        if (samples.isEmpty()) return

        val channelConfig = if (result.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBufSize = AudioTrack.getMinBufferSize(result.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(result.sampleRate)
                .setChannelMask(channelConfig)
                .build(),
            maxOf(minBufSize, samples.size * 2),
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.write(samples, 0, samples.size)
        track.play()
        previewTrack = track
        isPreviewing = true

        val durationSec = samples.size.toFloat() / result.channels / result.sampleRate
        scope.launch {
            delay((durationSec * 1000).toLong() + 100L)
            if (previewTrack === track) {
                track.stop()
                track.release()
                previewTrack = null
                isPreviewing = false
            }
        }
    }

    // ── Full-screen overlay ────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD000000))
            .pointerInput(Unit) { detectTapGestures { /* consume backdrop */ } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {

            // ── Header ─────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "EDIT — PAD ${padIndex + 1}",
                        color = BtnActive, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                    )
                    if (audio != null) {
                        Text(
                            audio.name,
                            color = Color(0xFF888888), fontSize = 9.sp
                        )
                    }
                }
                if (durationMs > 0) {
                    Text(
                        "${cropStartMs.toEditorTimeStr()} – ${cropEndMs.toEditorTimeStr()}",
                        color = Color(0xFFFFB74D), fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A))
                        .pointerInput(Unit) { detectTapGestures { onClose() } },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Pick new sound / Clear sound — always available, even before
            // any audio has loaded, so a pad with nothing assigned isn't a
            // dead end anymore, and clearing doesn't need a detour to
            // another screen either.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(2f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A1A1A))
                        .pointerInput(Unit) { detectTapGestures { onPickNewSound() } }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("PICK NEW SOUND", color = BtnActive, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A1010))
                        .pointerInput(Unit) { detectTapGestures { onClearSound() } }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("CLEAR", color = Color(0xFFFF6666), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                isLoading -> {
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Loading waveform…", color = Color(0xFF888888), fontSize = 12.sp)
                    }
                }

                errorMsg != null -> {
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(errorMsg!!, color = Color(0xFFFF6666), fontSize = 12.sp,
                            textAlign = TextAlign.Center)
                    }
                }

                amplitudes.isNotEmpty() -> {
                    // ── Mode selector ───────────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ModeBtn("CROP", editMode == "CROP", Modifier.weight(1f)) {
                            editMode = "CROP"
                            deleteStartMs = -1L; deleteEndMs = -1L
                        }
                        ModeBtn("DEL REGION", editMode == "DELETE", Modifier.weight(1f)) {
                            editMode = "DELETE"
                        }
                        // Zoom controls
                        EditorBtn("-", Modifier.size(36.dp)) {
                            zoom = (zoom / 1.5f).coerceAtLeast(1f)
                            if (zoom == 1f) scrollFrac = 0f
                        }
                        EditorBtn("+", Modifier.size(36.dp)) {
                            zoom = (zoom * 1.5f).coerceAtMost(32f)
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // ── Waveform canvas ─────────────────────────────────────────
                    WaveformEditorCanvas(
                        amplitudes = amplitudes,
                        durationMs = durationMs,
                        zoom = zoom,
                        scrollFrac = scrollFrac,
                        cropStartMs = cropStartMs,
                        cropEndMs = cropEndMs,
                        deleteStartMs = deleteStartMs,
                        deleteEndMs = deleteEndMs,
                        editMode = editMode,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0A0A0A)),
                        // Same drag-to-select interaction as onDeleteRegion below —
                        // every drag fully replaces both edges in one motion
                        // instead of nudging whichever handle was pixel-closer,
                        // so there's no stale edge left over from a prior edit.
                        onCropRegion = { s, e ->
                            val lo = s.coerceIn(0L, durationMs)
                            val hi = e.coerceAtLeast(lo + 10L).coerceAtMost(durationMs)
                            cropStartMs = lo
                            cropEndMs = hi
                            hasUserEdited = true
                        },
                        onDeleteRegion = { s, e ->
                            deleteStartMs = s.coerceIn(cropStartMs, cropEndMs)
                            deleteEndMs = e.coerceIn(cropStartMs, cropEndMs)
                            hasUserEdited = true
                        },
                        // Region-drag gestures no longer autosave anything —
                        // they only ever update the in-memory selection above
                        // (onCropRegion/onDeleteRegion). Nothing is written to
                        // disk until APPLY is tapped explicitly.
                        onRegionCommit = {},
                        onZoomScroll = { scale, offsetFrac ->
                            zoom = (zoom * scale).coerceIn(1f, 32f)
                            scrollFrac = offsetFrac.coerceIn(0f, 1f - 1f / zoom)
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    // ── Info row ────────────────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Start: ${cropStartMs.toEditorTimeStr()}",
                            color = Color(0xFF00E5FF), fontSize = 9.sp
                        )
                        if (deleteStartMs >= 0 && deleteEndMs > deleteStartMs) {
                            Text(
                                "Del: ${deleteStartMs.toEditorTimeStr()} – ${deleteEndMs.toEditorTimeStr()}",
                                color = Color(0xFFFF4444), fontSize = 9.sp
                            )
                        } else {
                            Text(
                                "Duration: ${(cropEndMs - cropStartMs).toEditorTimeStr()}",
                                color = Color(0xFF888888), fontSize = 9.sp
                            )
                        }
                        Text(
                            "End: ${cropEndMs.toEditorTimeStr()}",
                            color = Color(0xFF00E5FF), fontSize = 9.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Action buttons ──────────────────────────────────────────
                    // PREVIEW listens to the current unsaved selection.
                    // RESET clears the on-screen selection back to the full
                    // clip and cancels any pending auto-apply (see the
                    // debounce LaunchedEffect above) — there is no
                    // APPLY/SAVE button; edits commit automatically ~1s
                    // after the selection stops changing.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1A1A1A))
                                .pointerInput(Unit) {
                                    detectTapGestures { previewSelection() }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (isPreviewing) "▶ PLAYING…" else "▶ PREVIEW",
                                color = Color(0xFF00E5FF), fontSize = 11.sp,
                                fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1A1A1A))
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        cropStartMs = 0L
                                        cropEndMs = durationMs
                                        deleteStartMs = -1L
                                        deleteEndMs = -1L
                                        zoom = 1f
                                        scrollFrac = 0f
                                        saveMsg = null
                                        hasUserEdited = false
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("RESET", color = Color(0xFF888888), fontSize = 11.sp,
                                fontWeight = FontWeight.Bold)
                        }
                    }

                    if (isSaving || (hasUserEdited && !isSaving) || saveMsg != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                isSaving -> "Saving…"
                                hasUserEdited -> "Adjusting…"
                                else -> saveMsg!!
                            },
                            color = if (!isSaving && saveMsg?.startsWith("Error") == true) Color(0xFFFF6666)
                                    else Color(0xFF66FFAA),
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> {
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No waveform data", color = Color(0xFF555555), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ── Waveform Canvas ───────────────────────────────────────────────────────────

// Which multi-touch interaction the canvas's single gesture recognizer has
// committed to for the gesture currently in progress. Owning both
// interactions in one recognizer (see below) is what guarantees REGION and
// TRANSFORM can never both react to the same touch stream at once.
// HANDLE_START/HANDLE_END = single-finger drag that grabbed an existing
// edge precisely instead of redefining the whole region. DUAL_HANDLE = two
// fingers landing on the start and end edges at once, each finger moving
// its own edge independently (forward or back) in the same gesture.
private enum class CanvasGesture { NONE, REGION, TRANSFORM, HANDLE_START, HANDLE_END, DUAL_HANDLE }

// How close (in px) a touch-down needs to land next to an existing crop/
// delete edge line to grab that edge precisely instead of starting a brand
// new region selection.
private const val kHandleGrabPx = 56f

@Composable
private fun WaveformEditorCanvas(
    amplitudes: FloatArray,
    durationMs: Long,
    zoom: Float,
    scrollFrac: Float,
    cropStartMs: Long,
    cropEndMs: Long,
    deleteStartMs: Long,
    deleteEndMs: Long,
    editMode: String,
    modifier: Modifier,
    onCropRegion: (Long, Long) -> Unit,
    onDeleteRegion: (Long, Long) -> Unit,
    onRegionCommit: () -> Unit,
    onZoomScroll: (Float, Float) -> Unit
) {
    // Track canvas width in px for coordinate math. Written only from layout
    // (onGloballyPositioned) — a second, redundant write of the same value
    // used to also happen on every single draw frame inside the Canvas draw
    // lambda; removed, since layout size doesn't change per frame.
    var canvasWidthPx by remember { mutableStateOf(1f) }

    // ROOT CAUSE of the reported "unnatural"/laggy crop feel and broken
    // two-finger zoom: this canvas used to run TWO separate `pointerInput`
    // gesture detectors stacked on the same node — one for pinch/pan
    // (detectTransformGestures) and a second, independent one for the
    // region drag — and BOTH received and reacted to the exact same raw
    // touch stream at the same time. detectTransformGestures fires its
    // callback for any pointer count, including exactly one finger (zoom
    // stays 1, but pan is nonzero), so a plain single-finger crop/delete
    // drag was ALSO being read as a pan/scroll simultaneously — and the
    // reverse was just as broken: during a genuine two-finger pinch, the
    // region detector's "first pointer" tracking kept moving the crop/
    // delete boundary too. Two independent recognizers fighting over one
    // touch stream explains every symptom: janky pinch/pan, a boundary
    // that visibly fought the finger, and a final crop that didn't match
    // what was on screen because the boundary got corrupted mid-pinch.
    //
    // Fix: exactly one recognizer below. It decides per-gesture, from the
    // live pointer count, whether this is a region drag or a pinch/pan,
    // and once it commits to one it never evaluates the other against the
    // same events.
    val liveDurationMs by rememberUpdatedState(durationMs)
    val liveZoom by rememberUpdatedState(zoom)
    val liveScrollFrac by rememberUpdatedState(scrollFrac)
    // Handle-grab hit-testing (currentEdgesMs, below) reads these from
    // inside a long-lived awaitEachGesture loop that only restarts when
    // editMode changes — without rememberUpdatedState these would be
    // frozen at whichever values were current the last time the gesture
    // detector itself was rebuilt, silently testing grab distance against
    // stale edge positions.
    val liveCropStartMs by rememberUpdatedState(cropStartMs)
    val liveCropEndMs by rememberUpdatedState(cropEndMs)
    val liveDeleteStartMs by rememberUpdatedState(deleteStartMs)
    val liveDeleteEndMs by rememberUpdatedState(deleteEndMs)

    // In-flight inertia from a released pan, if any. A fresh touch always
    // cancels it immediately — standard scroll-view behaviour: touching the
    // content stops any residual momentum dead.
    val flingScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }

    Canvas(
        modifier = modifier
            .onGloballyPositioned { canvasWidthPx = it.size.width.toFloat() }
            // Single recognizer for BOTH region-select (crop/delete) and
            // pinch-zoom/pan — see the ROOT CAUSE note above for why this
            // must never be two separate detectors. Restarts only when
            // editMode changes, not on every value the gesture itself
            // writes back (cropStartMs, zoom, ...), which would tear down
            // and restart the detector mid-drag.
            .pointerInput(editMode) {
                awaitEachGesture {
                    flingJob?.cancel()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var gesture = CanvasGesture.NONE
                    val dragStartPx = down.position.x
                    var regionChanged = false
                    var lastEventTimeMs = down.uptimeMillis
                    var scrollVelocityFracPerMs = 0f
                    var startPointerId: androidx.compose.ui.input.pointer.PointerId? = null
                    var endPointerId: androidx.compose.ui.input.pointer.PointerId? = null

                    // Current edges relevant to the active editMode, used to
                    // decide (once, at gesture start) whether a touch grabs
                    // an existing edge precisely rather than redefining the
                    // whole region.
                    fun currentEdgesMs(): Pair<Long, Long>? = if (editMode == "CROP") {
                        liveCropStartMs to liveCropEndMs
                    } else if (liveDeleteStartMs >= 0 && liveDeleteEndMs > liveDeleteStartMs) {
                        liveDeleteStartMs to liveDeleteEndMs
                    } else null

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2 && gesture == CanvasGesture.NONE) {
                            // First moment two fingers are both down for
                            // this gesture — decide once whether they
                            // landed on the two existing edges (dual-handle
                            // precise adjust) or this is an ordinary pinch/
                            // pan. Sorted by x so it doesn't matter which
                            // finger touched down first.
                            val edges = currentEdgesMs()
                            val sorted = pressed.sortedBy { it.position.x }
                            val p0 = sorted[0]
                            val p1 = sorted[1]
                            if (edges != null) {
                                val startPx = msToCanvasPx(edges.first, liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx)
                                val endPx = msToCanvasPx(edges.second, liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx)
                                if (abs(p0.position.x - startPx) <= kHandleGrabPx &&
                                    abs(p1.position.x - endPx) <= kHandleGrabPx
                                ) {
                                    gesture = CanvasGesture.DUAL_HANDLE
                                    startPointerId = p0.id
                                    endPointerId = p1.id
                                }
                            }
                            if (gesture != CanvasGesture.DUAL_HANDLE) gesture = CanvasGesture.TRANSFORM
                        }

                        if (gesture == CanvasGesture.DUAL_HANDLE) {
                            // Each finger moves its own edge independently,
                            // forward or back — the other edge is untouched
                            // by this finger's motion.
                            val startChange = event.changes.firstOrNull { it.id == startPointerId }
                            val endChange = event.changes.firstOrNull { it.id == endPointerId }
                            if (startChange != null && startChange.pressed && endChange != null && endChange.pressed) {
                                val rawStart = canvasPxToMs(startChange.position.x.coerceIn(0f, canvasWidthPx), liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx)
                                val rawEnd = canvasPxToMs(endChange.position.x.coerceIn(0f, canvasWidthPx), liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx)
                                val newStart = rawStart.coerceIn(0L, rawEnd - 10L)
                                val newEnd = rawEnd.coerceAtLeast(newStart + 10L)
                                if (editMode == "CROP") onCropRegion(newStart, newEnd) else onDeleteRegion(newStart, newEnd)
                                regionChanged = true
                                startChange.consume()
                                endChange.consume()
                            }
                        } else if (pressed.size >= 2) {
                            // Two-plus fingers: pinch-zoom + pan. Owns the
                            // gesture exclusively from here — even if this
                            // started as a single-finger region drag a
                            // moment ago, that partial edit simply isn't
                            // committed (see the REGION/regionChanged check
                            // after the loop: `gesture` is TRANSFORM now).
                            gesture = CanvasGesture.TRANSFORM
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val z = liveZoom
                            val newZoom = (z * zoomChange).coerceIn(1f, 32f)
                            val centroid = event.calculateCentroid()
                            // Keep the pinch centroid anchored to the same
                            // waveform position as the zoom level changes.
                            val centroidFrac = pxToFrac(centroid.x, z, liveScrollFrac, canvasWidthPx)
                            val zoomedScroll = centroidFrac - pxToFrac(centroid.x, newZoom, 0f, canvasWidthPx)
                            // Content follows the fingers: dragging right
                            // reveals earlier content (scrollFrac decreases),
                            // same convention as scrolling any list by touch.
                            val panFrac = panChange.x / canvasWidthPx / newZoom
                            val newScroll = (zoomedScroll - panFrac)
                                .coerceIn(0f, (1f - 1f / newZoom).coerceAtLeast(0f))

                            val now = event.changes[0].uptimeMillis
                            val dt = (now - lastEventTimeMs).coerceAtLeast(1L)
                            // Zero out (not just "leave stale") on a still
                            // frame, so a deliberate pause-then-release
                            // never produces a phantom fling.
                            scrollVelocityFracPerMs = if (zoomChange == 1f && panChange == Offset.Zero) {
                                0f
                            } else {
                                (newScroll - liveScrollFrac) / dt
                            }
                            lastEventTimeMs = now

                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                onZoomScroll(newZoom / z, newScroll)
                            }
                            event.changes.forEach { it.consume() }
                        } else if (gesture != CanvasGesture.TRANSFORM && gesture != CanvasGesture.DUAL_HANDLE) {
                            // Exactly one finger, and this gesture hasn't
                            // become a pinch or a dual-handle adjust. If it
                            // landed within grab range of the existing
                            // start or end edge, drag ONLY that edge —
                            // precise forward/back nudging of one boundary
                            // instead of redefining the whole region from
                            // scratch, which is what made hitting an exact
                            // cut point hard. Otherwise (starting away from
                            // both edges) falls back to the original
                            // behavior: the drag fully redefines both edges
                            // (touch point to current point) — needed to
                            // make a brand new selection from nothing.
                            if (gesture == CanvasGesture.NONE) {
                                val edges = currentEdgesMs()
                                gesture = if (edges != null) {
                                    val startPx = msToCanvasPx(edges.first, liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx)
                                    val endPx = msToCanvasPx(edges.second, liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx)
                                    when {
                                        abs(dragStartPx - startPx) <= kHandleGrabPx -> CanvasGesture.HANDLE_START
                                        abs(dragStartPx - endPx) <= kHandleGrabPx -> CanvasGesture.HANDLE_END
                                        else -> CanvasGesture.REGION
                                    }
                                } else {
                                    CanvasGesture.REGION
                                }
                            }
                            val change = pressed[0]
                            val curMs = canvasPxToMs(change.position.x.coerceIn(0f, canvasWidthPx), liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx)
                            when (gesture) {
                                CanvasGesture.HANDLE_START -> {
                                    val edges = currentEdgesMs()
                                    val hi = edges?.second ?: liveDurationMs
                                    val lo = curMs.coerceIn(0L, hi - 10L)
                                    if (editMode == "CROP") onCropRegion(lo, hi) else onDeleteRegion(lo, hi)
                                }
                                CanvasGesture.HANDLE_END -> {
                                    val edges = currentEdgesMs()
                                    val lo = edges?.first ?: 0L
                                    val hi = curMs.coerceAtLeast(lo + 10L).coerceAtMost(liveDurationMs)
                                    if (editMode == "CROP") onCropRegion(lo, hi) else onDeleteRegion(lo, hi)
                                }
                                else -> {
                                    val s = canvasPxToMs(dragStartPx.coerceIn(0f, canvasWidthPx), liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx)
                                    val lo = minOf(s, curMs)
                                    val hi = maxOf(s, curMs)
                                    if (editMode == "CROP") onCropRegion(lo, hi) else onDeleteRegion(lo, hi)
                                }
                            }
                            regionChanged = true
                            change.consume()
                        }
                        // gesture == TRANSFORM but exactly 1 finger remains
                        // (the other just lifted): deliberately falls
                        // through and does nothing until the last finger
                        // also lifts — NOT reinterpreted as a fresh region
                        // drag, which would jump the boundary to wherever
                        // that finger happens to be.
                    }

                    when (gesture) {
                        CanvasGesture.REGION,
                        CanvasGesture.HANDLE_START,
                        CanvasGesture.HANDLE_END,
                        CanvasGesture.DUAL_HANDLE -> if (regionChanged) onRegionCommit()
                        CanvasGesture.TRANSFORM -> {
                            // Natural inertia: a fast pan release keeps
                            // gliding and decelerates instead of stopping
                            // dead, matching native scroll/zoom feel.
                            // Capped, clamped to valid scroll bounds, and
                            // killed instantly by the next touch.
                            val v0 = (scrollVelocityFracPerMs * 1000f).coerceIn(-4f, 4f)
                            val z = liveZoom
                            val maxScroll = (1f - 1f / z).coerceAtLeast(0f)
                            if (abs(v0) > 0.02f && maxScroll > 0f) {
                                flingJob = flingScope.launch {
                                    var v = v0
                                    var pos = liveScrollFrac
                                    var lastFrame = 0L
                                    while (abs(v) > 0.01f && pos in 0f..maxScroll) {
                                        withFrameNanos { t ->
                                            if (lastFrame != 0L) {
                                                val dt = (t - lastFrame) / 1_000_000_000f
                                                pos = (pos + v * dt).coerceIn(0f, maxScroll)
                                                v *= (1f - 4f * dt).coerceIn(0f, 1f)
                                                onZoomScroll(1f, pos)
                                            }
                                            lastFrame = t
                                        }
                                    }
                                }
                            }
                        }
                        CanvasGesture.NONE -> Unit
                    }
                }
            }
    ) {
        val midY = size.height / 2f
        val barCount = amplitudes.size

        // Visible window fraction
        val visibleFrac = 1f / zoom
        val startFrac   = scrollFrac
        val endFrac     = (scrollFrac + visibleFrac).coerceAtMost(1f)

        val startBar = (startFrac * barCount).toInt().coerceIn(0, barCount - 1)
        val endBar   = (endFrac   * barCount).toInt().coerceIn(0, barCount)

        val visibleBars = (endBar - startBar).coerceAtLeast(1)
        val barWidth    = size.width / visibleBars

        // Draw waveform bars
        for (i in startBar until endBar) {
            val amp = amplitudes[i]
            val barH = (amp * size.height * 0.85f).coerceAtLeast(1.5f)
            val x    = (i - startBar) * barWidth

            // Determine bar color
            val barMs = (i.toFloat() / barCount * durationMs).toLong()
            val color = when {
                deleteStartMs >= 0 && barMs in deleteStartMs..deleteEndMs -> Color(0xFFFF3333)
                barMs < cropStartMs || barMs > cropEndMs -> Color(0xFF333333)
                else -> Color(0xFF00E5FF)
            }

            drawRoundRect(
                color = color,
                topLeft = Offset(x + barWidth * 0.1f, midY - barH / 2f),
                size = Size(barWidth * 0.8f, barH),
                cornerRadius = CornerRadius(1f)
            )
        }

        // Draw crop start handle
        val startPx = msToCanvasPx(cropStartMs, durationMs, zoom, scrollFrac, size.width)
        if (startPx in 0f..size.width) {
            drawLine(Color(0xFF00FF88), Offset(startPx, 0f), Offset(startPx, size.height), strokeWidth = 2.5f)
            drawRoundRect(
                color = Color(0xFF00FF88),
                topLeft = Offset(startPx, 0f),
                size = Size(16f, 24f),
                cornerRadius = CornerRadius(4f)
            )
        }

        // Draw crop end handle
        val endPx = msToCanvasPx(cropEndMs, durationMs, zoom, scrollFrac, size.width)
        if (endPx in 0f..size.width) {
            drawLine(Color(0xFFFFB74D), Offset(endPx, 0f), Offset(endPx, size.height), strokeWidth = 2.5f)
            drawRoundRect(
                color = Color(0xFFFFB74D),
                topLeft = Offset(endPx - 16f, 0f),
                size = Size(16f, 24f),
                cornerRadius = CornerRadius(4f)
            )
        }

        // Draw delete region overlay
        if (deleteStartMs >= 0 && deleteEndMs > deleteStartMs) {
            val dStartPx = msToCanvasPx(deleteStartMs, durationMs, zoom, scrollFrac, size.width)
            val dEndPx   = msToCanvasPx(deleteEndMs,   durationMs, zoom, scrollFrac, size.width)
            if (dEndPx > dStartPx) {
                drawRect(
                    color = Color(0x44FF0000),
                    topLeft = Offset(dStartPx, 0f),
                    size = Size(dEndPx - dStartPx, size.height)
                )
                drawLine(Color(0xFFFF3333), Offset(dStartPx, 0f), Offset(dStartPx, size.height), strokeWidth = 2f)
                drawLine(Color(0xFFFF3333), Offset(dEndPx,   0f), Offset(dEndPx,   size.height), strokeWidth = 2f)
            }
        }
    }
}

// ── Coordinate transform ──────────────────────────────────────────────────────
// Single source of truth for canvas-px <-> normalized-time-fraction <-> ms,
// used identically by the pinch/pan handler, the region-drag handler, and
// the draw pass below. Previously the pinch handler had its own inline copy
// of this same math (duplicated, not reused) — exactly the kind of
// redundant transform that lets gesture math and rendering drift apart.

private fun pxToFrac(px: Float, zoom: Float, scrollFrac: Float, width: Float): Float {
    if (width <= 0f || zoom <= 0f) return scrollFrac
    return (px / width) / zoom + scrollFrac
}

private fun fracToPx(frac: Float, zoom: Float, scrollFrac: Float, width: Float): Float {
    return (frac - scrollFrac) * zoom * width
}

private fun msToCanvasPx(ms: Long, durationMs: Long, zoom: Float, scrollFrac: Float, width: Float): Float {
    if (durationMs <= 0) return 0f
    return fracToPx(ms.toFloat() / durationMs, zoom, scrollFrac, width)
}

private fun canvasPxToMs(px: Float, durationMs: Long, zoom: Float, scrollFrac: Float, width: Float): Long {
    if (width <= 0) return 0L
    return (pxToFrac(px, zoom, scrollFrac, width) * durationMs).toLong().coerceIn(0L, durationMs)
}

// ── Amplitude computation ─────────────────────────────────────────────────────

private fun computeAmplitudes(pcm: PcmResult, bars: Int): FloatArray {
    val samplesPerChannel = pcm.pcm.size / pcm.channels
    val samplesPerBar     = max(1, samplesPerChannel / bars)
    val result = FloatArray(bars)

    for (bar in 0 until bars) {
        val start = bar * samplesPerBar * pcm.channels
        val end   = min(start + samplesPerBar * pcm.channels, pcm.pcm.size)
        if (start >= end) break
        var sumSq = 0.0
        var count = 0
        for (i in start until end step pcm.channels) {
            val s = pcm.pcm[i] / 32768f
            sumSq += s * s
            count++
        }
        result[bar] = if (count > 0) sqrt(sumSq / count).toFloat() else 0f
    }

    // Normalise
    val maxVal = result.max().coerceAtLeast(0.001f)
    for (i in result.indices) result[i] /= maxVal
    return result
}

// ── PCM editing ───────────────────────────────────────────────────────────────

/**
 * Applies crop + optional delete to the PCM, encodes to AAC m4a and returns
 * the resulting File. Runs on IO dispatcher.
 */
private suspend fun applyEdits(
    context: Context,
    pcm: PcmResult,
    durationMs: Long,
    cropStartMs: Long,
    cropEndMs: Long,
    deleteStartMs: Long,  // -1 = no delete
    deleteEndMs: Long,
    originalName: String
): File = withContext(Dispatchers.IO) {

    val outSamples = stitchEditedPcm(pcm, cropStartMs, cropEndMs, deleteStartMs, deleteEndMs)

    val outFile = File(
        context.cacheDir,
        "edit_${originalName}_${System.currentTimeMillis()}.wav"
    )
    writePcmToWav(
        samples   = outSamples,
        channels  = pcm.channels,
        sampleRate = pcm.sampleRate,
        outFile   = outFile
    )
    outFile
}

/**
 * Stitches crop + optional delete into a single output PCM (primitive
 * ShortArray, no encode) — shared by applyEdits (encodes to file) and the
 * in-editor PREVIEW playback (plays straight from memory via AudioTrack).
 */
private fun stitchEditedPcm(
    pcm: PcmResult,
    cropStartMs: Long,
    cropEndMs: Long,
    deleteStartMs: Long,  // -1 = no delete
    deleteEndMs: Long
): ShortArray {
    val channels   = pcm.channels
    val sampleRate = pcm.sampleRate

    // Convert ms → sample-frame indices (not byte indices)
    fun msToFrame(ms: Long) = (ms * sampleRate / 1000L).coerceIn(0, pcm.pcm.size.toLong() / channels)

    val cropStartFrame  = msToFrame(cropStartMs)
    val cropEndFrame    = msToFrame(cropEndMs)
    val delStartFrame   = if (deleteStartMs >= 0) msToFrame(deleteStartMs) else -1L
    val delEndFrame     = if (deleteStartMs >= 0) msToFrame(deleteEndMs)   else -1L

    // Build output PCM by stitching segments. PERF: a preallocated
    // ShortArray + arraycopy instead of boxing every sample into a
    // MutableList<Short> (hundreds of thousands to millions on a
    // multi-second clip).
    fun rangeIndices(fromFrame: Long, toFrame: Long): IntRange {
        val fromIdx = (fromFrame * channels).toInt().coerceIn(0, pcm.pcm.size)
        val toIdx   = (toFrame   * channels).toInt().coerceIn(0, pcm.pcm.size)
        return fromIdx until toIdx
    }

    val ranges = if (delStartFrame >= 0 && delEndFrame > delStartFrame) {
        // Crop start → delete start, then delete end → crop end
        listOf(
            rangeIndices(cropStartFrame, delStartFrame.coerceIn(cropStartFrame, cropEndFrame)),
            rangeIndices(delEndFrame.coerceIn(cropStartFrame, cropEndFrame), cropEndFrame)
        )
    } else {
        listOf(rangeIndices(cropStartFrame, cropEndFrame))
    }.filter { it.last >= it.first }

    val totalSamples = ranges.sumOf { it.last - it.first + 1 }
    val outSamples = ShortArray(totalSamples)
    var writeIdx = 0
    for (range in ranges) {
        val len = range.last - range.first + 1
        System.arraycopy(pcm.pcm, range.first, outSamples, writeIdx, len)
        writeIdx += len
    }
    return outSamples
}

/**
 * Writes raw 16-bit PCM straight into a WAV (RIFF) container — a plain
 * header + byte copy, no codec involved.
 *
 * BUG FIX: crop/delete used to re-encode the edited clip through
 * MediaCodec's async AAC encoder (dequeue/queue polling loop, ~10ms waits
 * per buffer) purely to save it as .m4a. That's real, audible latency on
 * every APPLY (client-reported: "save karne ke baad us tone me latency aa
 * jata hai") for a container format this app never needed — PcmDecoder
 * already reads WAV natively via MediaExtractor (minSdk 24 supports WAV
 * extraction), so there was nothing gained by encoding to AAC except the
 * encode cost itself and a lossy re-compression of an already-decoded PCM
 * clip. Writing WAV is a direct memory copy with a 44-byte header — no
 * polling loop, no MediaCodec/MediaMuxer instance to leak, and no quality
 * loss on repeated edits of the same pad.
 */
private fun writePcmToWav(
    samples: ShortArray,
    channels: Int,
    sampleRate: Int,
    outFile: File
) {
    val dataSize = samples.size * 2
    val byteRate = sampleRate * channels * 2
    val blockAlign = channels * 2

    java.io.FileOutputStream(outFile).use { fos ->
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)                 // PCM fmt chunk size
        header.putShort(1)                // PCM = 1
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16)               // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)
        fos.write(header.array())

        val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) body.putShort(s)
        fos.write(body.array())
    }
}

// ── Small UI helpers ──────────────────────────────────────────────────────────

@Composable
private fun ModeBtn(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Color(0xFF00E5FF) else Color(0xFF2A2A2A))
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color.Black else Color(0xFFAAAAAA),
            fontSize = 9.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EditorBtn(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2A2A2A))
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xFF00E5FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Time formatting ───────────────────────────────────────────────────────────

internal fun Long.toEditorTimeStr(): String {
    val ms  = this % 1000
    val sec = (this / 1000) % 60
    val min = this / 60_000
    return if (min > 0) "%d:%02d.%03d".format(min, sec, ms)
    else "%d.%03ds".format(sec, ms)
}

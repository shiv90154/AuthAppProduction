package com.example.myapplication.ui.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * WaveformEditorScreen
 *
 * Features:
 *  - Full waveform display of the assigned pad audio
 *  - Pinch-to-zoom (horizontal) and scroll via drag
 *  - Crop handles: drag left/right edges to set start/end trim
 *  - Delete region: tap "DEL REGION" to cut a selected middle section
 *  - Millisecond-precision display
 *  - Save: writes trimmed PCM back to a new file and updates AudioRepository
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
    onPickNewSound: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

            // ── Pick new sound — always available, even before any audio has
            // loaded, so a pad with nothing assigned isn't a dead end anymore.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1A1A))
                    .pointerInput(Unit) { detectTapGestures { onPickNewSound() } }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("PICK NEW SOUND FOR THIS PAD", color = BtnActive, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
                        onCropStartChange = { cropStartMs = it.coerceIn(0L, cropEndMs - 10L) },
                        onCropEndChange = { cropEndMs = it.coerceIn(cropStartMs + 10L, durationMs) },
                        onDeleteRegion = { s, e ->
                            deleteStartMs = s.coerceIn(cropStartMs, cropEndMs)
                            deleteEndMs = e.coerceIn(cropStartMs, cropEndMs)
                        },
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Reset
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
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("RESET", color = Color(0xFF888888), fontSize = 11.sp,
                                fontWeight = FontWeight.Bold)
                        }

                        // Save
                        Box(
                            modifier = Modifier
                                .weight(2f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSaving) Color(0xFF003322)
                                    else Color(0xFF005544)
                                )
                                .pointerInput(isSaving) {
                                    detectTapGestures {
                                        // BUG FIX: this used to also require audio != null,
                                        // so Save was unreachable for factory-kit pads even
                                        // though the waveform/crop UI above now loads and
                                        // displays them fine — the exact same "looks like it
                                        // works but doesn't actually do anything" bug as the
                                        // load-blocking one above. pcmResult != null is the
                                        // real readiness check (true for both custom and
                                        // factory audio now).
                                        if (isSaving || pcmResult == null) return@detectTapGestures
                                        scope.launch {
                                            isSaving = true
                                            saveMsg = null
                                            try {
                                                val result = pcmResult!!
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
                                                // BUG FIX: every other caller of assignRecordedAudio
                                                // (Mix, Add-To-End, mic recording) invalidates the
                                                // native pad buffer right after — this call site was
                                                // the one exception, so the edited/cropped audio was
                                                // saved to the repository but the pad kept playing the
                                                // OLD pre-edit sound until something unrelated (a kit
                                                // switch, an app restart) happened to force a reload.
                                                // "Save" now actually takes effect immediately.
                                                DrumEngine.invalidatePad(padIndex)
                                                DrumEngine.loadPad(context, kitIndex, padIndex, factoryResId)
                                                saveMsg = "Saved!"
                                                // refresh pcm + amplitudes for the new audio
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
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (isSaving) "Saving…" else "SAVE EDITS",
                                color = BtnActive, fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Save message
                    if (saveMsg != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            saveMsg!!,
                            color = if (saveMsg!!.startsWith("Error")) Color(0xFFFF6666)
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
    onCropStartChange: (Long) -> Unit,
    onCropEndChange: (Long) -> Unit,
    onDeleteRegion: (Long, Long) -> Unit,
    onZoomScroll: (Float, Float) -> Unit
) {
    // Track canvas width in px for coordinate math
    var canvasWidthPx by remember { mutableStateOf(1f) }

    // For delete drag tracking
    var dragStartPx by remember { mutableStateOf(-1f) }
    var dragEndPx by remember { mutableStateOf(-1f) }

    // BUG FIX: cropStartMs/cropEndMs/zoom/scrollFrac all change DURING the
    // very drag gestures below (onCropStartChange/onCropEndChange/onZoomScroll
    // write straight back into them) — with those values as pointerInput keys,
    // every single pixel of movement changed the key, which cancelled and
    // restarted the whole gesture-detector coroutine mid-drag. That reset
    // `activeHandle` back to "" on each restart, and since the pointer was
    // still down (previousPressed already true), the "which handle did we
    // grab" re-detection never re-ran either — so a crop handle would move
    // at most one pixel before the drag silently stopped responding. Same
    // problem for pinch-zoom: restarting detectTransformGestures mid-pinch
    // drops the gesture and needs fingers lifted and replaced to continue.
    // Fix: read live values via rememberUpdatedState instead of restart keys,
    // so the gesture loops start once per mode and never get torn down by
    // the state changes they themselves cause.
    val liveDurationMs by rememberUpdatedState(durationMs)
    val liveZoom by rememberUpdatedState(zoom)
    val liveScrollFrac by rememberUpdatedState(scrollFrac)
    val liveCropStartMs by rememberUpdatedState(cropStartMs)
    val liveCropEndMs by rememberUpdatedState(cropEndMs)

    Canvas(
        modifier = modifier
            .onGloballyPositioned { canvasWidthPx = it.size.width.toFloat() }
            // Two-finger pinch-to-zoom + pan-to-scroll — the standard
            // interaction pattern in crop/waveform editors (Splice,
            // GarageBand, Soundtrap, CapCut, etc.): pinch to zoom in/out,
            // drag with two fingers to scroll left/right through the zoomed
            // waveform. `pan` used to be received and silently discarded —
            // there was no way to actually scroll once zoomed in past 1x.
            // Key is Unit (start once), reads live state via rememberUpdatedState.
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, scale, _ ->
                    val z = liveZoom
                    val newZoom = (z * scale).coerceIn(1f, 32f)
                    // Keep the pinch centroid anchored to the same waveform
                    // position as the zoom level changes.
                    val centroidFrac = centroid.x / canvasWidthPx / z + liveScrollFrac
                    val zoomedScroll = centroidFrac - centroid.x / canvasWidthPx / newZoom
                    // Content follows the fingers: dragging right reveals
                    // earlier content (scrollFrac decreases), same direction
                    // convention as scrolling any horizontal list by touch.
                    val panFrac = pan.x / canvasWidthPx / newZoom
                    val newScroll = (zoomedScroll - panFrac)
                        .coerceIn(0f, (1f - 1f / newZoom).coerceAtLeast(0f))
                    onZoomScroll(newZoom / z, newScroll)
                }
            }
            // Tap/drag for crop handles or delete region — only restart when
            // the MODE itself changes (CROP vs DELETE genuinely need
            // different gesture logic), not on every value the drag updates.
            .pointerInput(editMode) {
                if (editMode == "CROP") {
                    // drag left edge = crop start, right edge = crop end
                    // Simple horizontal drag: determine which handle is closer
                    awaitPointerEventScope {
                        var activeHandle = ""  // "START" or "END"
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val px = change.position.x

                            if (change.pressed && !change.previousPressed) {
                                // Determine closest handle in px
                                val startPx = msToCanvasPx(liveCropStartMs, liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx)
                                val endPx   = msToCanvasPx(liveCropEndMs,   liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx)
                                activeHandle = if (abs(px - startPx) < abs(px - endPx)) "START" else "END"
                            }

                            if (change.pressed) {
                                val ms = canvasPxToMs(px, liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx)
                                when (activeHandle) {
                                    "START" -> onCropStartChange(ms)
                                    "END"   -> onCropEndChange(ms)
                                }
                                change.consume()
                            } else {
                                activeHandle = ""
                            }
                        }
                    }
                } else {
                    // Delete mode: drag to select region
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val px = change.position.x

                            if (change.pressed && !change.previousPressed) {
                                dragStartPx = px
                                dragEndPx = px
                            } else if (change.pressed) {
                                dragEndPx = px
                                val s = canvasPxToMs(
                                    dragStartPx.coerceIn(0f, canvasWidthPx),
                                    liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx
                                )
                                val e = canvasPxToMs(
                                    dragEndPx.coerceIn(0f, canvasWidthPx),
                                    liveDurationMs, liveZoom, liveScrollFrac, canvasWidthPx
                                )
                                onDeleteRegion(minOf(s, e), maxOf(s, e))
                                change.consume()
                            }
                        }
                    }
                }
            }
    ) {
        canvasWidthPx = size.width
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

// ── Coordinate helpers ────────────────────────────────────────────────────────

private fun msToCanvasPx(ms: Long, durationMs: Long, zoom: Float, scrollFrac: Float, width: Float): Float {
    if (durationMs <= 0) return 0f
    val frac = ms.toFloat() / durationMs
    val visibleFrac = 1f / zoom
    return ((frac - scrollFrac) / visibleFrac) * width
}

private fun canvasPxToMs(px: Float, durationMs: Long, zoom: Float, scrollFrac: Float, width: Float): Long {
    if (width <= 0) return 0L
    val visibleFrac = 1f / zoom
    val frac = (px / width) * visibleFrac + scrollFrac
    return (frac * durationMs).toLong().coerceIn(0L, durationMs)
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

    val channels   = pcm.channels
    val sampleRate = pcm.sampleRate

    // Convert ms → sample-frame indices (not byte indices)
    fun msToFrame(ms: Long) = (ms * sampleRate / 1000L).coerceIn(0, pcm.pcm.size.toLong() / channels)

    val cropStartFrame  = msToFrame(cropStartMs)
    val cropEndFrame    = msToFrame(cropEndMs)
    val delStartFrame   = if (deleteStartMs >= 0) msToFrame(deleteStartMs) else -1L
    val delEndFrame     = if (deleteStartMs >= 0) msToFrame(deleteEndMs)   else -1L

    // Build output PCM by stitching segments
    val outSamples = mutableListOf<Short>()

    fun addFrameRange(fromFrame: Long, toFrame: Long) {
        val fromIdx = (fromFrame * channels).toInt().coerceIn(0, pcm.pcm.size)
        val toIdx   = (toFrame   * channels).toInt().coerceIn(0, pcm.pcm.size)
        for (i in fromIdx until toIdx) outSamples.add(pcm.pcm[i])
    }

    if (delStartFrame >= 0 && delEndFrame > delStartFrame) {
        // Crop start → delete start, then delete end → crop end
        addFrameRange(cropStartFrame, delStartFrame.coerceIn(cropStartFrame, cropEndFrame))
        addFrameRange(delEndFrame.coerceIn(cropStartFrame, cropEndFrame), cropEndFrame)
    } else {
        addFrameRange(cropStartFrame, cropEndFrame)
    }

    // Encode to AAC m4a using MediaCodec
    val outFile = File(
        context.cacheDir,
        "edit_${originalName}_${System.currentTimeMillis()}.m4a"
    )
    encodePcmToM4a(
        samples   = outSamples.toShortArray(),
        channels  = channels,
        sampleRate = sampleRate,
        outFile   = outFile
    )
    outFile
}

/**
 * Encodes raw 16-bit PCM to AAC-LC in an MPEG-4 container.
 */
private fun encodePcmToM4a(
    samples: ShortArray,
    channels: Int,
    sampleRate: Int,
    outFile: File
) {
    val mimeType   = MediaFormat.MIMETYPE_AUDIO_AAC
    val bitRate    = 128_000

    val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channels).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        setInteger(MediaFormat.KEY_AAC_PROFILE,
            android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
    }

    val codec = MediaCodec.createEncoderByType(mimeType)
    val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var muxerTrack = -1
    var muxerStarted = false

    // BUG FIX: codec/muxer release used to only run after the loop finished
    // normally — any exception mid-encode (e.g. a full disk) leaked both
    // permanently. Android allows only a handful of concurrent codec
    // instances system-wide, so repeated failures here could eventually
    // break audio loading everywhere else in the app too.
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val bufferInfo  = MediaCodec.BufferInfo()
        var inputOffset = 0          // sample index into `samples`
        var sawInputEos = false
        var sawOutputEos = false

        // Each input buffer: 1024 frames per AAC frame
        val frameSamples = 1024 * channels

        while (!sawOutputEos) {
            // Feed input
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val remaining = samples.size - inputOffset
                        if (remaining <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val toCopy = min(remaining, min(frameSamples, inBuf.capacity() / 2))
                            val bytes  = ByteBuffer.allocate(toCopy * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in inputOffset until inputOffset + toCopy) bytes.putShort(samples[i])
                            bytes.flip()
                            inBuf.put(bytes)
                            val presentationUs = (inputOffset.toLong() / channels) * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inIdx, 0, toCopy * 2, presentationUs, 0)
                            inputOffset += toCopy
                        }
                    }
                }
            }

            // Drain output
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        muxerTrack = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && muxerStarted &&
                        bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        muxer.writeSampleData(muxerTrack, outBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }
        }
    } finally {
        try { codec.stop() } catch (e: Exception) { /* already stopped/never started successfully */ }
        codec.release()
        if (muxerStarted) {
            try { muxer.stop() } catch (e: Exception) { /* nothing was written */ }
        }
        muxer.release()
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

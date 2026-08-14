package com.example.myapplication.ui.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.BtnActive
import com.example.myapplication.ui.NavRed
import com.example.myapplication.ui.PanelBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
private const val MAX_TRIM_MS = 30_000L   // 30 seconds hard limit

/**
 * AudioTrimmer — shown when user picks a video file.
 *
 * Shows a drag handle so user can choose any 30-second window from the video.
 * On confirm it extracts only the audio track using MediaExtractor + MediaMuxer.
 *
 * @param videoUri      URI of the picked video
 * @param onDone        Called with the extracted audio File on success
 * @param onCancel      Called when user dismisses
 */
@Composable
fun AudioTrimmer(
    videoUri: Uri,
    maxTrimMs: Long = 30_000L,
    onDone: (File) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // Get total video duration
    val totalMs = remember(videoUri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L }
        finally { retriever.release() }
    }

    val maxStart   = (totalMs - maxTrimMs).coerceAtLeast(0L)
    val actualWindowMs =
        minOf(maxTrimMs, totalMs)
    var startMs    by remember { mutableStateOf(0L) }
    var isExtracting by remember { mutableStateOf(false) }
    var errorMsg   by remember { mutableStateOf<String?>(null) }

    val endMs = (startMs + actualWindowMs)
        .coerceAtMost(totalMs)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD000000))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            }
    ){
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(20.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(PanelBg)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Title ──────────────────────────────────────────────────────────
            Text(
                "Trim Video — Select ${actualWindowMs / 1000}s Window",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            // ── Duration info ──────────────────────────────────────────────────
            Text(
                "Video length: ${totalMs.toTimeStr()}   |   Window: ${startMs.toTimeStr()} → ${endMs.toTimeStr()}",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )

            // ── Trim bar ───────────────────────────────────────────────────────
            if (totalMs > 0) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    val barWidthPx = constraints.maxWidth.toFloat()

                    // Full track background
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2A2A2A))
                    )

                    // Selected window highlight
                    val windowFraction =
                        (actualWindowMs.toFloat() / totalMs.toFloat())
                            .coerceIn(0f, 1f)

                    val startFraction =
                        if (maxStart > 0)
                            startMs.toFloat() / maxStart.toFloat()
                        else
                            0f
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(windowFraction)
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    (
                                            startFraction *
                                                    (constraints.maxWidth * (1f - windowFraction))
                                            ).toInt(),
                                    0
                                )
                            }
                            .clip(RoundedCornerShape(8.dp))
                            .background(BtnActive.copy(alpha = 0.35f))
                    )

                    // Drag handle — user drags left/right to move window
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(totalMs) {
                                detectHorizontalDragGestures { _, dragAmount ->
                                    val msPerPx = totalMs.toFloat() / barWidthPx
                                    val delta   = (dragAmount * msPerPx).toLong()
                                    startMs = (startMs + delta).coerceIn(0L, maxStart)
                                }
                            }
                    )

                    // Start / End markers
                    Text(
                        startMs.toTimeStr(),
                        color = BtnActive,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp)
                    )
                    Text(
                        endMs.toTimeStr(),
                        color = BtnActive,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)
                    )
                }

                Text(
                    "← Drag left/right to move the 30s window →",
                    color = Color(0xFF666666),
                    fontSize = 10.sp
                )
            } else {
                Text("Could not read video duration.", color = NavRed, fontSize = 12.sp)
            }

            // ── Error ──────────────────────────────────────────────────────────
            if (errorMsg != null) {
                Text(errorMsg!!, color = NavRed, fontSize = 11.sp)
            }

            // ── Action buttons ─────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Cancel
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A))
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Cancel",
                        color = Color(0xFFCCCCCC),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.pressed }) onCancel()
                                }
                            }
                        }
                    )
                }

                // Confirm extract
                Box(
                    modifier = Modifier
                        .weight(2f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isExtracting) Color(0xFF003333) else Color(0xFF004444))
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isExtracting) "Extracting audio..." else "Extract Audio (${startMs.toTimeStr()} – ${endMs.toTimeStr()})",
                        color = BtnActive,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.pointerInput(isExtracting) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.pressed } && !isExtracting) {
                                        scope.launch {
                                            isExtracting = true
                                            errorMsg     = null
                                            try {
                                                val file = extractAudio(context, videoUri, startMs, endMs)
                                                onDone(file)
                                            } catch (e: Exception) {
                                                errorMsg = "Extraction failed: ${e.message}"
                                            } finally {
                                                isExtracting = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// ── Audio extraction using MediaExtractor + MediaMuxer ────────────────────────

private suspend fun extractAudio(
    context: Context,
    videoUri: Uri,
    startMs: Long,
    endMs: Long
): File = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    // BUG FIX: extractor/muxer release used to only run after the function
    // finished normally — this operates on a user-picked video file, which
    // can be malformed or have no valid samples in the selected time range,
    // so an exception mid-extraction was a real, reachable way to leak both
    // (and MediaExtractor/MediaMuxer/MediaCodec share the same small
    // system-wide instance pool, so this could break unrelated audio
    // loading elsewhere in the app too).
    try {
        extractor.setDataSource(context, videoUri, null)

        // Find audio track
        val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: throw IllegalStateException("No audio track found in video")

        extractor.selectTrack(audioTrackIndex)
        val format = extractor.getTrackFormat(audioTrackIndex)

        // Output file in app's Movies dir
        val outDir  = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        val outFile = File(outDir, "trim_${System.currentTimeMillis()}.mp4")

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val muxerTrack = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true

            val startUs = startMs * 1_000L
            val endUs   = endMs   * 1_000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val buffer   = java.nio.ByteBuffer.allocate(1024 * 1024)
            val bufInfo  = android.media.MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break

                bufInfo.presentationTimeUs = sampleTimeUs - startUs
                bufInfo.size   = sampleSize
                bufInfo.flags = 0
                bufInfo.offset = 0

                muxer.writeSampleData(muxerTrack, buffer, bufInfo)
                extractor.advance()
            }
        } catch (e: Exception) {
            // BUG FIX: on a mid-extraction failure (malformed video, no
            // samples in the selected window, full disk) the partially
            // written outFile used to be left behind on disk forever —
            // an orphaned temp file every time extraction failed, since the
            // caller only ever sees the thrown exception, never the File.
            outFile.delete()
            throw e
        } finally {
            if (muxerStarted) {
                try { muxer.stop() } catch (e: Exception) { /* nothing was written */ }
            }
            muxer.release()
        }

        outFile
    } finally {
        extractor.release()
    }
}

// toTimeStr() is defined in AudioListItem.kt (internal) — visible across this package
package com.example.myapplication.ui.pads
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.PadDark
import com.example.myapplication.ui.PadPressed
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
/**
 * DrumPad — base composable shared by all 8 individual pad wrappers.
 *
 * On press it shows a static RGB rainbow glow ring behind the pad.
 *
 * @param modifier  Standard Modifier (size/weight passed from parent)
 * @param pressed   Whether the pad is currently in a pressed/active state
 * @param onPress   Callback fired on tap
 */
@Composable
fun DrumPad(
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    modifier: Modifier = Modifier,
    pressed: Boolean,
    padNumber: Int,
    ledAtBottom: Boolean = false,
    onPress: () -> Unit,
    onRelease: () -> Unit = {},
    onDragStart: () -> Unit,
    onDragMove: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onPadPositionChanged: (Float, Float) -> Unit

) {
    Box(
        modifier = modifier

            .onGloballyPositioned { coordinates ->


                val pos = coordinates.positionInRoot()

                onPadPositionChanged(
                    pos.x,
                    pos.y
                )
            }
            // BUG FIX: this pad used to have THREE independent, uncoordinated
            // pointer-input handlers stacked on the same Box — a legacy
            // pointerInteropFilter (2-finger record, now dead code: its
            // onRecordStart/onRecordStop callbacks are no-op logging only,
            // recording is actually controlled from the EQ panel button) plus
            // two separate Compose pointerInput blocks (single-tap, 2-finger
            // drag). All three saw every touch independently. Concretely:
            // the tap-detector fired onPress() on ANY finger-down event,
            // including the second finger of a 2-finger swap/drag gesture —
            // so every attempt to drag-swap a pad also spuriously re-fired
            // that pad's sound and interfered with the drag. Unified into a
            // single coordinated handler below so a second finger touching
            // down is never misread as a fresh single-finger tap.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var dragging = false
                    var lastCenter = Offset.Zero

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        when {
                            pressed.size >= 2 -> {
                                val center = Offset(
                                    pressed.map { it.position.x }.average().toFloat(),
                                    pressed.map { it.position.y }.average().toFloat()
                                )

                                if (!dragging) {
                                    dragging = true
                                    lastCenter = center
                                    onDragStart()
                                } else {
                                    onDragMove(center.x - lastCenter.x, center.y - lastCenter.y)
                                    lastCenter = center
                                }

                                pressed.forEach { it.consume() }
                            }

                            pressed.size == 1 -> {
                                if (dragging) {
                                    // Dropped from 2 fingers to 1 — end the drag;
                                    // do NOT treat the remaining finger as a new tap.
                                    dragging = false
                                    onDragEnd()
                                } else if (event.changes.any { it.changedToDown() }) {
                                    // A genuine single-finger tap — fires on DOWN,
                                    // not on lift, so the pad feels instant.
                                    onPress()
                                }
                                // BUG FIX: this branch never consumed the touch —
                                // only the 2-finger branch did. An unconsumed
                                // finger-down/move event keeps propagating to any
                                // ancestor gesture detector on every frame while
                                // that first finger sits down waiting for a second
                                // one (the 2-finger swap/mix/add-to-end gesture),
                                // which can make Android's touch/gesture
                                // arbitration stall before the second finger's
                                // press is recognized as starting a drag. Consuming
                                // here (same as the 2-finger branch already does)
                                // stops that leak.
                                pressed.forEach { it.consume() }
                            }

                            else -> {
                                if (dragging) {
                                    dragging = false
                                    onDragEnd()
                                }
                                // BUG FIX: the LED/shadow used to stay lit for
                                // the sample's whole playback duration (plus a
                                // tail delay) instead of turning off the instant
                                // the finger lifts — this is what read as
                                // "latency" even though the actual audio
                                // trigger was already synchronous. All fingers
                                // are up now, so the visual press state ends
                                // here regardless of how long the sound itself
                                // keeps playing.
                                onRelease()
                            }
                        }
                    }
                }
            }
    ) {
        // Layer 1 — RGB glow ring drawn BEHIND the pad surface
//        Canvas(modifier = Modifier.matchParentSize()) {
//            if (pressed) {
//                val rgbBrush = Brush.sweepGradient(
//                    listOf(
//                        Color.Red,
//                        Color.Yellow,
//                        Color.Green,
//                        Color.Cyan,
//                        Color.Blue,
//                        Color.Magenta,
//                        Color.Red
//                    )
//                )
//                // Soft outer glow
//                drawRoundRect(
//                    brush        = rgbBrush,
//                    cornerRadius = CornerRadius(40f, 40f),
//                    style        = Stroke(width = 60f),
//                    alpha        = 0.25f
//                )
//                // Sharp inner ring
//                drawRoundRect(
//                    brush        = rgbBrush,
//                    cornerRadius = CornerRadius(40f, 40f),
//                    style        = Stroke(width = 20f),
//                    alpha        = 1f
//                )
//            }
//        }

        // Layer 2 — Actual pad surface ON TOP of the glow
        // 6.dp padding glow ko edges pe visible rakhti hai
        Column(
            modifier = Modifier.fillMaxSize()
        ) {


            if (!ledAtBottom) {

                Box(
                    modifier = Modifier
                        .padding(start = 50.dp, end = 50.dp, top = 3.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (pressed) Color.Red
                            else Color.DarkGray
                        )
                )

                Spacer(modifier = Modifier.height(4.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(1.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (pressed) PadPressed
                        else PadDark
                    )
            )

            if (ledAtBottom) {

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .padding(start = 50.dp, end = 50.dp, bottom = 3.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (pressed) Color.Red
                            else Color.DarkGray
                        )
                )
            }
        }
    }
}


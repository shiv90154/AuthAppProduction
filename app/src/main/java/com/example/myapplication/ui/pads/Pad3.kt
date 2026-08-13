package com.example.myapplication.ui.pads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun Pad3(
    modifier: Modifier = Modifier,
    pressed: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit = {},
    onDragStart: () -> Unit,
    onDragMove: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onPadPositionChanged: (Float, Float) -> Unit,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit
) {
    DrumPad(
        modifier = modifier,
        pressed = pressed,
        padNumber = 3,
        onPress = onPress,
        onRelease = onRelease,
        ledAtBottom = false,
        onRecordStart = onRecordStart,
        onRecordStop = onRecordStop,

        onDragStart = onDragStart,
        onDragMove = onDragMove,
        onDragEnd = onDragEnd,
        onPadPositionChanged = onPadPositionChanged
    )
}
package com.example.myapplication.ui.drag

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
fun DragPadOverlay(
    visible: Boolean,
    padNumber: Int,
    x: Float,
    y: Float
) {
    if (!visible) return

    Popup(
        offset = IntOffset(x.toInt(), y.toInt()),
        properties = PopupProperties(focusable = false)
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .alpha(0.85f)
                .background(color = Color(0xFF2A2A2A), shape = RoundedCornerShape(12.dp))
                .border(width = 3.dp, color = Color.Cyan, shape = RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "PAD $padNumber", color = Color.White)
        }
    }
}

package com.example.myapplication.ui.drag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PadActionMenu(
    visible: Boolean,
    onMix: () -> Unit,
    onAddToEnd: () -> Unit,
    onSwap: () -> Unit,
    onCancel: () -> Unit
) {
    if (!visible) return

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Clamp against maxWidth instead of a fixed 260.dp so this menu can't
        // exceed the available screen width on the smallest supported phones.
        val menuWidth = (maxWidth * 0.7f).coerceIn(200.dp, 260.dp)
        Column(
            modifier = Modifier
                .width(menuWidth)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MenuButton(text = "Mix",        color = Color(0xFF00E5FF), onClick = onMix)
            MenuButton(text = "Add To End", color = Color(0xFF00E5FF), onClick = onAddToEnd)
            MenuButton(text = "Swap",       color = Color(0xFF00E5FF), onClick = onSwap)
            MenuButton(text = "Cancel",     color = Color.Red,         onClick = onCancel)
        }
    }
}

@Composable
private fun MenuButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = color, fontSize = 18.sp)
    }
}

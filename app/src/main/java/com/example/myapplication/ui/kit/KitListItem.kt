package com.example.myapplication.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.BtnActive
import com.example.myapplication.ui.Kit

/**
 * KitListItem — ek kit ka single row in the list screen.
 *
 * @param kit        The Kit object to display
 * @param index      Position number (shown as label)
 * @param isSelected True if this is the currently active kit
 * @param onClick    Called when this row is tapped
 */
@Composable
fun KitListItem(
    kit: Kit,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    // NEW: single-patch export/share for just this one kit
    onExportPatch: () -> Unit = {}
) {
    val bgColor   = if (isSelected) Color(0xFF1A1A2E) else Color(0xFF1E1E1E)
    val textColor = if (isSelected) BtnActive        else Color(0xFFCCCCCC)
    val numColor  = if (isSelected) BtnActive        else Color(0xFF666666)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Index number badge
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isSelected) Color(0xFF00E5FF22) else Color(0xFF2A2A2A)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = "%03d".format(index + 1),
                color      = numColor,
                fontSize   = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Kit name
        Text(
            text       = kit.name,
            color      = textColor,
            fontSize   = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines   = 1,
            overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier   = Modifier.weight(1f)
        )

        // Active indicator dot
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(BtnActive)
            )
        }

        // Direct/inline rename — edits this kit's name right here instead of
        // needing to select it, close the list, open Settings, then Rename Kit.
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2A2A2A))
                .pointerInput(Unit) { detectTapGestures { onRename() } },
            contentAlignment = Alignment.Center
        ) {
            Text("✎", color = Color(0xFFCCCCCC), fontSize = 12.sp)
        }

        // NEW: export/share just this one kit as its own small patch file.
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2A2A2A))
                .pointerInput(Unit) { detectTapGestures { onExportPatch() } },
            contentAlignment = Alignment.Center
        ) {
            Text("⤴", color = Color(0xFFCCCCCC), fontSize = 12.sp)
        }
    }
}

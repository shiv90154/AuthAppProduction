package com.example.myapplication.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.BtnActive
import com.example.myapplication.ui.BtnBg
import com.example.myapplication.ui.Kit
import com.example.myapplication.ui.NavRed

/**
 * KitListScreen — full screen overlay showing all kits.
 *
 * User can:
 *  - Scroll through all kits
 *  - Tap a kit to select it and close the screen
 *  - Tap "+" to add a new kit
 *  - Tap "-" to delete the currently highlighted kit
 *
 * @param kits          Full list of kits
 * @param currentKit    Currently active kit index
 * @param onSelect      Called with index when user taps a kit row
 * @param onAdd         Called when "+" is tapped
 * @param onDelete      Called with index when "-" is tapped
 * @param onClose       Called when back/close is tapped
 */
@Composable
fun KitListScreen(
    kits: List<Kit>,
    currentKit: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Int) -> Unit,
    onCopy: (Int) -> Unit = {},
    onRename: (Int) -> Unit = {},
    // NEW: single-patch export/share for one kit at a time
    onExportPatch: (Int) -> Unit = {},
    onClose: () -> Unit
) {
    // Track which item is highlighted in this screen (starts at currentKit)
    var highlighted by remember { mutableStateOf(currentKit) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentKit)

    // Full screen dark overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))  // semi-transparent backdrop
            .pointerInput(Unit) { detectTapGestures { /* consume taps so nothing behind fires */ } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF111111))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "PATCH LIST",
                    color      = BtnActive,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    "${kits.size} kits",
                    color    = Color(0xFF666666),
                    fontSize = 11.sp
                )
                // Close button
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

            Spacer(modifier = Modifier.height(10.dp))

            // ── Kit List ──────────────────────────────────────────────────────
            LazyColumn(
                state  = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF161616)),
                contentPadding    = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(kits) { index, kit ->
                    KitListItem(
                        kit        = kit,
                        index      = index,
                        isSelected = index == highlighted,
                        onClick    = {
                            highlighted = index
                            onSelect(index)   // select and close
                        },
                        onRename   = {
                            highlighted = index
                            onRename(index)
                        },
                        onExportPatch = {
                            highlighted = index
                            onExportPatch(index)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Bottom Action Bar ─────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF111111))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // "+" Add new kit
                ActionBtn(
                    label    = "+  NEW KIT",
                    bgColor  = Color(0xFF003333),
                    txtColor = BtnActive,
                    modifier = Modifier.weight(1f)
                ) {
                    onAdd()
                }

                // "⧉" Copy highlighted kit (disabled once at the 200-kit cap)
                val canCopy = kits.size < 200
                ActionBtn(
                    label    = "⧉  COPY",
                    bgColor  = if (canCopy) Color(0xFF002233) else Color(0xFF1A1A1A),
                    txtColor = if (canCopy) BtnActive else Color(0xFF444444),
                    modifier = Modifier.weight(1f)
                ) {
                    if (canCopy) onCopy(highlighted)
                }

                // "-" Delete highlighted kit (disabled if only 1 kit)
                val canDelete = kits.size > 1
                ActionBtn(
                    label    = "−  DELETE",
                    bgColor  = if (canDelete) Color(0xFF330000) else Color(0xFF1A1A1A),
                    txtColor = if (canDelete) Color(0xFFFF4444) else Color(0xFF444444),
                    modifier = Modifier.weight(1f)
                ) {
                    if (canDelete) onDelete(highlighted)
                }
            }
        }
    }
}

// ── Small helper button used only in this screen ──────────────────────────────
@Composable
private fun ActionBtn(
    label: String,
    bgColor: Color,
    txtColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = label,
            color      = txtColor,
            fontSize   = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center
        )
    }
}

package com.example.myapplication.ui

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown instead of MidiLearnScreen when this device's activation code does
 * not include the MIDI capability (License.midiPurchased, toggled per-code
 * in the admin panel). Purely informational — no pricing, no purchase
 * button, no external link, no call to "contact" anyone or pay: the copy
 * deliberately avoids anything a Play reviewer could read as steering the
 * user to an out-of-app purchase for an in-app feature. It just states the
 * current code doesn't have MIDI and that an updated code unlocks it
 * automatically on the next check-in.
 */
@Composable
fun MidiPaywallScreen(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD000000))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(PanelBg)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(50)).background(Color(0xFF2A2A2A)),
                contentAlignment = Alignment.Center
            ) {
                Text("♪", color = LedActive, fontSize = 22.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text("MIDI not enabled for this code", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "MIDI Learn, pad mapping and CC control aren't enabled for your current activation code. If your code is updated to include MIDI, it unlocks automatically the next time the app checks in — no reinstall needed.",
                color = Color(0xFFAAAAAA), fontSize = 11.5.sp, textAlign = TextAlign.Center, lineHeight = 17.sp
            )
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2A2A2A))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("CLOSE", color = Color(0xFFCCCCCC), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

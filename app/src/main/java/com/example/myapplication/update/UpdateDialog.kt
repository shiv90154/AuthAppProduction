package com.example.myapplication.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Shown when AppUpdateApi reports a latestVersionCode above the installed
 * one. `forceUpdate` (set from the admin dashboard) drops the dismiss
 * button entirely — the dialog can't be closed without tapping Update,
 * which is the only way this app currently gates a mandatory update since
 * there's no separate kill-switch on the instrument itself.
 */
@Composable
fun UpdateDialog(info: AppUpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!info.forceUpdate) onDismiss() },
        title = { Text(if (info.forceUpdate) "Update required" else "Update available") },
        text = {
            Column {
                Text("A new version (${info.latestVersionName}) is available.")
                if (info.changelog.isNotBlank()) {
                    Text(info.changelog)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (info.apkUrl.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                if (!info.forceUpdate) onDismiss()
            }) {
                Text("Update")
            }
        },
        dismissButton = if (info.forceUpdate) null else {
            { TextButton(onClick = onDismiss) { Text("Later") } }
        }
    )
}

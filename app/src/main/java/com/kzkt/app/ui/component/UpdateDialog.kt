package com.kzkt.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kzkt.app.core.UpdateManager

/**
 * Global update dialog driven by [com.kzkt.app.ui.MainViewModel.updateState].
 * Rendered once at the app root so the auto-check on launch works from any tab.
 */
@Composable
fun UpdateDialog(
    checking: Boolean,
    info: UpdateManager.UpdateInfo?,
    downloading: Boolean,
    downloadProgress: Float,
    error: String?,
    upToDate: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    when {
        checking -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Checking for updates…") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
                        Text("Contacting GitHub Releases…")
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }

        downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Downloading update…") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { downloadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${(downloadProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Please keep the app open while the APK downloads.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {},
            )
        }

        upToDate -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("You're up to date") },
                text = { Text("KZKT ${com.kzkt.app.BuildConfig.VERSION_NAME} is the latest version.") },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("OK") }
                },
            )
        }

        error != null -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Update failed") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("OK") }
                },
            )
        }

        info != null -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Update available — v${info.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "New version: ${com.kzkt.app.BuildConfig.VERSION_NAME} → ${info.version}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (info.apkSizeBytes > 0) {
                            Text(
                                "Size: ${"%.1f".format(info.apkSizeBytes / 1024f / 1024f)} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (info.releaseNotes.isNotBlank()) {
                            MarkdownText(
                                info.releaseNotes,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = onDownload) { Text("Download & Install") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Later") }
                },
            )
        }
    }
}

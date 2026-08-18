package com.kzkt.app.ui.component

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import java.io.File

/**
 * Confirmation dialog before restoring a backup. Blocks dismissal while the
 * restore is running so the user cannot cancel mid-restore.
 */
@Composable
fun RestoreBackupDialog(
    isRestoring: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isRestoring) onDismiss() },
        title = { Text("Restore backup?") },
        text = {
            Text(
                "This will overwrite your current settings, glossary, history and translation " +
                    "memory with the contents of the backup file.",
            )
        },
        confirmButton = {
            TextButton(
                enabled = !isRestoring,
                onClick = onConfirm,
            ) { Text("Restore", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(
                enabled = !isRestoring,
                onClick = onDismiss,
            ) { Text("Cancel") }
        },
    )
}

/**
 * Font picker: list fonts bundled in the app's custom_fonts dir, with an option
 * to reset to the default font or import a new one via the system picker.
 */
@Composable
fun FontPickerDialog(
    context: Context,
    onPickDefault: () -> Unit,
    onPickFont: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Custom Font") },
        text = {
            val fontsDir = File(context.filesDir, "custom_fonts")
            val fonts =
                fontsDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".ttf") || it.name.endsWith(".otf")) } ?: emptyList()

            LazyColumn {
                item {
                    TextButton(
                        onClick = onPickDefault,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Default Font",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
                items(fonts) { font ->
                    TextButton(
                        onClick = { onPickFont(font.absolutePath) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(font.name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport) {
                Text("Import New Font")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

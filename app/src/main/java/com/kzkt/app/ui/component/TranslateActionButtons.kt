package com.kzkt.app.ui.component

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kzkt.app.ui.MainViewModel

/** File pickers / Cancel / Translate / Retry action buttons. */
@Composable
fun TranslateActionButtons(
    viewModel: MainViewModel,
    filePickerLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    folderPickerLauncher: ManagedActivityResultLauncher<Uri?, Uri?>,
) {
    val active by remember { derivedStateOf { viewModel.translationActive.value } }
    val hasFiles by remember { derivedStateOf { viewModel.selectedFiles.isNotEmpty() } }
    val yoloReady by remember { derivedStateOf { viewModel.yoloReady.value } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // File inputs — side by side, each half width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                enabled = !active,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pick File/Image")
            }

            OutlinedButton(
                onClick = { folderPickerLauncher.launch(null) },
                enabled = !active,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pick Folder")
            }
        }

        // Primary action — full width, natural height. (NOT weight(1f): inside a
        // Column weight stretches vertically and made the button fill the screen.)
        if (active) {
            Button(
                onClick = { viewModel.cancelTranslation() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cancel")
            }
        } else {
            // Retry-Failed button: appears after a batch when at least one file
            // failed, re-enqueuing ONLY the failed pages (1.35.0).
            val failedCount by remember {
                derivedStateOf {
                    viewModel.pageStatus.values.count { it == "failed" }
                }
            }
            if (failedCount > 0) {
                OutlinedButton(
                    onClick = { viewModel.retryFailedPages() },
                    enabled = hasFiles && yoloReady,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry Failed ($failedCount)")
                }
            }
            val canRetry by remember { derivedStateOf { viewModel.canRetry.value } }
            if (canRetry) {
                Button(
                    onClick = { viewModel.retryTranslation() },
                    enabled = hasFiles && yoloReady,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry")
                }
            } else {
                Button(
                    onClick = { viewModel.startTranslation() },
                    enabled = hasFiles && yoloReady,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Translate")
                }
            }
        }
    }
}

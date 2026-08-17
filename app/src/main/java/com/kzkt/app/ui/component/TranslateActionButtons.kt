package com.kzkt.app.ui.component

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kzkt.app.ui.MainViewModel

/** Centered empty state matching the screenshot when no files are selected. */
@Composable
fun EmptyMangaPickerView(
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            modifier = Modifier.size(68.dp),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Choose Manga / Comic",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Import images, folders, ZIP/CBZ, EPUB, or PDF to detect and translate speech bubbles.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onPickFile,
                enabled = enabled,
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pick File")
            }

            FilledTonalButton(
                onClick = onPickFolder,
                enabled = enabled,
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pick Folder")
            }
        }
    }
}

/** Action buttons shown when files are selected or translating. */
@Composable
fun TranslateActionButtons(
    viewModel: MainViewModel,
    filePickerLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    folderPickerLauncher: ManagedActivityResultLauncher<Uri?, Uri?>,
    modifier: Modifier = Modifier,
) {
    val active by remember { derivedStateOf { viewModel.translationActive.value } }
    val hasFiles by remember { derivedStateOf { viewModel.selectedFiles.isNotEmpty() } }
    val yoloReady by remember { derivedStateOf { viewModel.yoloReady.value } }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // File pickers row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                enabled = !active,
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pick File")
            }

            OutlinedButton(
                onClick = { folderPickerLauncher.launch(null) },
                enabled = !active,
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pick Folder")
            }
        }

        // Primary action
        if (active) {
            Button(
                onClick = { viewModel.cancelTranslation() },
                shape = RoundedCornerShape(50),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Cancel")
            }
        } else {
            val failedCount by remember {
                derivedStateOf {
                    viewModel.pageStatus.values.count { it == "failed" }
                }
            }
            if (failedCount > 0) {
                OutlinedButton(
                    onClick = { viewModel.retryFailedPages() },
                    enabled = hasFiles && yoloReady,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry Failed ($failedCount)")
                }
            }
            val canRetry by remember { derivedStateOf { viewModel.canRetry.value } }
            if (canRetry) {
                Button(
                    onClick = { viewModel.retryTranslation() },
                    enabled = hasFiles && yoloReady,
                    shape = RoundedCornerShape(50),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            } else {
                Button(
                    onClick = { viewModel.startTranslation() },
                    enabled = hasFiles && yoloReady,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Translate")
                }
            }
        }
    }
}

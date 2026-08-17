package com.kzkt.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzkt.app.core.TextRenderer
import com.kzkt.app.ui.FileUtils
import com.kzkt.app.ui.MainViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Bottom result card: View in App / Edit / Open in Gallery / Share, aligned with app design. */
@Composable
fun ResultPreviewCard(
    viewModel: MainViewModel,
    resultList: SnapshotStateList<String>,
) {
    val previewPath by remember { derivedStateOf { viewModel.currentPreviewPath.value } }

    if (previewPath != null || resultList.isNotEmpty()) {
        var showFullscreenViewer by remember { mutableStateOf(false) }
        var pdfReaderPath by remember { mutableStateOf<String?>(null) }
        val currentPath = previewPath ?: resultList.last()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        Spacer(Modifier.height(10.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Translation Result (${resultList.size} Pages)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        currentPath.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(10.dp))

                // Actions Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // View in App button (Pill shaped)
                    Button(
                        onClick = {
                            if (currentPath.endsWith(".pdf", ignoreCase = true)) {
                                pdfReaderPath = currentPath
                            } else {
                                showFullscreenViewer = true
                            }
                        },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "View in App",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    // Edit bubble text button
                    if (viewModel.lastResultForEditing.value != null && !currentPath.endsWith(".pdf", ignoreCase = true)) {
                        IconButton(
                            onClick = { viewModel.showInteractiveEditor.value = true },
                            colors =
                                IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ),
                            modifier =
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Text",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    // Open Gallery button (Outlined Pill)
                    OutlinedButton(
                        onClick = { FileUtils.openFileInSystemViewer(context, currentPath) },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Gallery",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    // Share button
                    IconButton(
                        onClick = { FileUtils.shareFile(context, currentPath) },
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        modifier =
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        val lastResult = viewModel.lastResultForEditing.value
        val allResultPaths = viewModel.resultPaths.toList()

        if (showFullscreenViewer) {
            val pagesToDisplay = if (allResultPaths.isNotEmpty()) allResultPaths else listOf(currentPath)

            if (pagesToDisplay.isNotEmpty()) {
                MangaReaderDialog(
                    pagePaths = pagesToDisplay,
                    initialIndex = allResultPaths.indexOf(currentPath).coerceAtLeast(0),
                    pipelineResult = lastResult,
                    targetLanguage = viewModel.settings.value.targetLanguage,
                    customFontPath = viewModel.settings.value.customFontPath,
                    renderStyle = viewModel.settings.value.renderStyle,
                    onDismiss = { showFullscreenViewer = false },
                )
            }
        }

        pdfReaderPath?.let { path ->
            PdfReaderDialog(
                pdfPath = path,
                onDismiss = { pdfReaderPath = null },
            )
        }

        if (viewModel.showInteractiveEditor.value && lastResult?.originalBitmap != null) {
            InteractiveEditorDialog(
                originalBitmap = lastResult.originalBitmap,
                translations = lastResult.translations,
                coordinateMap = lastResult.coordinateMap,
                textRenderer = TextRenderer(context),
                targetLanguage = viewModel.settings.value.targetLanguage,
                customFontPath = viewModel.settings.value.customFontPath,
                renderStyle = viewModel.settings.value.renderStyle,
                rawTexts = lastResult.rawTexts,
                styles = lastResult.styles,
                onDismiss = { viewModel.showInteractiveEditor.value = false },
                onSave = { updatedBitmap, updatedTranslations, updatedCoords, updatedStyles, onSaved ->
                    val outputPath = lastResult.outputPath
                    if (outputPath != null) {
                        val pristineOriginal = lastResult.originalBitmap
                        val rawTexts = lastResult.rawTexts
                        val targetLang = viewModel.settings.value.targetLanguage
                        scope.launch(Dispatchers.IO) {
                            try {
                                try {
                                    java.io.FileOutputStream(File(outputPath)).use { stream ->
                                        updatedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("KZKT", "Failed to save edited image: ${e.message}")
                                }
                                if (pristineOriginal != null) {
                                    try {
                                        com.kzkt.app.data.EditMetadataRepository(context).saveForOutput(
                                            outputPath,
                                            pristineOriginal,
                                            updatedTranslations,
                                            updatedCoords,
                                            targetLang,
                                            rawTexts,
                                            updatedStyles,
                                        )
                                    } catch (e: Exception) {
                                        android.util.Log.w("KZKT", "Failed to save edit metadata: ${e.message}")
                                    }
                                }
                            } finally {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    onSaved()
                                }
                            }
                        }
                    } else {
                        onSaved()
                    }

                    viewModel.lastResultForEditing.value =
                        lastResult.copy(
                            translations = updatedTranslations,
                            coordinateMap = updatedCoords,
                            styles = updatedStyles,
                        )
                },
            )
        }
    }
}

package com.kzkt.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun MainScreen(
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val logListState = rememberLazyListState()

    // Stable references to snapshot state lists — reading the list object itself
    // does not trigger recomposition (F4: no per-tick copy).
    val logList = viewModel.translationLog
    val resultList = viewModel.resultPaths

    // Auto-scroll log — non-animated jump, triggered only when the log grows (F2).
    val logSize by derivedStateOf { logList.size }
    LaunchedEffect(logSize) {
        if (logSize > 0) logListState.scrollToItem(logSize - 1)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // Initialize YOLO on first composition and request notification permission
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val permissionCheck = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                "android.permission.POST_NOTIFICATIONS"
            )
            if (permissionCheck != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
        }
    }

    // File picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val paths = uris.mapNotNull { FileUtils.getPathFromUri(context, it) }
            if (paths.isNotEmpty()) {
                viewModel.addFiles(paths)
            } else {
                // Fallback: copy to cache
                val copied = uris.mapNotNull { FileUtils.copyUriToCache(context, it) }
                viewModel.addFiles(copied)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header — matches History / Settings screens
        Text(
            "Translate",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        YoloStatus(viewModel)

        Spacer(Modifier.height(8.dp))

        QuickSettingsCard(viewModel)

        Spacer(Modifier.height(12.dp))

        ActionButtons(viewModel, filePickerLauncher)

        // ── Progress ──
        if (viewModel.translationActive.value) {
            Spacer(Modifier.height(8.dp))
            ProgressBar(viewModel)
        }

        Spacer(Modifier.height(12.dp))

        // ── Log output ──
        LogCard(
            viewModel = viewModel,
            logList = logList,
            logListState = logListState,
            modifier = Modifier.weight(1f),
        )

        ResultPreview(viewModel, resultList)
    }
}

@Composable
private fun StatusChip(
    icon: ImageVector,
    text: String,
    container: Color,
    content: Color,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

@Composable
private fun YoloStatus(viewModel: MainViewModel) {
    val yoloReady by derivedStateOf { viewModel.yoloReady.value }
    val yoloError by derivedStateOf { viewModel.yoloError.value }

    if (yoloReady) {
        StatusChip(
            icon = Icons.Default.CheckCircle,
            text = "YOLO ready",
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        val error = yoloError
        if (error != null) {
            StatusChip(
                icon = Icons.Default.Error,
                text = error,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            StatusChip(
                icon = Icons.Default.HourglassEmpty,
                text = "Loading YOLO...",
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoChip(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickSettingsCard(viewModel: MainViewModel) {
    val provider by derivedStateOf { viewModel.settings.value.llmProvider }
    val language by derivedStateOf { viewModel.settings.value.targetLanguage }
    val files = viewModel.selectedFiles

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(
                    icon = Icons.Outlined.Language,
                    label = provider.uppercase().replaceFirstChar { it },
                    modifier = Modifier.weight(1f),
                )
                InfoChip(
                    icon = Icons.Outlined.Translate,
                    label = "→ $language",
                    modifier = Modifier.weight(1f),
                )
            }

            if (files.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))
                Text(
                    "${files.size} file(s) selected",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                // Show at most 4 names so the card stays bounded and the
                // Translate button is never pushed off-screen by a big selection.
                val shown = files.take(4)
                shown.forEach { path ->
                    Text(
                        path.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val hidden = files.size - shown.size
                if (hidden > 0) {
                    Text(
                        "+$hidden more file(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    viewModel: MainViewModel,
    filePickerLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, List<Uri>>,
) {
    val active by derivedStateOf { viewModel.translationActive.value }
    val hasFiles by derivedStateOf { viewModel.selectedFiles.isNotEmpty() }
    val yoloReady by derivedStateOf { viewModel.yoloReady.value }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { filePickerLauncher.launch(arrayOf("image/*", "application/pdf")) },
            enabled = !active,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Pick Image")
        }

        if (active) {
            Button(
                onClick = { viewModel.cancelTranslation() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cancel")
            }
        } else {
            val canRetry by derivedStateOf { viewModel.canRetry.value }
            if (canRetry) {
                Button(
                    onClick = { viewModel.retryTranslation() },
                    enabled = hasFiles && yoloReady,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry")
                }
            } else {
                Button(
                    onClick = { viewModel.startTranslation() },
                    enabled = hasFiles && yoloReady,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Translate")
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(viewModel: MainViewModel) {
    val progress by derivedStateOf { viewModel.translationProgress.value }
    val done by derivedStateOf { viewModel.translationDone.value }
    val total by derivedStateOf { viewModel.translationTotal.value }

    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "$done / $total",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun LogCard(
    viewModel: MainViewModel,
    logList: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    logListState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val context = LocalContext.current
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Log",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${logList.size} entries",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (logList.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                val textToCopy = logList.joinToString("\n")
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("KZKT Logs", textToCopy)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "Copied ${logList.size} log entries to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy all logs",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (logList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Text(
                        "Select an image and press Translate to start.\n\nHistory tab below → finished translations.\nSettings tab → providers, API keys, tweak params.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = logListState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(logList, key = { index, _ -> index }) { _, msg ->
                        val isError = msg.contains("[!]") || msg.contains("[Cancelled]")
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = if (isError) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultPreview(
    viewModel: MainViewModel,
    resultList: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
) {
    val previewPath by derivedStateOf { viewModel.currentPreviewPath.value }

    if (previewPath != null || resultList.isNotEmpty()) {
        var showFullscreenViewer by remember { mutableStateOf(false) }
        val currentPath = previewPath ?: resultList.last()
        val context = LocalContext.current

        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Translation Result (${resultList.size} Pages)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        currentPath.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { showFullscreenViewer = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("View in App", fontSize = 12.sp)
                    }

                    if (viewModel.lastResultForEditing.value != null && !currentPath.endsWith(".pdf", ignoreCase = true)) {
                        IconButton(
                            onClick = { viewModel.showInteractiveEditor.value = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Text", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    OutlinedButton(
                        onClick = { FileUtils.openFileInSystemViewer(context, currentPath) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open in Gallery", fontSize = 12.sp)
                    }

                    IconButton(
                        onClick = { FileUtils.shareFile(context, currentPath) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        val lastResult = viewModel.lastResultForEditing.value
        val allResultPaths = viewModel.resultPaths.toList()
        var pdfReaderPages by remember { mutableStateOf<List<String>?>(null) }

        LaunchedEffect(showFullscreenViewer, currentPath) {
            if (showFullscreenViewer && currentPath.endsWith(".pdf", ignoreCase = true)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val cacheDir = File(context.cacheDir, "pdf_reader_cache")
                    val pages = com.kzkt.app.util.PdfImporter.extractPdfToImages(File(currentPath), cacheDir, context = context)
                    pdfReaderPages = pages
                }
            } else {
                pdfReaderPages = null
            }
        }

        if (showFullscreenViewer) {
            val pagesToDisplay = when {
                currentPath.endsWith(".pdf", ignoreCase = true) -> pdfReaderPages ?: emptyList()
                allResultPaths.isNotEmpty() -> allResultPaths
                else -> listOf(currentPath)
            }

            if (pagesToDisplay.isNotEmpty()) {
                com.kzkt.app.ui.component.MangaReaderDialog(
                    pagePaths = pagesToDisplay,
                    pipelineResult = lastResult,
                    targetLanguage = viewModel.settings.value.targetLanguage,
                    customFontPath = viewModel.settings.value.customFontPath,
                    onDismiss = { showFullscreenViewer = false }
                )
            }
        }

        if (viewModel.showInteractiveEditor.value && lastResult?.originalBitmap != null) {
            com.kzkt.app.ui.component.InteractiveEditorDialog(
                originalBitmap = lastResult.originalBitmap,
                translations = lastResult.translations,
                coordinateMap = lastResult.coordinateMap,
                textRenderer = com.kzkt.app.core.TextRenderer(context),
                targetLanguage = viewModel.settings.value.targetLanguage,
                customFontPath = viewModel.settings.value.customFontPath,
                onDismiss = { viewModel.showInteractiveEditor.value = false },
                onSave = { updatedBitmap, _ ->
                    if (lastResult.outputPath != null) {
                        val file = File(lastResult.outputPath)
                        java.io.FileOutputStream(file).use { stream ->
                            updatedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                        }
                    }
                    viewModel.showInteractiveEditor.value = false
                }
            )
        }
    }
}


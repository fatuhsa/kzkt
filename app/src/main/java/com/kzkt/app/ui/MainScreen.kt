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
import kotlinx.coroutines.launch

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
    val logSize by remember { derivedStateOf { logList.size } }
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

    val scope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val allPaths = mutableListOf<String>()
            
            for (uri in uris) {
                val mimeType = context.contentResolver.getType(uri)
                val path = com.kzkt.app.ui.FileUtils.getPathFromUri(context, uri)
                val isZip = mimeType?.contains("zip") == true || mimeType?.contains("cbz") == true || mimeType?.contains("epub") == true || 
                           path?.lowercase()?.endsWith(".zip") == true || 
                           path?.lowercase()?.endsWith(".cbz") == true ||
                           path?.lowercase()?.endsWith(".epub") == true
                           
                if (isZip) {
                    val extracted = com.kzkt.app.util.ArchiveExtractor.extractCbz(context, uri)
                    allPaths.addAll(extracted)
                } else if (path != null) {
                    allPaths.add(path)
                } else {
                    val copied = com.kzkt.app.ui.FileUtils.copyUriToCache(context, uri)
                    if (copied != null) allPaths.add(copied)
                }
            }
            
            if (allPaths.isNotEmpty()) {
                viewModel.addFiles(allPaths)
            }
        }
    }

    // ── Folder input (SAF tree picker): pick one folder, import all images in it ──
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            val toastContext = context
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val uris = com.kzkt.app.ui.FileUtils.listImageUrisFromTree(context, treeUri)
                if (uris.isEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(toastContext, "No images found in this folder", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val paths = uris.mapNotNull { com.kzkt.app.ui.FileUtils.copyUriToCache(context, it) }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (paths.isNotEmpty()) {
                        viewModel.addFiles(paths)
                        android.widget.Toast.makeText(toastContext, "Imported ${paths.size} images from folder", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
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

        ActionButtons(viewModel, filePickerLauncher, folderPickerLauncher)

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
    val yoloReady by remember { derivedStateOf { viewModel.yoloReady.value } }
    val yoloError by remember { derivedStateOf { viewModel.yoloError.value } }

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
    val provider by remember { derivedStateOf { viewModel.settings.value.llmProvider } }
    val language by remember { derivedStateOf { viewModel.settings.value.targetLanguage } }
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
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
    folderPickerLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Uri?, Uri?>,
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

@Composable
private fun ProgressBar(viewModel: MainViewModel) {
    val progress by remember { derivedStateOf { viewModel.translationProgress.value } }
    val done by remember { derivedStateOf { viewModel.translationDone.value } }
    val total by remember { derivedStateOf { viewModel.translationTotal.value } }

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
    val previewPath by remember { derivedStateOf { viewModel.currentPreviewPath.value } }

    if (previewPath != null || resultList.isNotEmpty()) {
        var showFullscreenViewer by remember { mutableStateOf(false) }
        // PDF results are viewed in the lazy in-app PDF reader (renders only the
        // visible pages) instead of rasterizing every page to disk first.
        var pdfReaderPath by remember { mutableStateOf<String?>(null) }
        val currentPath = previewPath ?: resultList.last()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

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
                        onClick = {
                            // PDF results open in the lazy in-app PDF reader (instant — no
                            // upfront per-page rasterization); images use the page reader.
                            if (currentPath.endsWith(".pdf", ignoreCase = true)) {
                                pdfReaderPath = currentPath
                            } else {
                                showFullscreenViewer = true
                            }
                        },
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

        if (showFullscreenViewer) {
            val pagesToDisplay = if (allResultPaths.isNotEmpty()) allResultPaths else listOf(currentPath)

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

        pdfReaderPath?.let { path ->
            com.kzkt.app.ui.component.PdfReaderDialog(
                pdfPath = path,
                onDismiss = { pdfReaderPath = null }
            )
        }

        if (viewModel.showInteractiveEditor.value && lastResult?.originalBitmap != null) {
            com.kzkt.app.ui.component.InteractiveEditorDialog(
                originalBitmap = lastResult.originalBitmap,
                translations = lastResult.translations,
                coordinateMap = lastResult.coordinateMap,
                textRenderer = com.kzkt.app.core.TextRenderer(context),
                targetLanguage = viewModel.settings.value.targetLanguage,
                customFontPath = viewModel.settings.value.customFontPath,
                rawTexts = lastResult.rawTexts,
                styles = lastResult.styles,
                onDismiss = { viewModel.showInteractiveEditor.value = false },
                onSave = { updatedBitmap, updatedTranslations, updatedCoords, updatedStyles, onSaved ->
                    val outputPath = lastResult.outputPath
                    if (outputPath != null) {
                        // Persist the edited image + bubble metadata off the main thread so
                        // reopening from History/reader shows the updated edits. onSaved() is
                        // called on the main thread once persistence finishes, which lets the
                        // editor show its "Saving..." state and then close itself.
                        val pristineOriginal = lastResult.originalBitmap
                        val rawTexts = lastResult.rawTexts
                        val targetLang = viewModel.settings.value.targetLanguage
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            // finally guarantees onSaved() runs (even on unexpected errors) so
                            // the editor's "Saving..." state can never get stuck.
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
                                            outputPath, pristineOriginal, updatedTranslations,
                                            updatedCoords, targetLang, rawTexts, updatedStyles)
                                    } catch (e: Exception) {
                                        android.util.Log.w("KZKT", "Failed to save edit metadata: ${e.message}")
                                    }
                                }
                            } finally {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    onSaved()
                                }
                            }
                        }
                    } else {
                        onSaved()
                    }

                    // Update state so if the user opens the editor again, they see their new edits
                    viewModel.lastResultForEditing.value = lastResult.copy(
                        translations = updatedTranslations,
                        coordinateMap = updatedCoords,
                        styles = updatedStyles
                    )
                }
            )
        }
    }
}


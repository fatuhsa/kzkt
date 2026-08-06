package com.kzkt.app.ui.component

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kzkt.app.ui.FileUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decode a page bitmap downsampled so its longest edge is at most [maxDim] px.
 * Reading bounds first lets us pick an inSampleSize power-of-two before the
 * actual decode, keeping reader memory bounded.
 */
private fun decodeSampledBitmap(path: String, maxDim: Int = 2048): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDim) {
            sampleSize *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } catch (_: Exception) {
        null
    }
}

/**
 * Fullscreen In-App Manga & PDF Reader Dialog with HorizontalPager,
 * Pinch-to-Zoom, Original vs. Translated toggle, and Live Touch-up Editing.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MangaReaderDialog(
    pagePaths: List<String>,
    initialIndex: Int = 0,
    pipelineResult: com.kzkt.app.core.TranslationPipeline.PipelineResult? = null,
    targetLanguage: String = "Indonesian",
    customFontPath: String = "",
    onDismiss: () -> Unit,
) {
    if (pagePaths.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, pagePaths.size - 1)) { pagePaths.size }

    var showControls by remember { mutableStateOf(true) }
    var showEditor by remember { mutableStateOf(false) }
    var isZoomed by remember { mutableStateOf(false) }

    var activeEditorBmp by remember { mutableStateOf<Bitmap?>(null) }
    var activeTranslations by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activeCoords by remember { mutableStateOf<Map<String, IntArray>>(emptyMap()) }
    var activeRawTexts by remember { mutableStateOf<Map<String, String>?>(null) }
    var activeStyles by remember { mutableStateOf<Map<String, com.kzkt.app.core.BubbleMeta>?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // ── Horizontal Pager Page Viewer ──
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !isZoomed,
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    val path = pagePaths[pageIndex]

                    // Decode off the main thread and downsample to at most ~2048px
                    // on the long edge. Manga pages are shown with ContentScale.Fit,
                    // so decoding the full-resolution file (often 3000–5000px, tens
                    // of MB) on the UI thread is what caused page-flip jank and high
                    // memory pressure in the reader.
                    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = path) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            decodeSampledBitmap(path)
                        }
                    }

                    ZoomablePageViewer(
                        bitmap = bitmap,
                        onTap = { showControls = !showControls },
                        onZoomStateChanged = { zoomed -> isZoomed = zoomed }
                    )
                }

                // ── Top App Bar (Controls) ──
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it },
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    TopAppBar(
                        title = {
                            Text(
                                text = "Page ${pagerState.currentPage + 1} / ${pagePaths.size}",
                                style = MaterialTheme.typography.titleMedium.copy(color = Color.White),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close Reader",
                                    tint = Color.White
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Black.copy(alpha = 0.7f)
                        )
                    )
                }

                // ── Bottom Control Toolbar ──
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Edit Text Button — resolves the page's bubble data from the
                            // persisted edit metadata first (works from History too), then
                            // falls back to the in-memory pipeline result, then to a plain
                            // page load (no editable bubbles). All disk I/O happens off the
                            // main thread.
                            IconButton(onClick = {
                                val currentPath = pagePaths[pagerState.currentPage]
                                scope.launch {
                                    val meta = withContext(Dispatchers.IO) {
                                        com.kzkt.app.data.EditMetadataRepository(context).loadForOutput(currentPath)
                                    }
                                    if (meta != null) {
                                        activeEditorBmp = meta.originalBitmap
                                        activeTranslations = meta.translations
                                        activeCoords = meta.coordinateMap
                                        activeRawTexts = meta.rawTexts
                                        activeStyles = meta.styles
                                        showEditor = true
                                    } else if (pipelineResult?.originalBitmap != null) {
                                        activeEditorBmp = pipelineResult.originalBitmap
                                        activeTranslations = pipelineResult.translations
                                        activeCoords = pipelineResult.coordinateMap
                                        activeRawTexts = pipelineResult.rawTexts
                                        activeStyles = pipelineResult.styles
                                        showEditor = true
                                    } else {
                                        val pageBmp = withContext(Dispatchers.IO) {
                                            com.kzkt.app.core.ImageProcessor.loadBitmap(currentPath)
                                        }
                                        if (pageBmp != null) {
                                            activeEditorBmp = pageBmp
                                            activeTranslations = emptyMap()
                                            activeCoords = emptyMap()
                                            activeRawTexts = null
                                            activeStyles = null
                                            showEditor = true
                                        }
                                    }
                                }
                            }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit Text",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            // Share Button
                            IconButton(
                                onClick = {
                                    val currentPath = pagePaths[pagerState.currentPage]
                                    FileUtils.shareFile(context, currentPath)
                                }
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            // Export CBZ Button
                            if (pagePaths.size > 1) {
                                IconButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            val fileName = "KZKT_Export_${System.currentTimeMillis()}.cbz"
                                            val cbzFile = com.kzkt.app.util.ArchiveExtractor.createCbz(context, pagePaths, fileName)
                                            withContext(Dispatchers.Main) {
                                                if (cbzFile != null) {
                                                    android.widget.Toast.makeText(context, "Exported to Downloads: ${cbzFile.name}", android.widget.Toast.LENGTH_LONG).show()
                                                } else {
                                                    android.widget.Toast.makeText(context, "Export failed", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Export CBZ",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Interactive Touch-up Editor Dialog ──
                if (showEditor && activeEditorBmp != null) {
                    InteractiveEditorDialog(
                        originalBitmap = activeEditorBmp!!,
                        translations = activeTranslations,
                        coordinateMap = activeCoords,
                        textRenderer = com.kzkt.app.core.TextRenderer(context),
                        targetLanguage = targetLanguage,
                        customFontPath = customFontPath,
                        rawTexts = activeRawTexts,
                        styles = activeStyles,
                        onDismiss = { showEditor = false },
                        onSave = { updatedBmp, updatedTranslations, updatedCoords, updatedStyles ->
                            val currentPath = pagePaths[pagerState.currentPage]
                            try {
                                val file = File(currentPath)
                                file.outputStream().use { out ->
                                    updatedBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("KZKT", "Failed to save edited page: ${e.message}")
                            }
                            showEditor = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomablePageViewer(
    bitmap: Bitmap?,
    onTap: () -> Unit,
    onZoomStateChanged: (Boolean) -> Unit = {}
) {
    if (bitmap == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(bitmap) {
        scale = 1f
        offset = Offset.Zero
        onZoomStateChanged(false)
    }

    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 4f)
        scale = newScale
        onZoomStateChanged(newScale > 1f)
        if (newScale > 1f) {
            offset += offsetChange
        } else {
            offset = Offset.Zero
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
                onDoubleClick = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                        onZoomStateChanged(false)
                    } else {
                        scale = 2.5f
                        onZoomStateChanged(true)
                    }
                }
            )
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
            .transformable(state = state, enabled = scale > 1f),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

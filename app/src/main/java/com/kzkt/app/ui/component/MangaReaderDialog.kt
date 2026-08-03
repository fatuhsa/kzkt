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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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

/**
 * Fullscreen In-App Manga & PDF Reader Dialog with HorizontalPager,
 * Pinch-to-Zoom, Original vs. Translated toggle, and Live Touch-up Editing.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MangaReaderDialog(
    pagePaths: List<String>,
    originalPaths: List<String> = emptyList(),
    initialIndex: Int = 0,
    pipelineResult: com.kzkt.app.core.TranslationPipeline.PipelineResult? = null,
    targetLanguage: String = "Indonesian",
    customFontPath: String = "",
    onDismiss: () -> Unit,
) {
    if (pagePaths.isEmpty()) return

    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, pagePaths.size - 1)) { pagePaths.size }

    var showControls by remember { mutableStateOf(true) }
    var showOriginal by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var isZoomed by remember { mutableStateOf(false) }

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
                    val path = if (showOriginal && pageIndex in originalPaths.indices) {
                        originalPaths[pageIndex]
                    } else {
                        pagePaths[pageIndex]
                    }

                    val bitmap = remember(path) {
                        try {
                            BitmapFactory.decodeFile(path)
                        } catch (_: Exception) { null }
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
                            // Toggle Original vs Translated
                            if (originalPaths.isNotEmpty()) {
                                FilterChip(
                                    selected = showOriginal,
                                    onClick = { showOriginal = !showOriginal },
                                    label = { Text(if (showOriginal) "Original" else "Translated") },
                                    leadingIcon = {
                                        Icon(
                                            if (showOriginal) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }

                            // Edit Text Button (if editing metadata available)
                            if (pipelineResult?.originalBitmap != null) {
                                IconButton(onClick = { showEditor = true }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit Text",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
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
                        }
                    }
                }

                // ── Interactive Touch-up Editor Dialog ──
                if (showEditor && pipelineResult?.originalBitmap != null) {
                    InteractiveEditorDialog(
                        originalBitmap = pipelineResult.originalBitmap,
                        translations = pipelineResult.translations,
                        coordinateMap = pipelineResult.coordinateMap,
                        textRenderer = com.kzkt.app.core.TextRenderer(context),
                        targetLanguage = targetLanguage,
                        customFontPath = customFontPath,
                        onDismiss = { showEditor = false },
                        onSave = { updatedBmp, _ ->
                            val currentPath = pagePaths[pagerState.currentPage]
                            try {
                                val file = File(currentPath)
                                file.outputStream().use { out ->
                                    updatedBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                            } catch (_: Exception) {}
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

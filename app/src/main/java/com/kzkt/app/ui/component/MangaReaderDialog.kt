package com.kzkt.app.ui.component

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kzkt.app.ui.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decode a page bitmap downsampled so its longest edge is at most [maxDim] px.
 * Reading bounds first lets us pick an inSampleSize power-of-two before the
 * actual decode, keeping reader memory bounded.
 */
private fun decodeSampledBitmap(
    path: String,
    maxDim: Int = 2048,
): Bitmap? =
    try {
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

private fun loadReaderBitmap(
    context: android.content.Context,
    path: String,
    isSideBySide: Boolean,
): Bitmap? {
    val translated = decodeSampledBitmap(path) ?: return null
    if (!isSideBySide) return translated

    val metaDir = java.io.File(context.filesDir, "edit_meta")
    val digest =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(
                java.io
                    .File(path)
                    .name
                    .toByteArray(),
            )
    val key = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
    val origFile = java.io.File(metaDir, "$key.png")

    val original = if (origFile.exists()) decodeSampledBitmap(origFile.absolutePath) else null
    if (original == null) return translated

    val combined =
        Bitmap.createBitmap(
            original.width + translated.width,
            maxOf(original.height, translated.height),
            Bitmap.Config.ARGB_8888,
        )
    val canvas = android.graphics.Canvas(combined)
    canvas.drawBitmap(original, 0f, 0f, null)
    canvas.drawBitmap(translated, original.width.toFloat(), 0f, null)
    original.recycle()
    translated.recycle()
    return combined
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
    pipelineResult: com.kzkt.app.core.PipelineResult? = null,
    targetLanguage: String = "Indonesian",
    customFontPath: String = "",
    renderStyle: String = "manga",
    onDismiss: () -> Unit,
) {
    if (pagePaths.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, pagePaths.size - 1)) { pagePaths.size }

    var showControls by remember { mutableStateOf(true) }
    var showEditor by remember { mutableStateOf(false) }
    // Bumped after a successful touch-up save: the page bitmaps are keyed on this
    // so the reader immediately shows the newly saved edits (previously the stale
    // cached bitmap stayed until the page left composition, e.g. swipe away & back).
    var pageReloadTick by remember { mutableIntStateOf(0) }
    var isZoomed by remember { mutableStateOf(false) }
    var isWebtoonMode by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf(false) }
    var isSideBySide by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf(false) }
    var isImmersive by remember { mutableStateOf(false) }

    // ── Immersive fullscreen (hide system bars) ──
    val dialogView = androidx.compose.ui.platform.LocalView.current
    val activityWindow = remember(context) { context.findActivity()?.window }
    LaunchedEffect(isImmersive) {
        val w = activityWindow ?: return@LaunchedEffect
        val controller =
            androidx.core.view.WindowCompat
                .getInsetsController(w, dialogView)
        if (isImmersive) {
            controller.hide(
                androidx.core.view.WindowInsetsCompat.Type
                    .systemBars(),
            )
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(
                androidx.core.view.WindowInsetsCompat.Type
                    .systemBars(),
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activityWindow?.let { w ->
                androidx.core.view.WindowCompat
                    .getInsetsController(w, dialogView)
                    .show(
                        androidx.core.view.WindowInsetsCompat.Type
                            .systemBars(),
                    )
            }
        }
    }

    var activeEditorBmp by remember { mutableStateOf<Bitmap?>(null) }
    var activeTranslations by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activeCoords by remember { mutableStateOf<Map<String, IntArray>>(emptyMap()) }
    var activeRawTexts by remember { mutableStateOf<Map<String, String>?>(null) }
    var activeStyles by remember { mutableStateOf<Map<String, com.kzkt.app.core.BubbleMeta>?>(null) }

    // Webtoon-mode scroll state plus the page the user is actually looking at. The
    // bottom toolbar (edit/share/export) must act on THIS page — in webtoon mode the
    // pager never scrolls, so pagerState.currentPage would target the wrong page.
    val webtoonListState = rememberLazyListState()
    val visibleWebtoonPage by remember { derivedStateOf { webtoonListState.firstVisibleItemIndex } }
    val currentPageIndex = if (isWebtoonMode) visibleWebtoonPage.coerceIn(0, pagePaths.size - 1) else pagerState.currentPage

    // Switching to webtoon mode continues from the page the pager was showing.
    LaunchedEffect(isWebtoonMode) {
        if (isWebtoonMode) webtoonListState.scrollToItem(pagerState.currentPage)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isWebtoonMode) {
                    LazyColumn(
                        state = webtoonListState,
                        // Tap anywhere to toggle the toolbar — but NOT via Modifier.clickable:
                        // a clickable on a scrollable container competes with the drag
                        // gesture and can freeze vertical scrolling. detectTapGestures
                        // only reacts to a clean tap and lets drag/fling pass through.
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures { showControls = !showControls }
                                },
                    ) {
                        items(pagePaths.size) { pageIndex ->
                            val path = pagePaths[pageIndex]
                            val bitmap by produceState<Bitmap?>(
                                initialValue = null,
                                key1 = path,
                                key2 = isSideBySide,
                                key3 = pageReloadTick,
                            ) {
                                value =
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        loadReaderBitmap(context, path, isSideBySide)
                                    }
                            }
                            val bmp = bitmap
                            // Reserve the page's aspect ratio while decoding so the list
                            // always has scrollable height (0-height items would make the
                            // list feel frozen until every page finishes loading).
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(if (bmp != null) bmp.width.toFloat() / bmp.height else 3f / 4f)
                                        .background(Color(0xFF1A1A1A)),
                            ) {
                                bmp?.let { loaded ->
                                    Image(
                                        bitmap = loaded.asImageBitmap(),
                                        contentDescription = "Page ${pageIndex + 1}",
                                        modifier = Modifier.fillMaxWidth(),
                                        contentScale = ContentScale.FillWidth,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ── Horizontal Pager Page Viewer ──
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !isZoomed,
                        modifier = Modifier.fillMaxSize(),
                    ) { pageIndex ->
                        val path = pagePaths[pageIndex]

                        val bitmap by produceState<Bitmap?>(initialValue = null, key1 = path, key2 = isSideBySide, key3 = pageReloadTick) {
                            value =
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    loadReaderBitmap(context, path, isSideBySide)
                                }
                        }

                        ZoomablePageViewer(
                            bitmap = bitmap,
                            onTap = { showControls = !showControls },
                            onZoomStateChanged = { zoomed -> isZoomed = zoomed },
                        )
                    }
                }

                // ── Top App Bar (Controls) ──
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it },
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    TopAppBar(
                        title = {
                            Text(
                                text = "Page ${currentPageIndex + 1} / ${pagePaths.size}",
                                style = MaterialTheme.typography.titleMedium.copy(color = Color.White),
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close Reader",
                                    tint = Color.White,
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { isSideBySide = !isSideBySide }) {
                                Icon(
                                    Icons.Default.Compare,
                                    contentDescription = "Side-by-side View",
                                    tint = if (isSideBySide) MaterialTheme.colorScheme.primary else Color.White,
                                )
                            }
                            IconButton(onClick = { isWebtoonMode = !isWebtoonMode }) {
                                Icon(
                                    if (isWebtoonMode) Icons.Default.ViewAgenda else Icons.Default.ViewCarousel,
                                    contentDescription = "Toggle Webtoon Mode",
                                    tint = Color.White,
                                )
                            }
                            IconButton(onClick = { isImmersive = !isImmersive }) {
                                Icon(
                                    if (isImmersive) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                    contentDescription = "Toggle Fullscreen",
                                    tint = if (isImmersive) MaterialTheme.colorScheme.primary else Color.White,
                                )
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Black.copy(alpha = 0.7f),
                            ),
                    )
                }

                // ── Bottom Control Toolbar ──
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Edit Text Button — resolves the page's bubble data from the
                            // persisted edit metadata first (works from History too), then
                            // falls back to the in-memory pipeline result, then to a plain
                            // page load (no editable bubbles). All disk I/O happens off the
                            // main thread.
                            IconButton(onClick = {
                                val currentPath = pagePaths[currentPageIndex]
                                scope.launch {
                                    val meta =
                                        withContext(Dispatchers.IO) {
                                            com.kzkt.app.data
                                                .EditMetadataRepository(context)
                                                .loadForOutput(currentPath)
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
                                        val pageBmp =
                                            withContext(Dispatchers.IO) {
                                                com.kzkt.app.core.ImageProcessor
                                                    .loadBitmap(currentPath)
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
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }

                            // Share Button
                            IconButton(
                                onClick = {
                                    val currentPath = pagePaths[currentPageIndex]
                                    FileUtils.shareFile(context, currentPath)
                                },
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            // Export CBZ Button
                            if (pagePaths.size > 1) {
                                IconButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            val fileName = "KZKT_Export_${System.currentTimeMillis()}.zip"
                                            val cbzFile =
                                                com.kzkt.app.util.ArchiveExtractor
                                                    .createCbz(context, pagePaths, fileName)
                                            withContext(Dispatchers.Main) {
                                                if (cbzFile != null) {
                                                    // Copy into the public Downloads/KZKT main folder (same
                                                    // pattern as the History export), then drop the temp copy.
                                                    val publicPath =
                                                        com.kzkt.app.ui.FileUtils.saveToMediaStore(
                                                            context,
                                                            cbzFile.absolutePath,
                                                        )
                                                    if (publicPath != null) {
                                                        cbzFile.delete()
                                                        android.widget.Toast
                                                            .makeText(
                                                                context,
                                                                "Saved to Downloads/KZKT: ${File(publicPath).name}",
                                                                android.widget.Toast.LENGTH_LONG,
                                                            ).show()
                                                    } else {
                                                        android.widget.Toast
                                                            .makeText(
                                                                context,
                                                                "Exported to Downloads: ${cbzFile.name}",
                                                                android.widget.Toast.LENGTH_LONG,
                                                            ).show()
                                                    }
                                                } else {
                                                    android.widget.Toast
                                                        .makeText(
                                                            context,
                                                            "Export failed",
                                                            android.widget.Toast.LENGTH_SHORT,
                                                        ).show()
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.FolderZip,
                                        contentDescription = "Export ZIP",
                                        tint = MaterialTheme.colorScheme.onSurface,
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
                        textRenderer =
                            com.kzkt.app.core
                                .TextRenderer(context),
                        targetLanguage = targetLanguage,
                        customFontPath = customFontPath,
                        renderStyle = renderStyle,
                        rawTexts = activeRawTexts,
                        styles = activeStyles,
                        onDismiss = { showEditor = false },
                        onSave = { updatedBmp, updatedTranslations, updatedCoords, updatedStyles, onSaved ->
                            val currentPath = pagePaths[currentPageIndex]
                            // Pristine (pre-inpaint) original — re-persisted alongside the
                            // edits so the editor keeps working from History with updated data.
                            val pristineOriginal = activeEditorBmp
                            scope.launch(Dispatchers.IO) {
                                var savedOk = true
                                try {
                                    try {
                                        File(currentPath).outputStream().use { out ->
                                            updatedBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                        }
                                    } catch (e: Exception) {
                                        savedOk = false
                                        android.util.Log.e("KZKT", "Failed to save edited page: ${e.message}")
                                    }
                                    if (pristineOriginal != null) {
                                        try {
                                            com.kzkt.app.data.EditMetadataRepository(context).saveForOutput(
                                                currentPath,
                                                pristineOriginal,
                                                updatedTranslations,
                                                updatedCoords,
                                                targetLanguage,
                                                activeRawTexts,
                                                updatedStyles,
                                            )
                                        } catch (e: Exception) {
                                            savedOk = false
                                            android.util.Log.w("KZKT", "Failed to save edit metadata: ${e.message}")
                                        }
                                    }
                                } finally {
                                    withContext(Dispatchers.Main) {
                                        // Only reload + confirm when the save really succeeded;
                                        // onSaved() always runs so the editor never gets stuck.
                                        if (savedOk) {
                                            pageReloadTick++
                                            android.widget.Toast
                                                .makeText(
                                                    context,
                                                    "Changes saved",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                        } else {
                                            android.widget.Toast
                                                .makeText(
                                                    context,
                                                    "Failed to save changes",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                        }
                                        onSaved()
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

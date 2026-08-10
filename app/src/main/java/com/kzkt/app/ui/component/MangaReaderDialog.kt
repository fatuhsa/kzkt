package com.kzkt.app.ui.component

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compare
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

private fun loadReaderBitmap(context: android.content.Context, path: String, isSideBySide: Boolean): Bitmap? {
    val translated = decodeSampledBitmap(path) ?: return null
    if (!isSideBySide) return translated

    val metaDir = java.io.File(context.filesDir, "edit_meta")
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(java.io.File(path).name.toByteArray())
    val key = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
    val origFile = java.io.File(metaDir, "$key.png")
    
    val original = if (origFile.exists()) decodeSampledBitmap(origFile.absolutePath) else null
    if (original == null) return translated

    val combined = Bitmap.createBitmap(original.width + translated.width, maxOf(original.height, translated.height), Bitmap.Config.ARGB_8888)
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
    // Bumped after a successful touch-up save: the page bitmaps are keyed on this
    // so the reader immediately shows the newly saved edits (previously the stale
    // cached bitmap stayed until the page left composition, e.g. swipe away & back).
    var pageReloadTick by remember { mutableIntStateOf(0) }
    var isZoomed by remember { mutableStateOf(false) }
    var isWebtoonMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var isSideBySide by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

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
                if (isWebtoonMode) {
                    LazyColumn(
                        state = webtoonListState,
                        // Tap anywhere to toggle the toolbar — but NOT via Modifier.clickable:
                        // a clickable on a scrollable container competes with the drag
                        // gesture and can freeze vertical scrolling. detectTapGestures
                        // only reacts to a clean tap and lets drag/fling pass through.
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { showControls = !showControls }
                            }
                    ) {
                        items(pagePaths.size) { pageIndex ->
                            val path = pagePaths[pageIndex]
                            val bitmap by produceState<Bitmap?>(initialValue = null, key1 = path, key2 = isSideBySide, key3 = pageReloadTick) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    loadReaderBitmap(context, path, isSideBySide)
                                }
                            }
                            val bmp = bitmap
                            // Reserve the page's aspect ratio while decoding so the list
                            // always has scrollable height (0-height items would make the
                            // list feel frozen until every page finishes loading).
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(if (bmp != null) bmp.width.toFloat() / bmp.height else 3f / 4f)
                                    .background(Color(0xFF1A1A1A))
                            ) {
                                bmp?.let { loaded ->
                                    Image(
                                        bitmap = loaded.asImageBitmap(),
                                        contentDescription = "Page ${pageIndex + 1}",
                                        modifier = Modifier.fillMaxWidth(),
                                        contentScale = ContentScale.FillWidth
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
                        modifier = Modifier.fillMaxSize()
                    ) { pageIndex ->
                        val path = pagePaths[pageIndex]

                        val bitmap by produceState<Bitmap?>(initialValue = null, key1 = path, key2 = isSideBySide, key3 = pageReloadTick) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                loadReaderBitmap(context, path, isSideBySide)
                            }
                        }

                        ZoomablePageViewer(
                            bitmap = bitmap,
                            onTap = { showControls = !showControls },
                            onZoomStateChanged = { zoomed -> isZoomed = zoomed }
                        )
                    }
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
                                text = "Page ${currentPageIndex + 1} / ${pagePaths.size}",
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
                        actions = {
                            IconButton(onClick = { isSideBySide = !isSideBySide }) {
                                Icon(
                                    Icons.Default.Compare,
                                    contentDescription = "Side-by-side View",
                                    tint = if (isSideBySide) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }
                            IconButton(onClick = { isWebtoonMode = !isWebtoonMode }) {
                                Icon(
                                    if (isWebtoonMode) Icons.Default.ViewAgenda else Icons.Default.ViewCarousel,
                                    contentDescription = "Toggle Webtoon Mode",
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
                                val currentPath = pagePaths[currentPageIndex]
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
                                    val currentPath = pagePaths[currentPageIndex]
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
                                                currentPath, pristineOriginal, updatedTranslations,
                                                updatedCoords, targetLanguage, activeRawTexts, updatedStyles)
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
                                            android.widget.Toast.makeText(context, "Changes saved", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Failed to save changes", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        onSaved()
                                    }
                                }
                            }
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

/**
 * Holds the PdfRenderer for [PdfReaderDialog] plus a small LRU of rendered page
 * bitmaps so swiping back to an already-seen page is instant.
 *
 * Thread-safety: PdfRenderer is NOT thread-safe, and the pager/webtoon items
 * render on IO concurrently — every render call is therefore synchronized.
 *
 * Evicted bitmaps are deliberately NOT recycled here: a page composable may
 * still be drawing a bitmap during a fast fling, and recycling it would crash
 * with "Canvas: trying to use a recycled bitmap". The LRU bound keeps the live
 * set small, and page composables drop their references as they leave the
 * screen, letting GC reclaim the memory.
 */
private class PdfReaderState(context: android.content.Context, pdfPath: String) {
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val cache = LruCache<Int, Bitmap>(MAX_CACHED_PAGES)

    val pageCount: Int
        get() = renderer?.pageCount ?: 0

    init {
        com.kzkt.app.util.PdfImporter.openPdfFileDescriptor(context, File(pdfPath))?.let { pfd ->
            fd = pfd
            renderer = try {
                PdfRenderer(pfd)
            } catch (e: Exception) {
                android.util.Log.w("KZKT/PDF", "PdfRenderer init failed: ${e.message}")
                try { pfd.close() } catch (_: Exception) {}
                fd = null
                null
            }
        }
    }

    @Synchronized
    fun renderPage(index: Int): Bitmap? {
        cache.get(index)?.let { return it }
        val r = renderer ?: return null
        if (index !in 0 until r.pageCount) return null
        val page = r.openPage(index)
        return try {
            // Uniform scale so the page aspect ratio is always preserved (the same
            // bug fixed in PdfImporter: an asymmetric cap squishes/stretches pages).
            val maxDim = 1600
            val rawW = page.width.toFloat()
            val rawH = page.height.toFloat()
            val fit = minOf(1.0f, maxDim / maxOf(rawW, rawH))
            val width = (rawW * fit).toInt().coerceAtLeast(1)
            val height = (rawH * fit).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(index, bmp)
            bmp
        } catch (e: Exception) {
            android.util.Log.w("KZKT/PDF", "Failed to render page ${index + 1}: ${e.message}")
            null
        } finally {
            page.close()
        }
    }

    @Synchronized
    fun close() {
        cache.evictAll()
        renderer?.close()
        renderer = null
        try { fd?.close() } catch (_: Exception) {}
        fd = null
    }

    companion object {
        private const val MAX_CACHED_PAGES = 5
    }
}

/**
 * Fast in-app PDF reader. Pages are rendered lazily from the PDF via Android
 * PdfRenderer — only the pages on screen — so a translated PDF opens instantly.
 * (The previous flow rasterized every page to disk up front, which was slow for
 * multi-page manga.) Supports pinch-zoom, webtoon mode, and sharing the PDF.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderDialog(
    pdfPath: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // Owns the renderer + page cache for the lifetime of the dialog.
    val pdfState = remember(pdfPath) { PdfReaderState(context, pdfPath) }
    DisposableEffect(pdfState) {
        onDispose {
            // Close off the main thread: close() waits (via its lock) for any
            // in-flight IO page render, which would otherwise stall dismissal.
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                pdfState.close()
            }
        }
    }

    val pageCount = pdfState.pageCount
    if (pageCount <= 0) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Could not open PDF", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onDismiss) {
                            Text("Close", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        return
    }

    val pagerState = rememberPagerState(initialPage = 0) { pageCount }
    var showControls by remember { mutableStateOf(true) }
    var isZoomed by remember { mutableStateOf(false) }
    var isWebtoonMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    val webtoonListState = rememberLazyListState()
    val visibleWebtoonPage by remember { derivedStateOf { webtoonListState.firstVisibleItemIndex } }
    val currentPageIndex = if (isWebtoonMode) visibleWebtoonPage.coerceIn(0, pageCount - 1) else pagerState.currentPage

    LaunchedEffect(isWebtoonMode) {
        if (isWebtoonMode) webtoonListState.scrollToItem(pagerState.currentPage)
    }

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
                if (isWebtoonMode) {
                    LazyColumn(
                        state = webtoonListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { showControls = !showControls }
                            }
                    ) {
                        items(pageCount) { pageIndex ->
                            val bitmap by produceState<Bitmap?>(initialValue = null, key1 = pageIndex) {
                                value = withContext(Dispatchers.IO) { pdfState.renderPage(pageIndex) }
                            }
                            val bmp = bitmap
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(if (bmp != null) bmp.width.toFloat() / bmp.height else 3f / 4f)
                                    .background(Color(0xFF1A1A1A))
                            ) {
                                bmp?.let { loaded ->
                                    Image(
                                        bitmap = loaded.asImageBitmap(),
                                        contentDescription = "Page ${pageIndex + 1}",
                                        modifier = Modifier.fillMaxWidth(),
                                        contentScale = ContentScale.FillWidth
                                    )
                                }
                            }
                        }
                    }
                } else {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !isZoomed,
                        modifier = Modifier.fillMaxSize()
                    ) { pageIndex ->
                        val bitmap by produceState<Bitmap?>(initialValue = null, key1 = pageIndex) {
                            value = withContext(Dispatchers.IO) { pdfState.renderPage(pageIndex) }
                        }
                        ZoomablePageViewer(
                            bitmap = bitmap,
                            onTap = { showControls = !showControls },
                            onZoomStateChanged = { zoomed -> isZoomed = zoomed }
                        )
                    }
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
                                text = "Page ${currentPageIndex + 1} / $pageCount",
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
                        actions = {
                            IconButton(onClick = { isWebtoonMode = !isWebtoonMode }) {
                                Icon(
                                    if (isWebtoonMode) Icons.Default.ViewAgenda else Icons.Default.ViewCarousel,
                                    contentDescription = "Toggle Webtoon Mode",
                                    tint = Color.White
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Black.copy(alpha = 0.7f)
                        )
                    )
                }

                // ── Bottom Toolbar ──
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
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { FileUtils.shareAnyFile(context, pdfPath, "application/pdf") }) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Share PDF",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

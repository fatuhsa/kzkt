package com.kzkt.app.ui.component

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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

/** Unwrap a (dialog/theme-wrapped) Context to the owning Activity, if any. */
internal tailrec fun android.content.Context.findActivity(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
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
private class PdfReaderState(
    context: android.content.Context,
    pdfPath: String,
) {
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val cache = LruCache<Int, Bitmap>(MAX_CACHED_PAGES)

    val pageCount: Int
        get() = renderer?.pageCount ?: 0

    init {
        com.kzkt.app.util.PdfImporter.openPdfFileDescriptor(context, File(pdfPath))?.let { pfd ->
            fd = pfd
            renderer =
                try {
                    PdfRenderer(pfd)
                } catch (e: Exception) {
                    android.util.Log.w("KZKT/PDF", "PdfRenderer init failed: ${e.message}")
                    try {
                        pfd.close()
                    } catch (_: Exception) {
                    }
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
        try {
            fd?.close()
        } catch (_: Exception) {
        }
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
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                pdfState.close()
            }
        }
    }

    val pageCount = pdfState.pageCount
    if (pageCount <= 0) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
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
    var isWebtoonMode by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf(false) }

    val webtoonListState = rememberLazyListState()
    val visibleWebtoonPage by remember { derivedStateOf { webtoonListState.firstVisibleItemIndex } }
    val currentPageIndex = if (isWebtoonMode) visibleWebtoonPage.coerceIn(0, pageCount - 1) else pagerState.currentPage

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
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures { showControls = !showControls }
                                },
                    ) {
                        items(pageCount) { pageIndex ->
                            val bitmap by produceState<Bitmap?>(initialValue = null, key1 = pageIndex) {
                                value = withContext(Dispatchers.IO) { pdfState.renderPage(pageIndex) }
                            }
                            val bmp = bitmap
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
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !isZoomed,
                        modifier = Modifier.fillMaxSize(),
                    ) { pageIndex ->
                        val bitmap by produceState<Bitmap?>(initialValue = null, key1 = pageIndex) {
                            value = withContext(Dispatchers.IO) { pdfState.renderPage(pageIndex) }
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
                                text = "Page ${currentPageIndex + 1} / $pageCount",
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
                            IconButton(onClick = { isWebtoonMode = !isWebtoonMode }) {
                                Icon(
                                    if (isWebtoonMode) Icons.Default.ViewAgenda else Icons.Default.ViewCarousel,
                                    contentDescription = "Toggle Webtoon Mode",
                                    tint = Color.White,
                                )
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Black.copy(alpha = 0.7f),
                            ),
                    )
                }

                // ── Bottom Toolbar ──
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
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { FileUtils.shareAnyFile(context, pdfPath, "application/pdf") }) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Share PDF",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

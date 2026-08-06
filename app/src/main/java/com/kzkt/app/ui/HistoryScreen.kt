package com.kzkt.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kzkt.app.data.HistoryEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by viewModel.historyEntries.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<HistoryEntry?>(null) }
    var readerPages by remember { mutableStateOf<List<String>?>(null) }
    var readerInitialIndex by remember { mutableIntStateOf(0) }
    var isExtractingPdf by remember { mutableStateOf(false) }

    fun openReaderForEntry(entry: HistoryEntry) {
        val file = File(entry.outputPath)
        if (file.name.endsWith(".pdf", ignoreCase = true)) {
            isExtractingPdf = true
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val cacheDir = File(context.cacheDir, "pdf_reader_cache")
                val pages = com.kzkt.app.util.PdfImporter.extractPdfToImages(file, cacheDir, context = context)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    isExtractingPdf = false
                    if (pages.isNotEmpty()) {
                        readerPages = pages
                        readerInitialIndex = 0
                    }
                }
            }
        } else if (file.exists()) {
            // Resolve sibling pages off the main thread — File.exists() is
            // synchronous disk I/O that stutters the UI when history is large.
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val allHistoryImages = entries.mapNotNull { e ->
                    val path = e.outputPath
                    if (!path.endsWith(".pdf", ignoreCase = true) && File(path).exists()) path else null
                }
                val idx = allHistoryImages.indexOf(file.absolutePath).coerceAtLeast(0)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    readerPages = if (allHistoryImages.isNotEmpty()) allHistoryImages else listOf(file.absolutePath)
                    readerInitialIndex = idx
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            EmptyHistoryPlaceholder()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "History",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                // Stable key: timestamp alone can collide when a multi-file batch is
                // recorded in the same millisecond, which made LazyColumn throw
                // "key already used" and stall the list. contentType lets LazyColumn
                // reuse item composition while scrolling.
                items(
                    entries,
                    key = { "${it.timestamp}_${it.fileName}_${it.pageCount}" },
                    contentType = { "history_entry" },
                ) { entry ->
                    HistoryItem(
                        entry = entry,
                        onClick = { openReaderForEntry(entry) },
                        onLongClick = { confirmDelete = entry },
                    )
                }
            }
        }

        if (isExtractingPdf) {
            androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text("Preparing Manga Pages...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (readerPages != null && readerPages!!.isNotEmpty()) {
            com.kzkt.app.ui.component.MangaReaderDialog(
                pagePaths = readerPages!!,
                initialIndex = readerInitialIndex,
                targetLanguage = viewModel.settings.value.targetLanguage,
                customFontPath = viewModel.settings.value.customFontPath,
                onDismiss = { readerPages = null }
            )
        }
    }

    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete history?") },
            text = { Text("Delete \"${entry.fileName}\" from history?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteHistoryEntry(entry.timestamp)
                        confirmDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyHistoryPlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(top = 120.dp),
    ) {
        Icon(
            Icons.Outlined.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No history yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Finished translations will appear here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItem(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Type badge: PDF vs image
            val isPdf = entry.outputPath.endsWith(".pdf", ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPdf) Icons.Filled.PictureAsPdf else Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${entry.pageCount} pages · ${entry.provider} · ${entry.targetLanguage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    formatTimestamp(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onLongClick() },
            )
        }
    }
}

private val DATE_FORMATTER by lazy { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

private fun formatTimestamp(ts: Long): String {
    return DATE_FORMATTER.format(Date(ts))
}

package com.kzkt.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.kzkt.app.core.Config
import com.kzkt.app.data.HistoryEntry
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by viewModel.historyEntries.collectAsStateWithLifecycle()

    // Search + provider filter state
    var query by rememberSaveable { mutableStateOf("") }
    var providerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var languageFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val dateRangeState = rememberDateRangePickerState()
    var showDateRangePicker by remember { mutableStateOf(false) }

    // Tombstones for entries pending permanent deletion — hides the item
    // immediately after a swipe while the DataStore write settles (supports Undo).
    var removedIds by remember { mutableStateOf(setOf<Long>()) }
    var clearedSnapshot by remember { mutableStateOf<List<HistoryEntry>?>(null) }

    var confirmDelete by remember { mutableStateOf<HistoryEntry?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var readerPages by remember { mutableStateOf<List<String>?>(null) }
    var readerInitialIndex by remember { mutableIntStateOf(0) }
    // Translated PDFs open in the lazy in-app PDF reader (no upfront per-page rasterization).
    var pdfReaderPath by remember { mutableStateOf<String?>(null) }

    // Multi-select mode for exporting/deleting several entries at once.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedTimestamps by remember { mutableStateOf(setOf<Long>()) }
    var exporting by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val filteredEntries = remember(entries, query, providerFilter, languageFilter, dateRangeState.selectedStartDateMillis, dateRangeState.selectedEndDateMillis, removedIds) {
        val startMillis = dateRangeState.selectedStartDateMillis
        val endMillis = dateRangeState.selectedEndDateMillis
        entries.asSequence()
            .filterNot { it.timestamp in removedIds }
            .filter { providerFilter == null || it.provider == providerFilter }
            .filter { languageFilter == null || it.targetLanguage == languageFilter }
            .filter {
                if (startMillis != null && endMillis != null) {
                    it.timestamp in startMillis..endMillis
                } else if (startMillis != null) {
                    it.timestamp >= startMillis
                } else if (endMillis != null) {
                    it.timestamp <= endMillis
                } else true
            }
            .filter {
                query.isBlank() ||
                    it.fileName.contains(query, ignoreCase = true) ||
                    providerDisplayName(it.provider).contains(query, ignoreCase = true) ||
                    it.targetLanguage.contains(query, ignoreCase = true)
            }
            .toList()
    }

    val groupedEntries = remember(filteredEntries) { groupByDay(filteredEntries) }

    val providerOptions = remember(entries) {
        entries.map { it.provider }
            .distinct()
            .sortedBy { providerDisplayName(it) }
    }
    val languageOptions = remember(entries) {
        entries.map { it.targetLanguage }.distinct().sorted()
    }

    fun openReaderForEntry(entry: HistoryEntry) {
        val file = File(entry.outputPath)
        if (file.name.endsWith(".pdf", ignoreCase = true)) {
            // Open instantly via the lazy in-app PDF reader (renders only the pages
            // on screen) — the old flow rasterized every page to disk first, which
            // made opening a translated PDF slow.
            pdfReaderPath = entry.outputPath
        } else if (file.exists()) {
            // Group pages by "book" so the reader only shows sibling pages of the
            // same chapter instead of every image in history. Sibling detection +
            // File.exists() run off the main thread (synchronous disk I/O).
            scope.launch(Dispatchers.IO) {
                val bookKey = bookGroupKey(file.absolutePath)
                val siblingPages = entries.mapNotNull { e ->
                    val p = e.outputPath
                    if (!p.endsWith(".pdf", ignoreCase = true) &&
                        File(p).exists() &&
                        bookGroupKey(p) == bookKey
                    ) p else null
                }.sortedWith(Comparator { a, b -> compareNatural(a, b) })

                val pages = siblingPages.ifEmpty { listOf(file.absolutePath) }
                val idx = pages.indexOf(file.absolutePath).coerceAtLeast(0)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    readerPages = pages
                    readerInitialIndex = idx
                }
            }
        }
    }

    fun deleteEntry(entry: HistoryEntry) {
        removedIds = removedIds + entry.timestamp
        viewModel.deleteHistoryEntry(entry.timestamp)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Deleted \"${entry.fileName}\"",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                removedIds = removedIds - entry.timestamp
                viewModel.restoreHistoryEntry(entry)
            }
        }
    }

    fun clearAll() {
        // Snapshot ALL entries (not just the filtered subset) so Undo can restore
        // everything even when a search/filter is active.
        val snapshot = entries
        clearedSnapshot = snapshot
        viewModel.clearHistory()
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "History cleared",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                clearedSnapshot?.let { viewModel.restoreHistoryEntries(it) }
            }
            clearedSnapshot = null
        }
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedTimestamps = emptySet()
    }

    fun toggleSelect(entry: HistoryEntry) {
        selectedTimestamps = if (entry.timestamp in selectedTimestamps) {
            selectedTimestamps - entry.timestamp
        } else {
            selectedTimestamps + entry.timestamp
        }
    }

    fun deleteSelected() {
        val doomed = entries.filter { it.timestamp in selectedTimestamps }
        // Batch delete must NOT go through deleteEntry(): that shows a per-item
        // Undo snackbar (N snackbars queued for N items). Remove silently from the
        // list + repo in one pass, and offer one bulk Undo like clearAll.
        removedIds = removedIds + doomed.map { it.timestamp }
        doomed.forEach { viewModel.deleteHistoryEntry(it.timestamp) }
        exitSelectionMode()
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Deleted ${doomed.size} entr${if (doomed.size == 1) "y" else "ies"}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                removedIds = removedIds - doomed.map { it.timestamp }.toSet()
                viewModel.restoreHistoryEntries(doomed)
            }
        }
    }

    /**
     * Export the selected entries as one ZIP (reuses the CBZ writer — same format,
     * .zip extension) or one PDF. Runs off the main thread, then shares the file.
     */
    fun exportSelected(asPdf: Boolean) {
        val selected = entries.filter { it.timestamp in selectedTimestamps }
        // Only image outputs can be packed — PDF entries stay out of the archive.
        val paths = selected.mapNotNull { e ->
            val p = e.outputPath
            if (!p.endsWith(".pdf", ignoreCase = true) && File(p).exists()) p else null
        }
        if (paths.isEmpty()) {
            android.widget.Toast.makeText(context, "No image files selected to export", android.widget.Toast.LENGTH_SHORT).show()
            exitSelectionMode()
            return
        }
        exporting = true
        val toastContext = context
        scope.launch(Dispatchers.IO) {
            val stamp = System.currentTimeMillis()
            val out = if (asPdf) {
                com.kzkt.app.util.ArchiveExtractor.createPdf(toastContext, paths, "KZKT_Export_$stamp.pdf")
            } else {
                com.kzkt.app.util.ArchiveExtractor.createCbz(toastContext, paths, "KZKT_Export_$stamp.zip")
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                exporting = false
                if (out != null) {
                    com.kzkt.app.ui.FileUtils.shareAnyFile(
                        toastContext, out.absolutePath,
                        if (asPdf) "application/pdf" else "application/zip"
                    )
                    exitSelectionMode()
                } else {
                    android.widget.Toast.makeText(toastContext, "Export failed", android.widget.Toast.LENGTH_SHORT).show()
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "header") {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (selectionMode) "Select (${selectedTimestamps.size})" else "History",
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp),
                            )
                            if (selectionMode) {
                                TextButton(onClick = { exitSelectionMode() }) {
                                    Text("Done")
                                }
                            } else {
                                TextButton(onClick = { selectionMode = true }) {
                                    Text("Select")
                                }
                            }
                            IconButton(onClick = { confirmClearAll = true }, enabled = !selectionMode) {
                                Icon(
                                    Icons.Filled.DeleteSweep,
                                    contentDescription = "Clear all history",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // Search field
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search history...") },
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Clear search",
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                        )

                        // Provider filter chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = providerFilter == null,
                                onClick = { providerFilter = null },
                                label = { Text("All") },
                                leadingIcon = if (providerFilter == null) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else null,
                            )
                            providerOptions.forEach { key ->
                                FilterChip(
                                    selected = providerFilter == key,
                                    onClick = { providerFilter = key },
                                    label = { Text(providerDisplayName(key)) },
                                    leadingIcon = if (providerFilter == key) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                    } else null,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showDateRangePicker = true }) {
                                Icon(Icons.Outlined.DateRange, contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(18.dp))
                                Text(if (dateRangeState.selectedStartDateMillis != null) "Custom Date" else "All Time")
                            }
                            if (dateRangeState.selectedStartDateMillis != null) {
                                IconButton(onClick = { dateRangeState.setSelection(null, null) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Filled.Close, "Clear Date", modifier = Modifier.size(16.dp))
                                }
                            }
                            Divider(modifier = Modifier.height(24.dp).width(1.dp))
                            
                            FilterChip(
                                selected = languageFilter == null,
                                onClick = { languageFilter = null },
                                label = { Text("All Langs") },
                                leadingIcon = if (languageFilter == null) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else null,
                            )
                            languageOptions.forEach { lang ->
                                FilterChip(
                                    selected = languageFilter == lang,
                                    onClick = { languageFilter = lang },
                                    label = { Text(lang) },
                                    leadingIcon = if (languageFilter == lang) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                    } else null,
                                )
                            }
                        }
                    }
                }

                if (filteredEntries.isEmpty()) {
                    // Keep the search bar + chips visible so the query stays clearable.
                    item(key = "no_results") {
                        NoResultsItem()
                    }
                } else {
                    // Date-grouped entries ("Today", "Yesterday", then formatted date)
                    groupedEntries.forEach { (label, groupEntries) ->
                        item(key = "day_${groupEntries.first().timestamp}") {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        // Stable key: timestamp alone can collide when a multi-file batch is
                        // recorded in the same millisecond, which made LazyColumn throw
                        // "key already used" and stall the list. contentType lets LazyColumn
                        // reuse item composition while scrolling.
                        items(
                            groupEntries,
                            key = { "${it.timestamp}_${it.fileName}_${it.pageCount}" },
                            contentType = { "history_entry" },
                        ) { entry ->
                            SwipeToDismissHistoryItem(
                                entry = entry,
                                onClick = {
                                    if (selectionMode) toggleSelect(entry) else openReaderForEntry(entry)
                                },
                                onLongClick = { if (selectionMode) toggleSelect(entry) else confirmDelete = entry },
                                onDelete = { deleteEntry(entry) },
                                selectionMode = selectionMode,
                                isSelected = entry.timestamp in selectedTimestamps,
                                onToggleSelect = { toggleSelect(entry) },
                                onRetry = { viewModel.retryHistoryEntry(entry) },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // ── Selection-mode action bar (export / delete / cancel) ──
        if (selectionMode && selectedTimestamps.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionActionButton(
                        icon = Icons.Filled.Refresh,
                        label = "ZIP",
                        enabled = !exporting,
                        onClick = { exportSelected(asPdf = false) },
                    )
                    SelectionActionButton(
                        icon = Icons.Filled.PictureAsPdf,
                        label = "PDF",
                        enabled = !exporting,
                        onClick = { exportSelected(asPdf = true) },
                    )
                    SelectionActionButton(
                        icon = Icons.Filled.Delete,
                        label = "Delete",
                        enabled = !exporting,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { deleteSelected() },
                    )
                    SelectionActionButton(
                        icon = Icons.Filled.Close,
                        label = "Cancel",
                        enabled = !exporting,
                        onClick = { exitSelectionMode() },
                    )
                }
            }
        }

        if (readerPages != null && readerPages!!.isNotEmpty()) {
            com.kzkt.app.ui.component.MangaReaderDialog(
                pagePaths = readerPages!!,
                initialIndex = readerInitialIndex,
                bookKey = bookGroupKey(readerPages!!.first()),
                targetLanguage = viewModel.settings.value.targetLanguage,
                customFontPath = viewModel.settings.value.customFontPath,
                onDismiss = { readerPages = null }
            )
        }

        pdfReaderPath?.let { path ->
            com.kzkt.app.ui.component.PdfReaderDialog(
                pdfPath = path,
                onDismiss = { pdfReaderPath = null }
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
                        deleteEntry(entry)
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

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all history?") },
            text = { Text("Remove all ${entries.size} entries from history?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearAll = false
                        clearAll()
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            },
        )
    }

    // Date-range filter picker (opened by the "Custom Date" button above). The
    // filter itself already runs on dateRangeState in [filteredEntries]; this is
    // the missing dialog that lets the user actually set the range.
    // (material3 1.5.0-alpha25 no longer ships DateRangePickerDialog, so the
    // picker is wrapped in a plain Dialog + Card.)
    if (showDateRangePicker) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showDateRangePicker = false }) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "Filter by date range",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    DateRangePicker(
                        state = dateRangeState,
                        showModeToggle = false,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            dateRangeState.setSelection(null, null)
                            showDateRangePicker = false
                        }) { Text("Clear") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { showDateRangePicker = false }) { Text("OK") }
                    }
                }
            }
        }
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

/** In-list empty state shown below the (still visible) search bar. */
@Composable
private fun NoResultsItem() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "No results found",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Try a different search or filter",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/** Swipe left (End→Start) to delete, with a red background + trash affordance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissHistoryItem(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        // Swipe-to-delete stays disabled in selection mode (taps select instead).
        gesturesEnabled = !selectionMode,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        },
    ) {
        HistoryItem(
            entry = entry,
            onClick = onClick,
            onLongClick = onLongClick,
            selectionMode = selectionMode,
            isSelected = isSelected,
            onToggleSelect = onToggleSelect,
            onRetry = onRetry,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItem(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val isPdf = entry.outputPath.endsWith(".pdf", ignoreCase = true)
    val isFailed = entry.status == "failed"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selection checkbox (only in selection mode).
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.size(40.dp),
                )
            }

            HistoryThumbnail(entry = entry, isPdf = isPdf)

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
                    "${entry.pageCount} pages · ${providerDisplayName(entry.provider)} · ${entry.targetLanguage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isFailed) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Failed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = onRetry,
                            enabled = !entry.inputPath.isNullOrBlank(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        TIME_FORMATTER.format(Date(entry.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // IconButton gives a proper 48dp minimum touch target. Hidden in
            // selection mode — the checkbox + swipe are the delete/select paths.
            if (!selectionMode) {
                IconButton(onClick = onLongClick) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Translated-page thumbnail via Coil. Falls back to the PDF/image type icon when
 * the entry is a PDF or the underlying file no longer exists on disk.
 */
@Composable
private fun HistoryThumbnail(
    entry: HistoryEntry,
    isPdf: Boolean,
) {
    val file = remember(entry.outputPath) { File(entry.outputPath) }
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 76.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (!isPdf && file.exists()) {
            SubcomposeAsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    )
                },
                error = { TypeBadgeIcon(isPdf) },
            )
        } else {
            TypeBadgeIcon(isPdf)
        }
    }
}

@Composable
private fun TypeBadgeIcon(isPdf: Boolean) {
    Icon(
        imageVector = if (isPdf) Icons.Filled.PictureAsPdf else Icons.Filled.Image,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
    )
}

/** One compact icon+label button in the selection-mode action bar. */
@Composable
private fun SelectionActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (enabled) tint else tint.copy(alpha = 0.4f))
    }
}

// ── Helpers ────────────────────────────────────────────────────────

private fun providerDisplayName(key: String): String =
    Config.PROVIDER_REGISTRY[key]?.displayName ?: key

private val DAY_HEADER_FORMATTER by lazy { SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()) }
private val TIME_FORMATTER by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }

private fun dayOf(ts: Long): LocalDate =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

/** Group entries into day buckets with friendly headers, newest day first. */
private fun groupByDay(entries: List<HistoryEntry>): List<Pair<String, List<HistoryEntry>>> {
    if (entries.isEmpty()) return emptyList()
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    return entries
        .groupBy { dayOf(it.timestamp) }
        .toSortedMap(compareByDescending { it })
        .map { (day, list) ->
            val label = when (day) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> DAY_HEADER_FORMATTER.format(Date(day.toEpochDay() * 86_400_000L))
            }
            label to list
        }
}

/**
 * Heuristic "book" key: parent directory + file base name with any trailing
 * digits/separators stripped ("…/KZKT/chapter1_p01.png" → "…/KZKT|chapter1_p").
 * Used to group sibling pages of the same chapter in the reader; the directory
 * is included to avoid merging unrelated books that share page naming schemes.
 */
private fun bookGroupKey(path: String): String {
    val f = File(path)
    var base = f.name.substringBeforeLast('.').trim()
    // MediaStore appends " (1)", " (2)", ... when a display name collides on
    // re-export — strip that suffix so re-exported pages still group with the
    // rest of the batch instead of becoming their own "book".
    base = base.replace(Regex("\\s+\\(\\d+\\)$"), "")
    val stripped = base.trimEnd { it.isDigit() || it == '_' || it == '-' || it == ' ' }
    // Pure-numbered page names ("001", "002", ...) strip to nothing. Fall back to
    // a shared "numbered" token so all numbered pages in the same folder group
    // into one reader session (previously every page got its own key → the reader
    // showed only the tapped page, no swipe).
    return "${f.parent ?: ""}|${stripped.ifEmpty { "numbered" }}"
}

/** Natural (numeric-aware) string comparison for page ordering. */
private fun compareNatural(a: String, b: String): Int {
    val regex = Regex("(\\d+)|(\\D+)")
    val ca = regex.findAll(a).map { it.value }.toList()
    val cb = regex.findAll(b).map { it.value }.toList()
    var i = 0
    while (i < ca.size && i < cb.size) {
        val x = ca[i]
        val y = cb[i]
        val cmp = if (x.all(Char::isDigit) && y.all(Char::isDigit)) {
            x.toLong().compareTo(y.toLong())
        } else {
            x.compareTo(y)
        }
        if (cmp != 0) return cmp
        i++
    }
    return ca.size - cb.size
}

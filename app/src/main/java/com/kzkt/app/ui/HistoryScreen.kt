package com.kzkt.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kzkt.app.data.HistoryEntry
import com.kzkt.app.ui.component.EmptyHistoryPlaceholder
import com.kzkt.app.ui.component.HistoryFolderCard
import com.kzkt.app.ui.component.HistorySelectionBar
import com.kzkt.app.ui.component.MangaReaderDialog
import com.kzkt.app.ui.component.NoResultsItem
import com.kzkt.app.ui.component.PdfReaderDialog
import com.kzkt.app.ui.component.SwipeToDismissHistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by viewModel.historyEntries.collectAsStateWithLifecycle()

    // Search state
    var query by rememberSaveable { mutableStateOf("") }

    // Sort state — how the list is ordered AND how the reader orders the pages
    // of a batch. Default: by translation time, page 1 on top (= first page).
    var sortMode by rememberSaveable { mutableStateOf(HistorySortMode.TIME) }
    var sortDescending by rememberSaveable { mutableStateOf(false) }

    // Tombstones for entries pending permanent deletion — hides the item
    // immediately after a swipe while the DataStore write settles (supports Undo).
    var removedIds by remember { mutableStateOf(setOf<Long>()) }
    var clearedSnapshot by remember { mutableStateOf<List<HistoryEntry>?>(null) }

    var confirmDelete by remember { mutableStateOf<HistoryEntry?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    // Batch drill-down: which translation run (folder) is open. Null = main list.
    // Stored as the batchKeyOf() value so the folder view can filter siblings.
    var openBatchKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Whole-batch delete confirmation (long-press on a folder card).
    var confirmDeleteBatch by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var readerPages by remember { mutableStateOf<List<String>?>(null) }
    var readerInitialIndex by remember { mutableIntStateOf(0) }
    // Translated PDFs open in the lazy in-app PDF reader (no upfront per-page rasterization).
    var pdfReaderPath by remember { mutableStateOf<String?>(null) }

    // Multi-select mode for exporting/deleting several entries at once.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedTimestamps by remember { mutableStateOf(setOf<Long>()) }
    var exporting by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val filteredEntries =
        remember(entries, query, removedIds) {
            filterHistoryEntries(
                entries = entries,
                query = query,
                removedIds = removedIds,
            )
        }

    // Apply the chosen sort to the displayed list. Grouping by day happens AFTER
    // sorting so each day bucket keeps the selected order internally.
    val sortedFiltered =
        remember(filteredEntries, sortMode, sortDescending) {
            sortHistoryEntries(filteredEntries, sortMode, sortDescending)
        }
    val groupedEntries = remember(sortedFiltered) { groupByDayAndBatch(sortedFiltered) }

    // Total page count per translation run (from the unfiltered list) — used to
    // show "X of Y pages match" on folder cards while a search is active.
    val batchTotals =
        remember(entries, removedIds) {
            entries
                .filterNot { it.timestamp in removedIds }
                .groupingBy { batchKeyOf(it) }
                .eachCount()
        }
    // Pages of the currently open folder (still query-filtered, still sorted).
    val folderEntries =
        remember(openBatchKey, sortedFiltered) {
            if (openBatchKey == null) emptyList() else sortedFiltered.filter { batchKeyOf(it) == openBatchKey }
        }

    fun openReaderForEntry(entry: HistoryEntry) {
        val file = File(entry.outputPath)
        if (file.name.endsWith(".pdf", ignoreCase = true)) {
            // Open instantly via the lazy in-app PDF reader (renders only the pages
            // on screen) — the old flow rasterized every page to disk first, which
            // made opening a translated PDF slow.
            pdfReaderPath = entry.outputPath
        } else if (file.exists()) {
            // Group pages by batch so the reader shows every sibling of the same
            // translation run (folder / multi-select / PDF) instead of only the
            // tapped page — ordered by the SAME sort the user picked in the list
            // (time or name, ascending/descending). Sibling detection +
            // File.exists() run off the main thread (synchronous disk I/O).
            scope.launch(Dispatchers.IO) {
                val (pages, initialIndex) = orderedPagesFor(entries, file.absolutePath, sortMode, sortDescending)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    readerPages = pages
                    readerInitialIndex = initialIndex
                }
            }
        }
    }

    fun deleteEntry(entry: HistoryEntry) {
        removedIds = removedIds + entry.timestamp
        viewModel.deleteHistoryEntry(entry.timestamp)
        scope.launch {
            val result =
                snackbarHostState.showSnackbar(
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
            val result =
                snackbarHostState.showSnackbar(
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
        selectedTimestamps =
            if (entry.timestamp in selectedTimestamps) {
                selectedTimestamps - entry.timestamp
            } else {
                selectedTimestamps + entry.timestamp
            }
    }

    /** Toggle every page of a translation run at once (folder-card select). */
    fun toggleSelectBatch(batchEntries: List<HistoryEntry>) {
        val timestamps = batchEntries.map { it.timestamp }.toSet()
        val allSelected = batchEntries.all { it.timestamp in selectedTimestamps }
        selectedTimestamps = if (allSelected) selectedTimestamps - timestamps else selectedTimestamps + timestamps
    }

    /** Delete a whole translation run with one Undo (long-press on a folder card). */
    fun deleteBatch(batchEntries: List<HistoryEntry>) {
        removedIds = removedIds + batchEntries.map { it.timestamp }
        batchEntries.forEach { viewModel.deleteHistoryEntry(it.timestamp) }
        scope.launch {
            val result =
                snackbarHostState.showSnackbar(
                    message = "Deleted ${batchEntries.size} entr${if (batchEntries.size == 1) "y" else "ies"}",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) {
                removedIds = removedIds - batchEntries.map { it.timestamp }.toSet()
                viewModel.restoreHistoryEntries(batchEntries)
            }
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
            val result =
                snackbarHostState.showSnackbar(
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
     * .zip extension) or one PDF. Runs off the main thread, then copies the result
     * into the public Downloads/KZKT folder (no share sheet — it just lands there).
     */
    fun exportSelected(asPdf: Boolean) {
        val selected = entries.filter { it.timestamp in selectedTimestamps }
        // Only image outputs can be packed — PDF entries stay out of the archive
        // (they are already PDF files; packing them would nest a PDF inside a ZIP).
        val pdfCount = selected.count { it.outputPath.endsWith(".pdf", ignoreCase = true) }
        val paths =
            selected.mapNotNull { e ->
                val p = e.outputPath
                if (!p.endsWith(".pdf", ignoreCase = true) && File(p).exists()) p else null
            }
        if (paths.isEmpty()) {
            val msg =
                if (pdfCount > 0) {
                    "PDF entr${if (pdfCount == 1) "y is" else "ies are"} already PDF files — only image pages can be packed"
                } else {
                    "No image files selected to export"
                }
            android.widget.Toast
                .makeText(context, msg, android.widget.Toast.LENGTH_SHORT)
                .show()
            exitSelectionMode()
            return
        }
        exporting = true
        val toastContext = context
        scope.launch(Dispatchers.IO) {
            val stamp = System.currentTimeMillis()
            val out =
                if (asPdf) {
                    com.kzkt.app.util.ArchiveExtractor
                        .createPdf(toastContext, paths, "KZKT_Export_$stamp.pdf")
                } else {
                    com.kzkt.app.util.ArchiveExtractor
                        .createCbz(toastContext, paths, "KZKT_Export_$stamp.zip")
                }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                exporting = false
                if (out != null) {
                    // Copy into the public Downloads/KZKT folder. The private temp copy
                    // is only deleted when the public copy actually landed (otherwise
                    // the fallback path in the toast would point at a missing file).
                    val publicPath =
                        com.kzkt.app.ui.FileUtils
                            .saveToMediaStore(toastContext, out.absolutePath)
                    if (publicPath != null) {
                        out.delete()
                        android.widget.Toast
                            .makeText(
                                toastContext,
                                "Saved to Downloads/KZKT: ${File(publicPath).name}",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                    } else {
                        android.widget.Toast
                            .makeText(
                                toastContext,
                                "Saved: ${out.absolutePath}",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                    }
                    exitSelectionMode()
                } else {
                    android.widget.Toast
                        .makeText(toastContext, "Export failed", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    // System back closes the open folder before leaving the screen.
    BackHandler(enabled = openBatchKey != null) {
        openBatchKey = null
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
                if (openBatchKey == null) {
                    // ── MAIN LIST ──
                    item(key = "header") {
                        HistoryFilterHeader(
                            selectionMode = selectionMode,
                            selectedCount = selectedTimestamps.size,
                            query = query,
                            onQueryChange = { query = it },
                            onSelectMode = { selectionMode = true },
                            onExitSelectMode = { exitSelectionMode() },
                            onClearAllClick = { confirmClearAll = true },
                            sortMode = sortMode,
                            onSortModeChange = { sortMode = it },
                            sortDescending = sortDescending,
                            onToggleSortDirection = { sortDescending = !sortDescending },
                        )
                    }

                    if (filteredEntries.isEmpty()) {
                        // Keep the search bar + chips visible so the query stays clearable.
                        item(key = "no_results") {
                            NoResultsItem()
                        }
                    } else {
                        // Day buckets ("Today", "Yesterday", ...), each split into its
                        // translation runs: multi-page runs collapse to a folder card,
                        // single-page runs (one image / a PDF) stay as direct items.
                        groupedEntries.forEach { dayGroup ->
                            item(key = "day_${dayGroup.batches.first().entries.first().timestamp}") {
                                Text(
                                    dayGroup.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                )
                            }
                            dayGroup.batches.forEach { batch ->
                                if (batch.entries.size > 1) {
                                    val batchKey = batchKeyOf(batch.entries.first())
                                    item(key = "folder_$batchKey") {
                                        HistoryFolderCard(
                                            entries = batch.entries,
                                            title = batchFolderTitle(batch.entries),
                                            matchNote =
                                                if (query.isNotBlank()) {
                                                    val total = batchTotals[batchKey] ?: batch.entries.size
                                                    if (batch.entries.size < total) "${batch.entries.size} of $total pages match" else null
                                                } else {
                                                    null
                                                },
                                            selectionMode = selectionMode,
                                            isSelected = batch.entries.all { it.timestamp in selectedTimestamps },
                                            onClick = {
                                                if (selectionMode) toggleSelectBatch(batch.entries) else openBatchKey = batchKey
                                            },
                                            onLongClick = {
                                                if (selectionMode) toggleSelectBatch(batch.entries) else confirmDeleteBatch = batch.entries
                                            },
                                            onToggleSelect = { toggleSelectBatch(batch.entries) },
                                        )
                                    }
                                } else {
                                    // Single-page run: direct item (page-level select).
                                    items(
                                        batch.entries,
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
                } else {
                    // ── FOLDER DRILL-DOWN: header (back + title + sort) then pages. ──
                    item(key = "folder_header") {
                        HistoryFolderHeader(
                            title = batchFolderTitle(folderEntries),
                            onBack = { openBatchKey = null },
                            selectionMode = selectionMode,
                            selectedCount = selectedTimestamps.size,
                            onSelectMode = { selectionMode = true },
                            onExitSelectMode = { exitSelectionMode() },
                            sortMode = sortMode,
                            onSortModeChange = { sortMode = it },
                            sortDescending = sortDescending,
                            onToggleSortDirection = { sortDescending = !sortDescending },
                        )
                    }
                    if (folderEntries.isEmpty()) {
                        item(key = "no_results") {
                            NoResultsItem()
                        }
                    } else {
                        items(
                            folderEntries,
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
            HistorySelectionBar(
                exporting = exporting,
                onExportZip = { exportSelected(asPdf = false) },
                onExportPdf = { exportSelected(asPdf = true) },
                onDelete = { deleteSelected() },
                onCancel = { exitSelectionMode() },
            )
        }

        if (readerPages != null && readerPages!!.isNotEmpty()) {
            MangaReaderDialog(
                pagePaths = readerPages!!,
                initialIndex = readerInitialIndex,
                targetLanguage = viewModel.settings.value.targetLanguage,
                customFontPath = viewModel.settings.value.customFontPath,
                renderStyle = viewModel.settings.value.renderStyle,
                onDismiss = { readerPages = null },
            )
        }

        pdfReaderPath?.let { path ->
            PdfReaderDialog(
                pdfPath = path,
                onDismiss = { pdfReaderPath = null },
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
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }

    confirmDeleteBatch?.let { batchEntries ->
        AlertDialog(
            onDismissRequest = { confirmDeleteBatch = null },
            title = { Text("Delete batch?") },
            text = { Text("Delete this batch (${batchEntries.size} entr${if (batchEntries.size == 1) "y" else "ies"}) from history?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteBatch(batchEntries)
                        confirmDeleteBatch = null
                        if (openBatchKey != null) openBatchKey = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteBatch = null }) { Text("Cancel") }
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
                    },
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            },
        )
    }
}

/** History header: title + selection actions, search field and sort controls. */
@Composable
private fun HistoryFilterHeader(
    selectionMode: Boolean,
    selectedCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelectMode: () -> Unit,
    onExitSelectMode: () -> Unit,
    onClearAllClick: () -> Unit,
    sortMode: HistorySortMode,
    onSortModeChange: (HistorySortMode) -> Unit,
    sortDescending: Boolean,
    onToggleSortDirection: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selectionMode) "Select ($selectedCount)" else "History",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
            )
            if (selectionMode) {
                TextButton(onClick = onExitSelectMode) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(onClick = onSelectMode) {
                    Text("Select", fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(onClick = onClearAllClick, enabled = !selectionMode) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = "Clear all history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Pill Search field
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search history...") },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(50),
        )

        // Pill Sort controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = sortMode == HistorySortMode.TIME,
                onClick = { onSortModeChange(HistorySortMode.TIME) },
                label = { Text("By Time") },
                shape = RoundedCornerShape(50),
                leadingIcon =
                    if (sortMode == HistorySortMode.TIME) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = sortMode == HistorySortMode.NAME,
                onClick = { onSortModeChange(HistorySortMode.NAME) },
                label = { Text("By Name") },
                shape = RoundedCornerShape(50),
                leadingIcon =
                    if (sortMode == HistorySortMode.NAME) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    Modifier
                        .height(24.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            )
            IconButton(onClick = onToggleSortDirection) {
                Icon(
                    if (sortDescending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    contentDescription = if (sortDescending) "Sort descending" else "Sort ascending",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Folder drill-down header: back button, folder title and the same sort controls. */
@Composable
private fun HistoryFolderHeader(
    title: String,
    onBack: () -> Unit,
    selectionMode: Boolean,
    selectedCount: Int,
    onSelectMode: () -> Unit,
    onExitSelectMode: () -> Unit,
    sortMode: HistorySortMode,
    onSortModeChange: (HistorySortMode) -> Unit,
    sortDescending: Boolean,
    onToggleSortDirection: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
            Text(
                if (selectionMode) "Select ($selectedCount)" else title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
            )
            if (selectionMode) {
                TextButton(onClick = onExitSelectMode) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(onClick = onSelectMode) {
                    Text("Select", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = sortMode == HistorySortMode.TIME,
                onClick = { onSortModeChange(HistorySortMode.TIME) },
                label = { Text("By Time") },
                shape = RoundedCornerShape(50),
                leadingIcon =
                    if (sortMode == HistorySortMode.TIME) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = sortMode == HistorySortMode.NAME,
                onClick = { onSortModeChange(HistorySortMode.NAME) },
                label = { Text("By Name") },
                shape = RoundedCornerShape(50),
                leadingIcon =
                    if (sortMode == HistorySortMode.NAME) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    Modifier
                        .height(24.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            )
            IconButton(onClick = onToggleSortDirection) {
                Icon(
                    if (sortDescending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    contentDescription = if (sortDescending) "Sort descending" else "Sort ascending",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

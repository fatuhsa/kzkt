package com.kzkt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** History header: title + selection actions, search field and sort controls. */
@Composable
internal fun HistoryFilterHeader(
    selectionMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
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
                IconButton(onClick = onToggleSelectAll) {
                    Icon(
                        if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                        contentDescription = if (allSelected) "Deselect all" else "Select all",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onExitSelectMode) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Done",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                IconButton(onClick = onSelectMode) {
                    Icon(
                        Icons.Outlined.Checklist,
                        contentDescription = "Select items",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClearAllClick) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = "Clear all history",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
internal fun HistoryFolderHeader(
    title: String,
    onBack: () -> Unit,
    selectionMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
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
                IconButton(onClick = onToggleSelectAll) {
                    Icon(
                        if (allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                        contentDescription = if (allSelected) "Deselect all" else "Select all",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onExitSelectMode) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Done",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                IconButton(onClick = onSelectMode) {
                    Icon(
                        Icons.Outlined.Checklist,
                        contentDescription = "Select items",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

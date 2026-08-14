package com.kzkt.app.ui

import com.kzkt.app.core.Config
import com.kzkt.app.data.HistoryEntry
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

// ── Formatting / naming ────────────────────────────────────────────

private val DAY_HEADER_FORMATTER by lazy { SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()) }
val TIME_FORMATTER by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }

/** Human-readable provider name for a provider key (falls back to the key). */
fun providerDisplayName(key: String): String =
    Config.PROVIDER_REGISTRY[key]?.displayName ?: key

private fun dayOf(ts: Long): LocalDate =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

// ── Filtering / grouping ───────────────────────────────────────────

/**
 * Applies search, provider/language filters and a date range to the history
 * entries, hiding tombstones in [removedIds]. Pure — shared by the History
 * screen (via remember) and unit tests.
 */
fun filterHistoryEntries(
    entries: List<HistoryEntry>,
    query: String,
    providerFilter: String?,
    languageFilter: String?,
    startMillis: Long?,
    endMillis: Long?,
    removedIds: Set<Long>,
): List<HistoryEntry> = entries.asSequence()
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

/** Group entries into day buckets with friendly headers, newest day first. */
fun groupByDay(entries: List<HistoryEntry>): List<Pair<String, List<HistoryEntry>>> {
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

// ── Book grouping / ordering ───────────────────────────────────────

/**
 * Heuristic "book" key: parent directory + file base name with any trailing
 * digits/separators stripped ("…/KZKT/chapter1_p01.png" → "…/KZKT|chapter1_p").
 * Used to group sibling pages of the same chapter in the reader; the directory
 * is included to avoid merging unrelated books that share page naming schemes.
 */
fun bookGroupKey(path: String): String {
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
fun compareNatural(a: String, b: String): Int {
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

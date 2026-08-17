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
    removedIds: Set<Long>,
    providerFilter: String? = null,
    languageFilter: String? = null,
    startMillis: Long? = null,
    endMillis: Long? = null,
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

/** Grouping key for a translation run: batchId when present, legacy heuristic otherwise. */
fun batchKeyOf(entry: HistoryEntry): String = entry.batchId.ifBlank { bookGroupKey(entry.outputPath) }

/**
 * Display title for a batch folder card: the public sub-folder name when the
 * batch was saved into one ("2026-08-17 02-46 (37 pages)"), otherwise a
 * time-based fallback (legacy / single-file runs saved to the main folder).
 */
fun batchFolderTitle(entries: List<HistoryEntry>): String {
    if (entries.isEmpty()) return "Batch"
    val parent = File(entries.first().outputPath).parentFile?.name
    return if (!parent.isNullOrBlank() && parent != "KZKT") {
        parent
    } else {
        "Batch · ${TIME_FORMATTER.format(Date(entries.maxOf { it.timestamp }))}"
    }
}

/** One translation run inside a day: label + its pages (already sorted). */
data class HistoryBatchGroup(
    val label: String,
    val entries: List<HistoryEntry>,
)

/** One day bucket: friendly header ("Today") + its translation runs. */
data class HistoryDayGroup(
    val label: String,
    val batches: List<HistoryBatchGroup>,
)

/**
 * Group entries into day buckets (newest day first), and inside each day into
 * translation runs ([HistoryEntry.batchId], or [bookGroupKey] for legacy
 * entries) so every batch gets its own separator in the list. Pages of one run
 * are already ordered by the active sort, so grouping preserves that order.
 */
fun groupByDayAndBatch(entries: List<HistoryEntry>): List<HistoryDayGroup> {
    if (entries.isEmpty()) return emptyList()
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    return entries
        .groupBy { dayOf(it.timestamp) }
        .toSortedMap(compareByDescending { it })
        .map { (day, dayEntries) ->
            val dayLabel = when (day) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> DAY_HEADER_FORMATTER.format(Date(day.toEpochDay() * 86_400_000L))
            }
            val batches =
                dayEntries
                    .groupBy(::batchKeyOf)
                    .values
                    .map { batchEntries ->
                        val failedCount = batchEntries.count { it.status == "failed" }
                        val finishedAt = batchEntries.maxOf { it.timestamp }
                        val pageWord = if (batchEntries.size == 1) "page" else "pages"
                        val label =
                            buildString {
                                append("${batchEntries.size} ", pageWord)
                                if (failedCount > 0) append(" · $failedCount failed")
                                append(" · ", TIME_FORMATTER.format(Date(finishedAt)))
                            }
                        HistoryBatchGroup(label, batchEntries)
                    }
            HistoryDayGroup(dayLabel, batches)
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

/** How the History list and reader order their entries. */
enum class HistorySortMode { TIME, NAME }

/**
 * Sort history entries for display.
 *
 * Both modes group entries by translation run (same [HistoryEntry.batchId], or
 * [bookGroupKey] for legacy entries). Pages INSIDE a run follow the mode:
 * TIME = translation order with page 1 (oldest timestamp) on top by default,
 * NAME = numeric-aware file-name order (1, 2, 10, ...). [descending] flips the
 * pages so the last page comes first — so the toggle ALWAYS visibly changes
 * the order, even when History holds a single run.
 *
 * The runs themselves are ordered by the mode: TIME = newest run first by
 * default (oldest first when [descending]), NAME = numeric-aware file name of
 * the run's first page (A-Z by default, Z-A when [descending]). Pure — shared
 * by the History screen and unit tests.
 */
fun sortHistoryEntries(
    entries: List<HistoryEntry>,
    mode: HistorySortMode,
    descending: Boolean,
): List<HistoryEntry> {
    val grouped =
        entries.groupBy { e ->
            if (e.batchId.isNotBlank()) "batch:${e.batchId}" else "book:${bookGroupKey(e.outputPath)}"
        }
    val runs =
        grouped.values.map { run ->
            val pages =
                when (mode) {
                    HistorySortMode.TIME -> run.sortedBy { it.timestamp }
                    HistorySortMode.NAME ->
                        run.sortedWith(Comparator { a, b -> compareNatural(a.fileName, b.fileName) })
                }
            Triple(
                if (descending) pages.reversed() else pages,
                run.maxOf { it.timestamp },
                pages.first().fileName,
            )
        }
    val orderedRuns =
        when (mode) {
            HistorySortMode.TIME -> if (descending) runs.sortedBy { it.second } else runs.sortedByDescending { it.second }
            HistorySortMode.NAME -> {
                val nameComparator =
                    Comparator<Triple<List<HistoryEntry>, Long, String>> { a, b ->
                        compareNatural(a.third, b.third)
                    }
                if (descending) runs.sortedWith(nameComparator.reversed()) else runs.sortedWith(nameComparator)
            }
        }
    return orderedRuns.flatMap { it.first }
}

/**
 * All pages that open together with [targetPath] in the reader, INCLUDING the
 * tapped page itself, ordered by [sortMode] (default: translation time, oldest
 * first = page 1, the newest page last — the natural reading order regardless
 * of file names). Returns the ordered list plus the index of [targetPath]
 * within it, so the reader opens on the page the user tapped.
 *
 * Pages are grouped by the translation run they belong to (same
 * [HistoryEntry.batchId]) — which works even when the source file names differ
 * — or, for entries recorded before batchId existed, pages sharing the same
 * [bookGroupKey] heuristic. PDFs are excluded (they open in the PDF reader
 * instead) and missing files are skipped.
 */
fun orderedPagesFor(
    entries: List<HistoryEntry>,
    targetPath: String,
    sortMode: HistorySortMode = HistorySortMode.TIME,
    descending: Boolean = false,
): Pair<List<String>, Int> {
    val target = entries.firstOrNull { it.outputPath == targetPath }
    val batchId = target?.batchId ?: ""
    val pages =
        entries.mapNotNull { e ->
            val p = e.outputPath
            val sameBatch = batchId.isNotBlank() && e.batchId == batchId
            val sameBook = batchId.isBlank() && bookGroupKey(p) == bookGroupKey(targetPath)
            val isSibling = !p.endsWith(".pdf", ignoreCase = true) && File(p).exists() && (sameBatch || sameBook)
            if (isSibling) e to p else null
        }
    val comparator =
        Comparator<Pair<HistoryEntry, String>> { (ea, pa), (eb, pb) ->
            val primary =
                when (sortMode) {
                    HistorySortMode.TIME -> ea.timestamp.compareTo(eb.timestamp)
                    HistorySortMode.NAME -> compareNatural(ea.fileName, eb.fileName)
                }
            if (primary != 0) primary else compareNatural(pa, pb)
        }
    val ordered =
        pages
            .sortedWith(if (descending) comparator.reversed() else comparator)
            .map { it.second }
    return ordered to ordered.indexOf(targetPath).coerceAtLeast(0)
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
            // toLongOrNull guards against digit runs that overflow Long (e.g.
            // temp-dir names like "kzkt_batch12345678901234567890"); treat those
            // as equal so ordering falls through to the next segment.
            (x.toLongOrNull() ?: 0L).compareTo(y.toLongOrNull() ?: 0L)
        } else {
            x.compareTo(y)
        }
        if (cmp != 0) return cmp
        i++
    }
    return ca.size - cb.size
}

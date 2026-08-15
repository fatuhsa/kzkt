package com.kzkt.app.ui

import com.kzkt.app.data.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class HistoryListUtilsTest {
    private fun tmpFile(
        dir: File,
        name: String,
    ): String = File(dir, name).apply { writeBytes(ByteArray(16)) }.absolutePath

    @Test
    fun `groups siblings by batchId even when names differ`() {
        val dir = createTempDirectory("kzkt_batch").toFile()
        val a = tmpFile(dir, "c.jpg") // names deliberately NOT in page order
        val b = tmpFile(dir, "a.png")
        val c = tmpFile(dir, "b.jpg")

        val entries =
            listOf(
                HistoryEntry(3, "b.jpg", c, 1, "openai", "Indonesian", inputPath = "c", status = "ok", batchId = "run1"), // newest
                HistoryEntry(2, "a.png", b, 1, "openai", "Indonesian", inputPath = "b", status = "ok", batchId = "run1"),
                HistoryEntry(1, "c.jpg", a, 1, "openai", "Indonesian", inputPath = "a", status = "ok", batchId = "run1"), // oldest = page 1
            )

        // Tap the middle page (by time) — the reader must return ALL pages ordered
        // by translation time (oldest first), with the tapped page at its index.
        val (pages, index) = orderedPagesFor(entries, b)
        assertEquals(listOf(a, b, c), pages)
        assertEquals(1, index)
    }

    @Test
    fun `does not mix pages of different batches`() {
        val dir = createTempDirectory("kzkt_mix").toFile()
        val a = tmpFile(dir, "a.jpg")
        val b = tmpFile(dir, "b.png")
        val other = tmpFile(dir, "other.png")

        val entries =
            listOf(
                HistoryEntry(1, "a.jpg", a, 1, "openai", "Indonesian", inputPath = "a", status = "ok", batchId = "run1"),
                HistoryEntry(2, "b.png", b, 1, "openai", "Indonesian", inputPath = "b", status = "ok", batchId = "run1"),
                HistoryEntry(3, "other.png", other, 1, "openai", "Indonesian", inputPath = "other", status = "ok", batchId = "run2"),
            )

        val (pages, index) = orderedPagesFor(entries, a)
        assertEquals(listOf(a, b), pages)
        assertEquals(0, index)
    }

    @Test
    fun `falls back to bookGroupKey when batchId is empty`() {
        val dir = createTempDirectory("kzkt_legacy").toFile()
        val p1 = tmpFile(dir, "page_02.png") // name order reversed on purpose
        val p2 = tmpFile(dir, "page_01.png")

        val entries =
            listOf(
                HistoryEntry(1, "page_02.png", p1, 1, "openai", "Indonesian", inputPath = "p1"), // no batchId, older
                HistoryEntry(2, "page_01.png", p2, 1, "openai", "Indonesian", inputPath = "p2"), // no batchId, newer
            )

        // Time order wins over file-name order.
        val (pages, index) = orderedPagesFor(entries, p1)
        assertEquals(listOf(p1, p2), pages)
        assertEquals(0, index)
    }

    @Test
    fun `returns only the tapped page when no siblings exist`() {
        val dir = createTempDirectory("kzkt_single").toFile()
        val a = tmpFile(dir, "a.jpg")
        val entries =
            listOf(
                HistoryEntry(1, "a.jpg", a, 1, "openai", "Indonesian", inputPath = "a", status = "ok", batchId = "run1"),
            )

        val (pages, index) = orderedPagesFor(entries, a)
        assertEquals(listOf(a), pages)
        assertEquals(0, index)
    }

    @Test
    fun `skips missing files and pdf outputs`() {
        val dir = createTempDirectory("kzkt_missing").toFile()
        val a = tmpFile(dir, "a.jpg")
        val missing = File(dir, "gone.jpg").absolutePath // does not exist on disk
        val pdf = tmpFile(dir, "book.pdf")

        val entries =
            listOf(
                HistoryEntry(1, "a.jpg", a, 1, "openai", "Indonesian", inputPath = "a", status = "ok", batchId = "run1"),
                HistoryEntry(2, "gone.jpg", missing, 1, "openai", "Indonesian", inputPath = "gone", status = "ok", batchId = "run1"),
                HistoryEntry(3, "book.pdf", pdf, 5, "openai", "Indonesian", inputPath = "pdf", status = "ok", batchId = "run1"),
            )

        val (pages, _) = orderedPagesFor(entries, a)
        assertEquals(listOf(a), pages)
        assertTrue(File(pdf).exists())
    }

    @Test
    fun `sorts reader pages by name when NAME mode is selected`() {
        val dir = createTempDirectory("kzkt_name_sort").toFile()
        val page1 = tmpFile(dir, "page_10.jpg") // name order ≠ time order
        val page2 = tmpFile(dir, "page_2.jpg")
        val page3 = tmpFile(dir, "page_1.jpg")

        val entries =
            listOf(
                // Newest by time, but page_1 by name.
                HistoryEntry(3, "page_1.jpg", page3, 1, "openai", "Indonesian", inputPath = "p3", status = "ok", batchId = "run1"),
                HistoryEntry(2, "page_10.jpg", page1, 1, "openai", "Indonesian", inputPath = "p1", status = "ok", batchId = "run1"),
                // Oldest by time, but page_2 by name.
                HistoryEntry(1, "page_2.jpg", page2, 1, "openai", "Indonesian", inputPath = "p2", status = "ok", batchId = "run1"),
            )

        // By name ascending: page_1, page_2, page_10 (numeric-aware).
        val (pages, index) = orderedPagesFor(entries, page2, HistorySortMode.NAME, descending = false)
        assertEquals(listOf(page3, page2, page1), pages)
        assertEquals(1, index)
    }

    @Test
    fun `reverses reader pages when descending is selected`() {
        val dir = createTempDirectory("kzkt_desc").toFile()
        val a = tmpFile(dir, "a.jpg")
        val b = tmpFile(dir, "b.jpg")

        val entries =
            listOf(
                HistoryEntry(1, "a.jpg", a, 1, "openai", "Indonesian", inputPath = "a", status = "ok", batchId = "run1"),
                HistoryEntry(2, "b.jpg", b, 1, "openai", "Indonesian", inputPath = "b", status = "ok", batchId = "run1"),
            )

        val (pages, _) = orderedPagesFor(entries, a, HistorySortMode.TIME, descending = true)
        assertEquals(listOf(b, a), pages)
    }

    @Test
    fun `sortHistoryEntries keeps page 1 on top per batch and flips pages when descending`() {
        val dir = createTempDirectory("kzkt_sort_list").toFile()
        val a = tmpFile(dir, "c.jpg")
        val b = tmpFile(dir, "a.jpg")
        val c = tmpFile(dir, "b.jpg")
        val e1 = HistoryEntry(1, "c.jpg", a, 1, "openai", "Indonesian", inputPath = "a", status = "ok", batchId = "run1")
        val e2 = HistoryEntry(2, "a.jpg", b, 1, "openai", "Indonesian", inputPath = "b", status = "ok", batchId = "run1")
        val e3 = HistoryEntry(3, "b.jpg", c, 1, "openai", "Indonesian", inputPath = "c", status = "ok", batchId = "run1")
        val entries = listOf(e3, e1, e2)

        // Single batch, TIME: page 1 on top by default, flipped when descending.
        assertEquals(listOf(e1, e2, e3), sortHistoryEntries(entries, HistorySortMode.TIME, descending = false))
        assertEquals(listOf(e3, e2, e1), sortHistoryEntries(entries, HistorySortMode.TIME, descending = true))
        // Single batch, NAME: page 1 (first file) on top by default, flipped when descending.
        assertEquals(listOf(e1, e2, e3), sortHistoryEntries(entries, HistorySortMode.NAME, descending = false))
        assertEquals(listOf(e3, e2, e1), sortHistoryEntries(entries, HistorySortMode.NAME, descending = true))
    }

    @Test
    fun `sortHistoryEntries orders runs by time or name with pages flipped`() {
        val dir = createTempDirectory("kzkt_sort_batches").toFile()
        val b1p1 = tmpFile(dir, "z1.jpg")
        val b1p2 = tmpFile(dir, "z2.jpg")
        val b2p1 = tmpFile(dir, "a1.jpg")
        val b2p2 = tmpFile(dir, "a2.jpg")
        // run1 finished at ts=3 (older), run2 finished at ts=8 (newer).
        val run1p1 = HistoryEntry(1, "z1.jpg", b1p1, 1, "openai", "Indonesian", inputPath = "a", status = "ok", batchId = "run1")
        val run1p2 = HistoryEntry(3, "z2.jpg", b1p2, 1, "openai", "Indonesian", inputPath = "b", status = "ok", batchId = "run1")
        val run2p1 = HistoryEntry(6, "a1.jpg", b2p1, 1, "openai", "Indonesian", inputPath = "c", status = "ok", batchId = "run2")
        val run2p2 = HistoryEntry(8, "a2.jpg", b2p2, 1, "openai", "Indonesian", inputPath = "d", status = "ok", batchId = "run2")
        val entries = listOf(run2p2, run1p1, run2p1, run1p2)

        // TIME ascending: newest run (run2) first, page 1 on top of each run.
        assertEquals(
            listOf(run2p1, run2p2, run1p1, run1p2),
            sortHistoryEntries(entries, HistorySortMode.TIME, descending = false),
        )
        // TIME descending: oldest run first AND pages flipped (last page on top).
        assertEquals(
            listOf(run1p2, run1p1, run2p2, run2p1),
            sortHistoryEntries(entries, HistorySortMode.TIME, descending = true),
        )
        // NAME ascending: run ordered by first-page name (a1... before z1...).
        assertEquals(
            listOf(run2p1, run2p2, run1p1, run1p2),
            sortHistoryEntries(entries, HistorySortMode.NAME, descending = false),
        )
        // NAME descending: runs reversed by name AND pages flipped.
        assertEquals(
            listOf(run1p2, run1p1, run2p2, run2p1),
            sortHistoryEntries(entries, HistorySortMode.NAME, descending = true),
        )
    }

    @Test
    fun `groupByDayAndBatch splits each run into its own batch group`() {
        val dir = createTempDirectory("kzkt_group").toFile()
        val p1 = tmpFile(dir, "a.jpg")
        val p2 = tmpFile(dir, "b.png")
        val q1 = tmpFile(dir, "c.jpg")
        val q2 = tmpFile(dir, "d.png")
        val now = System.currentTimeMillis()
        // run1: 2 pages finished first; run2: 2 pages finished later, same day.
        val entries =
            listOf(
                HistoryEntry(now, "a.jpg", p1, 1, "openai", "Indonesian", inputPath = "a", status = "ok", batchId = "run1"),
                HistoryEntry(now + 1, "b.png", p2, 1, "openai", "Indonesian", inputPath = "b", status = "ok", batchId = "run1"),
                HistoryEntry(now + 2, "c.jpg", q1, 1, "openai", "Indonesian", inputPath = "c", status = "ok", batchId = "run2"),
                HistoryEntry(now + 3, "d.png", q2, 1, "openai", "Indonesian", inputPath = "d", status = "failed", batchId = "run2"),
            )

        val groups = groupByDayAndBatch(entries)

        assertEquals(1, groups.size) // single day
        assertEquals("Today", groups[0].label)
        assertEquals(2, groups[0].batches.size) // two separate runs
        // run1 finished earlier → its batch comes first, pages in entry order.
        assertEquals(listOf(p1, p2), groups[0].batches[0].entries.map { it.outputPath })
        assertEquals("2 pages · ", groups[0].batches[0].label.substring(0, 10))
        // run2 finished later, includes a failed page marker in its label.
        assertEquals(listOf(q1, q2), groups[0].batches[1].entries.map { it.outputPath })
        assertTrue(groups[0].batches[1].label.contains("1 failed"))
    }

    @Test
    fun `groupByDayAndBatch falls back to bookGroupKey for legacy entries`() {
        val dir = createTempDirectory("kzkt_group_legacy").toFile()
        val p1 = tmpFile(dir, "page_1.png")
        val p2 = tmpFile(dir, "page_2.png")
        val now = System.currentTimeMillis()
        // No batchId (pre-batchId records) — same folder + numbered names must still
        // group into ONE batch via the bookGroupKey heuristic.
        val entries =
            listOf(
                HistoryEntry(now, "page_1.png", p1, 1, "openai", "Indonesian", inputPath = "a", status = "ok"),
                HistoryEntry(now + 1, "page_2.png", p2, 1, "openai", "Indonesian", inputPath = "b", status = "ok"),
            )

        val groups = groupByDayAndBatch(entries)

        assertEquals(1, groups.size)
        assertEquals(1, groups[0].batches.size) // merged into one batch
        assertEquals(2, groups[0].batches[0].entries.size)
    }
}

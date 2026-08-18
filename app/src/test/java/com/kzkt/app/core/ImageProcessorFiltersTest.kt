package com.kzkt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the detection-stage box filters that run on every page before
 * translation: false-giant removal, overlap merging and nonsense-box removal.
 * Pure IntArray logic — no Android dependencies.
 */
class ImageProcessorFiltersTest {
    // ── removeFalseGiants ──────────────────────────────────────────

    @Test
    fun `removeFalseGiants drops a giant that almost fully covers a small box`() {
        // Small 10x10 (area 100), giant 30x30 (area 900 = 9x). Giant covers 100% of small.
        val boxes = listOf(intArrayOf(0, 0, 30, 30), intArrayOf(0, 0, 10, 10))
        val result = ImageProcessor.removeFalseGiants(boxes)
        assertEquals(1, result.size)
        assertTrue(result[0].contentEquals(intArrayOf(0, 0, 10, 10)))
    }

    @Test
    fun `removeFalseGiants keeps both when the small box is not covered by 80 percent`() {
        // Giant overlaps the small box only partially (25/100 px overlap).
        val boxes = listOf(intArrayOf(5, 5, 35, 35), intArrayOf(0, 0, 10, 10))
        val result = ImageProcessor.removeFalseGiants(boxes)
        assertEquals(2, result.size)
    }

    @Test
    fun `removeFalseGiants keeps both when area ratio is not above 6x`() {
        // 20x20 (400) vs 30x30 (900) -> ratio 2.25, no giant relation.
        val boxes = listOf(intArrayOf(0, 0, 30, 30), intArrayOf(0, 0, 20, 20))
        val result = ImageProcessor.removeFalseGiants(boxes)
        assertEquals(2, result.size)
    }

    @Test
    fun `removeFalseGiants keeps disjoint boxes`() {
        val boxes = listOf(intArrayOf(0, 0, 30, 30), intArrayOf(100, 100, 110, 110))
        val result = ImageProcessor.removeFalseGiants(boxes)
        assertEquals(2, result.size)
    }

    // ── mergeOverlapping ───────────────────────────────────────────

    @Test
    fun `mergeOverlapping merges a fully contained box into the outer one`() {
        val boxes = listOf(intArrayOf(10, 10, 90, 90), intArrayOf(0, 0, 100, 100))
        val result = ImageProcessor.mergeOverlapping(boxes)
        assertEquals(1, result.size)
        assertTrue(result[0].contentEquals(intArrayOf(0, 0, 100, 100)))
    }

    @Test
    fun `mergeOverlapping merges partially overlapping boxes`() {
        val boxes = listOf(intArrayOf(0, 0, 100, 100), intArrayOf(50, 0, 150, 100))
        val result = ImageProcessor.mergeOverlapping(boxes)
        assertEquals(1, result.size)
        assertTrue(result[0].contentEquals(intArrayOf(0, 0, 150, 100)))
    }

    @Test
    fun `mergeOverlapping keeps disjoint boxes separate`() {
        val boxes = listOf(intArrayOf(0, 0, 100, 100), intArrayOf(200, 200, 300, 300))
        val result = ImageProcessor.mergeOverlapping(boxes)
        assertEquals(2, result.size)
    }

    // ── removeNonsense ─────────────────────────────────────────────

    @Test
    fun `removeNonsense keeps a normal speech bubble`() {
        val result = ImageProcessor.removeNonsense(listOf(intArrayOf(10, 10, 110, 110)), 1000, 1000)
        assertEquals(1, result.size)
    }

    @Test
    fun `removeNonsense drops an overly wide box`() {
        // 400x100 (ratio 4.0) on a 1000-wide image -> too wide.
        val result = ImageProcessor.removeNonsense(listOf(intArrayOf(0, 0, 400, 100)), 1000, 1000)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `removeNonsense drops an overly flat box`() {
        // 600x100 on 1000x1000: >= 50% width, <= 16% height.
        val result = ImageProcessor.removeNonsense(listOf(intArrayOf(0, 0, 600, 100)), 1000, 1000)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `removeNonsense drops an overly thin box but keeps normal ones`() {
        val boxes =
            listOf(
                intArrayOf(0, 0, 500, 150), // thin: 75000 px on 2000x1000, ratio 3.33
                intArrayOf(10, 10, 110, 110), // normal
            )
        val result = ImageProcessor.removeNonsense(boxes, 2000, 1000)
        assertEquals(1, result.size)
        assertTrue(result[0].contentEquals(intArrayOf(10, 10, 110, 110)))
    }
}

package com.kzkt.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for free-text region merging (the "one big box for nearby text" behavior).
 * Pure JVM: only touches IntArray box math, no Android/OpenCV calls.
 */
class ImageProcessorMergeTest {

    private fun merge(vararg boxes: IntArray): List<IntArray> =
        ImageProcessor.mergeNearbyTextBoxes(boxes.toList())

    @Test
    fun `same row adjacent boxes merge into one wide box`() {
        // Two words on the same line, 20px apart, both 100px tall.
        val merged = merge(
            intArrayOf(0, 0, 100, 100),
            intArrayOf(120, 0, 250, 100),
        )
        assertEquals(1, merged.size)
        assertArrayEquals(intArrayOf(0, 0, 250, 100), merged[0])
    }

    @Test
    fun `stacked caption lines merge into one tall box`() {
        // Two lines of a caption box, 15px vertical gap, same x range.
        val merged = merge(
            intArrayOf(50, 50, 350, 100),
            intArrayOf(50, 115, 350, 165),
        )
        assertEquals(1, merged.size)
        assertArrayEquals(intArrayOf(50, 50, 350, 165), merged[0])
    }

    @Test
    fun `far apart boxes stay separate`() {
        // Same row but a 400px gap — clearly unrelated text.
        val merged = merge(
            intArrayOf(0, 0, 100, 100),
            intArrayOf(500, 0, 620, 100),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `vertically distant boxes stay separate`() {
        // Two captions with a large vertical gap.
        val merged = merge(
            intArrayOf(50, 50, 350, 100),
            intArrayOf(50, 400, 350, 450),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `chain merge collapses three nearby boxes into one`() {
        // A merges with B (gap 20), B's space merges with C — all one box.
        val merged = merge(
            intArrayOf(0, 0, 100, 100),
            intArrayOf(120, 0, 220, 100),
            intArrayOf(240, 0, 340, 100),
        )
        assertEquals(1, merged.size)
        assertArrayEquals(intArrayOf(0, 0, 340, 100), merged[0])
    }

    @Test
    fun `single box is returned unchanged`() {
        val merged = merge(intArrayOf(10, 20, 110, 80))
        assertEquals(1, merged.size)
        assertArrayEquals(intArrayOf(10, 20, 110, 80), merged[0])
    }

    @Test
    fun `overlapping boxes always merge`() {
        val merged = merge(
            intArrayOf(0, 0, 120, 100),
            intArrayOf(60, 20, 200, 140),
        )
        assertEquals(1, merged.size)
        assertArrayEquals(intArrayOf(0, 0, 200, 140), merged[0])
    }
}

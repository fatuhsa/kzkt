package com.kzkt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the shared box geometry helpers used by both ImageProcessor
 * (detection dedup) and ResultRenderer (render skip checks). Pure logic,
 * no Android dependencies.
 */
class BoxGeometryTest {
    // ── dimensions & area ──────────────────────────────────────────

    @Test
    fun `dimensions clamp degenerate boxes to at least 1`() {
        val box = intArrayOf(100, 100, 50, 50) // inverted
        assertEquals(1, BoxGeometry.boxWidth(box))
        assertEquals(1, BoxGeometry.boxHeight(box))
    }

    @Test
    fun `areaBox is zero for inverted boxes and positive for normal ones`() {
        assertEquals(0, BoxGeometry.areaBox(intArrayOf(100, 100, 50, 50)))
        assertEquals(10000, BoxGeometry.areaBox(intArrayOf(0, 0, 100, 100)))
    }

    @Test
    fun `areaRatio guards against zero total area`() {
        // totalArea 0 is clamped to 1 to avoid division by zero (ratio can exceed 1)
        assertEquals(10000.0, BoxGeometry.areaRatio(intArrayOf(0, 0, 100, 100), 0), 0.0001)
        assertEquals(0.5, BoxGeometry.areaRatio(intArrayOf(0, 0, 100, 100), 20000), 0.0001)
    }

    // ── intersection / IoU ─────────────────────────────────────────

    @Test
    fun `rectIou is one for identical boxes`() {
        val box = intArrayOf(10, 10, 110, 110)
        assertEquals(1.0, BoxGeometry.rectIou(box, box), 0.0001)
    }

    @Test
    fun `rectIou is zero for disjoint boxes`() {
        val a = intArrayOf(0, 0, 10, 10)
        val b = intArrayOf(20, 20, 30, 30)
        assertEquals(0.0, BoxGeometry.rectIou(a, b), 0.0001)
    }

    @Test
    fun `rectIou computes partial overlap`() {
        // 100x100 boxes, half overlap horizontally -> IoU = 1/3
        val a = intArrayOf(0, 0, 100, 100)
        val b = intArrayOf(50, 0, 150, 100)
        assertEquals(1.0 / 3.0, BoxGeometry.rectIou(a, b), 0.001)
    }

    // ── shouldMerge ────────────────────────────────────────────────

    @Test
    fun `shouldMerge is false when boxes do not overlap`() {
        val a = intArrayOf(0, 0, 100, 100)
        val b = intArrayOf(200, 200, 300, 300)
        assertFalse(BoxGeometry.shouldMerge(a, b))
    }

    @Test
    fun `shouldMerge is true when IoU is high`() {
        // 100x100 boxes overlapping by half -> IoU 0.33 >= 0.20
        val a = intArrayOf(0, 0, 100, 100)
        val b = intArrayOf(50, 0, 150, 100)
        assertTrue(BoxGeometry.shouldMerge(a, b))
    }

    @Test
    fun `shouldMerge is true when the small box is mostly covered`() {
        // Tiny box fully inside a big one: low IoU but 100% coverage of the small box
        val big = intArrayOf(0, 0, 100, 100)
        val small = intArrayOf(10, 10, 20, 20)
        assertTrue(BoxGeometry.shouldMerge(small, big))
    }

    @Test
    fun `shouldMerge is false for a small overlap that does not reach thresholds`() {
        val a = intArrayOf(0, 0, 100, 100)
        val b = intArrayOf(90, 90, 190, 190)
        assertFalse(BoxGeometry.shouldMerge(a, b))
    }

    @Test
    fun `shouldMerge is false when either box has zero area`() {
        val degenerate = intArrayOf(50, 50, 50, 50)
        val normal = intArrayOf(0, 0, 100, 100)
        assertFalse(BoxGeometry.shouldMerge(degenerate, normal))
    }

    // ── nonsense box heuristics ────────────────────────────────────

    @Test
    fun `normal speech bubble is not a nonsense box`() {
        val box = intArrayOf(10, 10, 110, 110)
        assertFalse(BoxGeometry.isNonsenseBox(box, 1000, 1000))
    }

    @Test
    fun `overly wide box is rejected`() {
        // 400x100 (ratio 4.0) on a 1000-wide image -> too wide
        val box = intArrayOf(0, 0, 400, 100)
        assertTrue(BoxGeometry.isNonsenseBox(box, 1000, 1000))
    }

    @Test
    fun `wide box below the width threshold is kept`() {
        // ratio 4.0 but only 100px wide on a 1000px image (< 35% width)
        val box = intArrayOf(0, 0, 100, 25)
        assertFalse(BoxGeometry.isNonsenseBox(box, 1000, 1000))
    }

    @Test
    fun `overly flat box is rejected`() {
        // 600x100 on a 1000x1000 image: >= 50% width, <= 16% height
        val box = intArrayOf(0, 0, 600, 100)
        assertTrue(BoxGeometry.isNonsenseBox(box, 1000, 1000))
    }

    @Test
    fun `overly thin box is rejected`() {
        // 500x150 = 75000 px on a 2000x1000 image (>= 3.5% area), ratio 3.33
        val box = intArrayOf(0, 0, 500, 150)
        assertTrue(BoxGeometry.isNonsenseBox(box, 2000, 1000))
    }
}

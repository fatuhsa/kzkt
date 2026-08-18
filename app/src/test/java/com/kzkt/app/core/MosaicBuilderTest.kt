package com.kzkt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for MosaicBuilder's pure batch-partitioning logic. chunkCrops only reads
 * CropItem.id, so an uninitialized Bitmap instance is safe (same technique as
 * BatchTranslationPlannerTest).
 */
class MosaicBuilderTest {
    private fun crop(id: String): MosaicBuilder.CropItem {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = Class.forName("sun.misc.Unsafe").getMethod("allocateInstance", Class::class.java)
        val bmp = allocate.invoke(unsafe, android.graphics.Bitmap::class.java) as android.graphics.Bitmap
        return MosaicBuilder.CropItem(id, bmp)
    }

    @Test
    fun `chunkCrops empty list returns empty`() {
        assertTrue(MosaicBuilder.chunkCrops(emptyList()).isEmpty())
    }

    @Test
    fun `chunkCrops splits by maxPerChunk preserving order`() {
        val crops = (1..25).map { crop("$it") }
        val chunks = MosaicBuilder.chunkCrops(crops, maxPerChunk = 10)
        assertEquals(3, chunks.size)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), chunks[0].map { it.id })
        assertEquals(listOf("11", "12", "13", "14", "15", "16", "17", "18", "19", "20"), chunks[1].map { it.id })
        assertEquals(listOf("21", "22", "23", "24", "25"), chunks[2].map { it.id })
    }

    @Test
    fun `chunkCrops returns single chunk when size fits`() {
        val crops = (1..5).map { crop("$it") }
        val chunks = MosaicBuilder.chunkCrops(crops, maxPerChunk = 10)
        assertEquals(1, chunks.size)
        assertEquals(5, chunks[0].size)
    }

    @Test
    fun `chunkCrops uses default 20 when maxPerChunk is not positive`() {
        val crops = (1..45).map { crop("$it") }
        // 45 items with default 20 -> 3 chunks (20, 20, 5)
        assertEquals(3, MosaicBuilder.chunkCrops(crops, maxPerChunk = 0).size)
        assertEquals(3, MosaicBuilder.chunkCrops(crops, maxPerChunk = -5).size)
    }
}

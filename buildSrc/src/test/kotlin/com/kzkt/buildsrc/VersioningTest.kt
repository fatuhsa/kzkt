package com.kzkt.buildsrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersioningTest {

    @Test
    fun `three segment versions map to derived codes`() {
        assertEquals(13500000, Versioning.deriveVersionCode("1.35.0"))
        assertEquals(12502000, Versioning.deriveVersionCode("1.25.2"))
        assertEquals(10203004, Versioning.deriveVersionCode("1.2.3.4"))
    }

    @Test
    fun `four segment versions include the build segment`() {
        assertEquals(12501022, Versioning.deriveVersionCode("1.25.1.22"))
        assertEquals(12501023, Versioning.deriveVersionCode("1.25.1.23"))
    }

    @Test
    fun `versionCode is strictly increasing across sequential releases`() {
        val codes = listOf("1.34.0", "1.35.0", "1.35.1", "1.36.0")
            .map { Versioning.deriveVersionCode(it) }
        assertEquals(codes, codes.sorted())
        assertEquals(codes.toSet().size, codes.size)
    }

    @Test
    fun `non numeric segments degrade to zero instead of crashing`() {
        assertEquals(13500000, Versioning.deriveVersionCode("1.35.0-debug"))
        assertEquals(0, Versioning.deriveVersionCode(""))
    }

    @Test
    fun `newer release always has higher code than installed`() {
        val installed = Versioning.deriveVersionCode("1.35.0")
        assertTrue(Versioning.deriveVersionCode("1.35.1") > installed)
        assertTrue(Versioning.deriveVersionCode("1.36.0") > installed)
        assertTrue(Versioning.deriveVersionCode("2.0.0") > installed)
    }
}

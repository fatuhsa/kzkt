package com.kzkt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure batch decision logic extracted from TranslationPipeline.
 * These cover the exact classes of bugs found on-device: provider responses
 * that drop page prefixes or rewrite ids, and free-text regions silently
 * skipped at render.
 */
class BatchTranslationPlannerTest {
    // The planner only ever reads CropItem.id, so a bitmap whose methods are
    // never called is safe here. allocateInstance skips the (unmockable)
    // Bitmap constructor entirely.
    private fun crop(id: String): MosaicBuilder.CropItem {
        // allocateInstance via reflection skips the (unmockable) Bitmap
        // constructor; the planner only reads CropItem.id so no method is ever
        // called on the bitmap.
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = Class.forName("sun.misc.Unsafe").getMethod("allocateInstance", Class::class.java)
        val bmp = allocate.invoke(unsafe, android.graphics.Bitmap::class.java) as android.graphics.Bitmap
        return MosaicBuilder.CropItem(id, bmp)
    }

    // ── normalizeTranslations ──────────────────────────────────────

    @Test
    fun `normalize keeps page-prefixed ids verbatim`() {
        val raw = mapOf("1_ft1" to "Hello", "2_3" to "World")
        val n = BatchTranslationPlanner.normalizeTranslations(raw)
        assertEquals("Hello", n.byId["1_ft1"])
        assertEquals("World", n.byId["2_3"])
        // Page-prefixed free-text ids also register a bare "ftN" fallback.
        assertEquals("Hello", n.bareFtLookup["ft1"])
    }

    @Test
    fun `normalize strips page prefix into bare ft fallback`() {
        // The classic bug: model returns "ft1" instead of "1_ft1".
        val raw = mapOf("ft1" to "Hello")
        val n = BatchTranslationPlanner.normalizeTranslations(raw)
        assertEquals("Hello", n.byId["ft1"])
        assertEquals("Hello", n.bareFtLookup["ft1"])
    }

    @Test
    fun `normalize keeps first bare ft and prefers page-prefixed`() {
        val raw = mapOf("ft1" to "first", "1_ft1" to "canonical")
        val n = BatchTranslationPlanner.normalizeTranslations(raw)
        assertEquals("canonical", n.byId["1_ft1"])
        // Bare form is only a fallback; first occurrence wins.
        assertEquals("first", n.bareFtLookup["ft1"])
    }

    @Test
    fun `normalize drops unparseable keys`() {
        val n = BatchTranslationPlanner.normalizeTranslations(mapOf("garbage" to "x", "1_2" to "y"))
        assertEquals("y", n.byId["1_2"])
        assertTrue(n.byId.keys.none { it == "garbage" })
    }

    // ── missingEchoedIds ───────────────────────────────────────────

    @Test
    fun `missing ids are only those sent but not translated`() {
        val chunk = listOf(crop("1_1"), crop("1_2"), crop("1_3"), crop("1_4"))
        val sentIds = setOf("1_1", "1_2", "1_3", "1_4")
        val translated = mapOf("1_1" to "a", "1_3" to "c")
        val missing = BatchTranslationPlanner.missingEchoedIds(chunk, sentIds, translated)
        assertEquals(listOf("1_2", "1_4"), missing.map { it.id })
    }

    @Test
    fun `no-text bubbles never sent are not reported missing`() {
        // 1_2 was OCR'd as no-text, so it was never part of the request.
        val chunk = listOf(crop("1_1"), crop("1_2"), crop("1_3"))
        val sentIds = setOf("1_1", "1_3")
        val translated = mapOf("1_1" to "a")
        val missing = BatchTranslationPlanner.missingEchoedIds(chunk, sentIds, translated)
        assertEquals(listOf("1_3"), missing.map { it.id })
    }

    @Test
    fun `rewritten id with trailing punctuation still counts as translated`() {
        // Model rewrote "1_1" as "1_1." — hasTranslation normalizes both sides.
        val chunk = listOf(crop("1_1"))
        val sentIds = setOf("1_1")
        val translated = mapOf("1_1." to "a")
        val missing = BatchTranslationPlanner.missingEchoedIds(chunk, sentIds, translated)
        assertTrue(missing.isEmpty())
    }

    // ── resolvePageTranslations ────────────────────────────────────

    @Test
    fun `page resolves bubbles directly and free text via bare fallback`() {
        val normalized =
            BatchTranslationPlanner.normalizeTranslations(
                mapOf("1_2" to "World", "ft1" to "Hello"),
            )
        // Page 1 has bubble 1_2 and free-text 1_ft1; the model dropped the prefix.
        val resolved = BatchTranslationPlanner.resolvePageTranslations(setOf("1_2", "1_ft1"), normalized)
        assertEquals("World", resolved["1_2"])
        assertEquals("Hello", resolved["1_ft1"])
    }

    @Test
    fun `untranslated ids are simply absent from the result`() {
        val normalized = BatchTranslationPlanner.normalizeTranslations(mapOf("1_1" to "a"))
        val resolved = BatchTranslationPlanner.resolvePageTranslations(setOf("1_1", "1_2"), normalized)
        assertEquals(mapOf("1_1" to "a"), resolved)
    }

    // ── freeTextMatchCounts ────────────────────────────────────────

    @Test
    fun `counts matched vs total free text`() {
        val normalized =
            BatchTranslationPlanner.normalizeTranslations(
                mapOf("ft1" to "a", "2_2" to "b"),
            )
        val resolved = BatchTranslationPlanner.resolvePageTranslations(setOf("2_ft1", "2_ft2", "2_2"), normalized)
        val (matched, total) = BatchTranslationPlanner.freeTextMatchCounts(setOf("2_ft1", "2_ft2", "2_2"), resolved)
        assertEquals(1, matched)
        assertEquals(2, total)
    }
}

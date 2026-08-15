package com.kzkt.app.core

import android.graphics.Bitmap
import com.kzkt.app.core.providers.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the provider-chain core extracted from TranslationPipeline.
 * Pure JVM: uses a fake LlmProvider and RateLimiter(0) so no Android
 * bitmap or network access is needed.
 */
class ChunkTranslatorTest {

    /** Deterministic fake provider: returns a canned response or throws on demand. */
    /** Fake whose repair call (2nd translateText) returns valid JSON. */
    private class RepairProvider(
        override val providerName: String,
        private val broken: String,
        private val fixed: String,
    ) : LlmProvider {
        override val apiKey: String = "test-key"
        override val modelName: String = "test-model"
        var calls = 0

        override suspend fun translateImage(image: Bitmap, prompt: String): String? {
            calls++
            return broken
        }

        override suspend fun translateText(textJson: String, prompt: String): String? {
            calls++
            return if (calls == 1) broken else fixed
        }
    }

    private class FakeProvider(
        override val providerName: String,
        private val response: String? = null,
        private val error: Exception? = null,
    ) : LlmProvider {
        override val apiKey: String = "test-key"
        override val modelName: String = "test-model"
        var imageCalls = 0
        var textCalls = 0

        override suspend fun translateImage(image: Bitmap, prompt: String): String? {
            imageCalls++
            error?.let { throw it }
            return response
        }

        override suspend fun translateText(textJson: String, prompt: String): String? {
            textCalls++
            error?.let { throw it }
            return response
        }
    }

    private fun translator(
        provider: LlmProvider,
        fallbacks: List<LlmProvider> = emptyList(),
        isCancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ) = ChunkTranslator(
        provider = provider,
        fallbackProviders = fallbacks,
        rateLimiter = RateLimiter(0),
        targetLanguage = "Indonesian",
        cacheRepo = null,
        glossary = emptyMap(),
        params = Config.TweakParams(),
        onProgress = onProgress,
        isCancelled = isCancelled,
    )

    private suspend fun runChain(
        provider: LlmProvider,
        fallbacks: List<LlmProvider> = emptyList(),
        isCancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): Pair<Boolean, MutableMap<String, String>> {
        val allTranslations = mutableMapOf<String, String>()
        val ok = translator(provider, fallbacks, isCancelled, onProgress).translateWithProviders(
            request = { prov -> prov.translateText("{\"1\":\"x\"}", "prompt") },
            cropItems = emptyList(),
            allTranslations = allTranslations,
        )
        return ok to allTranslations
    }

    // ── normalizeIdKey ─────────────────────────────────────────────

    @Test
    fun `normalizeIdKey keeps plain and suffixed numeric ids`() {
        assertEquals("1", ChunkTranslator.normalizeIdKey("1"))
        assertEquals("1_2", ChunkTranslator.normalizeIdKey("1_2"))
        assertEquals("123_45", ChunkTranslator.normalizeIdKey("123_45"))
    }

    @Test
    fun `normalizeIdKey keeps leading underscore keys untouched`() {
        assertEquals("_note", ChunkTranslator.normalizeIdKey("_note"))
        assertEquals("_1", ChunkTranslator.normalizeIdKey("_1"))
    }

    @Test
    fun `normalizeIdKey extracts ids from prefixed and wrapped strings`() {
        assertEquals("7", ChunkTranslator.normalizeIdKey("ID 7"))
        assertEquals("3", ChunkTranslator.normalizeIdKey("bubble_3"))
        assertEquals("5", ChunkTranslator.normalizeIdKey("5."))
        assertEquals("9", ChunkTranslator.normalizeIdKey("\"9\""))
    }

    @Test
    fun `normalizeIdKey returns null for non-numeric keys`() {
        assertNull(ChunkTranslator.normalizeIdKey(""))
        assertNull(ChunkTranslator.normalizeIdKey("hello"))
        assertNull(ChunkTranslator.normalizeIdKey("abc_def"))
    }

    @Test
    fun `normalizeIdKey passes through free-text ids`() {
        assertEquals("ft1", ChunkTranslator.normalizeIdKey("ft1"))
        assertEquals("2_ft1", ChunkTranslator.normalizeIdKey("2_ft1"))
        assertEquals("7_ft3", ChunkTranslator.normalizeIdKey("7_ft3"))
        assertEquals("ft12", ChunkTranslator.normalizeIdKey("ft12"))
    }

    @Test
    fun `isFreeTextId detects free-text regions only`() {
        assertTrue(ChunkTranslator.isFreeTextId("ft1"))
        assertTrue(ChunkTranslator.isFreeTextId("2_ft1"))
        assertTrue(ChunkTranslator.isFreeTextId("0_ft1"))
        assertFalse(ChunkTranslator.isFreeTextId("1"))
        assertFalse(ChunkTranslator.isFreeTextId("2_1"))
        assertFalse(ChunkTranslator.isFreeTextId("_note"))
        assertFalse(ChunkTranslator.isFreeTextId(""))
    }

    // ── translateWithProviders core loop ───────────────────────────

    @Test
    fun `primary success merges translations and skips fallbacks`() = runBlocking {
        val primary = FakeProvider("Primary", response = """{"1":"halo","2_1":"selamat"}""")
        val fallback = FakeProvider("Fallback", response = """{"1":"fallback"}""")

        val (ok, translations) = runChain(primary, listOf(fallback))

        assertTrue(ok)
        assertEquals("halo", translations["1"])
        assertEquals("selamat", translations["2_1"])
        assertEquals(1, primary.textCalls)
        assertEquals(0, fallback.textCalls)
    }

    @Test
    fun `unparseable primary output falls through to fallback`() = runBlocking {
        val primary = FakeProvider("Primary", response = "not json at all")
        val fallback = FakeProvider("Fallback", response = """{"1":"dari fallback"}""")
        val progress = mutableListOf<String>()

        val (ok, translations) = runChain(primary, listOf(fallback), onProgress = { progress.add(it) })

        assertTrue(ok)
        assertEquals("dari fallback", translations["1"])
        // Initial call + one JSON-repair attempt (which returns the same broken text).
        assertEquals(2, primary.textCalls)
        assertEquals(1, fallback.textCalls)
        assertTrue(progress.any { it.contains("unparseable") })
    }

    @Test
    fun `unparseable primary output is repaired by the same provider`() = runBlocking {
        val primary = RepairProvider("Primary", broken = "bukan json", fixed = """{"1":"diperbaiki"}""")
        val fallback = FakeProvider("Fallback", response = """{"1":"fallback"}""")
        val progress = mutableListOf<String>()

        val (ok, translations) = runChain(primary, listOf(fallback), onProgress = { progress.add(it) })

        assertTrue(ok)
        assertEquals("diperbaiki", translations["1"])
        assertEquals(2, primary.calls)
        assertEquals(0, fallback.textCalls)
        assertTrue(progress.any { it.contains("repair") })
    }

    @Test
    fun `null response from primary falls through to fallback`() = runBlocking {
        val primary = FakeProvider("Primary", response = null)
        val fallback = FakeProvider("Fallback", response = """{"1":"ok"}""")

        val (ok, translations) = runChain(primary, listOf(fallback))

        assertTrue(ok)
        assertEquals("ok", translations["1"])
        assertEquals(1, primary.textCalls)
        assertEquals(1, fallback.textCalls)
    }

    @Test
    fun `all providers failing returns false and logs failover`() = runBlocking {
        val primary = FakeProvider("Primary", error = IllegalStateException("boom"))
        val fallback = FakeProvider("Fallback", error = IllegalStateException("boom"))
        val progress = mutableListOf<String>()

        val (ok, translations) = runChain(primary, listOf(fallback), onProgress = { progress.add(it) })

        assertFalse(ok)
        assertTrue(translations.isEmpty())
        // Both providers fail, so each logs its own failover line.
        assertEquals(2, progress.count { it.contains("Trying fallback provider") })
        assertEquals(2, progress.count { it.contains("failed (boom)") })
    }

    @Test
    fun `cancellation stops the chain without calling providers`() = runBlocking {
        val primary = FakeProvider("Primary", response = """{"1":"x"}""")

        val (ok, translations) = runChain(primary, isCancelled = { true })

        assertFalse(ok)
        assertTrue(translations.isEmpty())
        assertEquals(0, primary.textCalls)
    }

    @Test
    fun `cancellation inside provider call propagates`() = runBlocking {
        val primary = FakeProvider("Primary", error = CancellationException("stopped"))
        val fallback = FakeProvider("Fallback", response = """{"1":"x"}""")

        val thrown = try {
            runChain(primary, listOf(fallback))
            null
        } catch (e: CancellationException) {
            e
        }

        assertTrue(thrown is CancellationException)
        assertEquals(0, fallback.textCalls)
    }

    @Test
    fun `empty translations from successful provider treated as failure`() = runBlocking {
        val primary = FakeProvider("Primary", response = """{}""")
        val fallback = FakeProvider("Fallback", response = """{"1":"ok"}""")

        val (ok, translations) = runChain(primary, listOf(fallback))

        assertTrue(ok)
        assertEquals("ok", translations["1"])
    }

    @Test
    fun `translateOcrChunk with empty chunk returns empty result`() = runBlocking {
        val provider = FakeProvider("Primary", response = """{"1":"x"}""")
        val result = translator(provider).translateOcrChunk(
            chunk = emptyList(),
            cropItems = emptyList(),
            allTranslations = mutableMapOf(),
            rawTexts = mutableMapOf(),
            textPrompt = { "prompt: $it" },
        )

        assertTrue(result.ocrMap.isEmpty())
        assertFalse(result.translated)
        assertEquals(0, provider.textCalls)
    }
}

package com.kzkt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {
    @Test
    fun `language codes map correctly`() {
        assertEquals("en", Config.getLangCode("English"))
        assertEquals("id", Config.getLangCode("Indonesian"))
        assertEquals("jp", Config.getLangCode("Japanese"))
        assertEquals("kr", Config.getLangCode("Korean"))
        assertEquals("cn", Config.getLangCode("Mandarin"))
        assertEquals("cn", Config.getLangCode("chinese (simplified)"))
        assertEquals("th", Config.getLangCode("Thai"))
        assertEquals("jv", Config.getLangCode("Javanese"))
    }

    @Test
    fun `unknown language falls back to first two chars lowercase`() {
        assertEquals("xy", Config.getLangCode("Xylophone"))
        assertEquals("en", Config.getLangCode("  English  "))
    }

    @Test
    fun `every provider in registry has a factory`() {
        for (key in Config.PROVIDER_REGISTRY.keys) {
            assertNotNull(
                "Provider '$key' should be creatable",
                com.kzkt.app.core.providers.ProviderFactory
                    .create(key, "key", "model", ""),
            )
        }
    }

    @Test
    fun `every language choice has a translation example`() {
        for (lang in Config.LANGUAGE_CHOICES) {
            assertTrue(
                "Language '$lang' should have a prompt example",
                Constants.TRANSLATION_EXAMPLES.containsKey(lang.lowercase()),
            )
        }
    }

    @Test
    fun `preset models never include empty model names`() {
        Config.PRESET_MODELS.forEach { (_, models) ->
            assertTrue(models.isNotEmpty())
            models.forEach { assertTrue(it.isNotBlank()) }
        }
    }
}

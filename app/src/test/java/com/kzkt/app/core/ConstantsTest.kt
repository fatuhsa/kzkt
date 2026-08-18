package com.kzkt.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstantsTest {
    @Test
    fun `prompt embeds target language and example translations`() {
        val prompt = Constants.buildPrompt("Indonesian")
        assertTrue(prompt.contains("to Indonesian"))
        assertTrue(prompt.contains("Cepat bangun!")) // example value 1
        assertTrue(prompt.contains("Ibu... tunggu...")) // example value 3
    }

    @Test
    fun `glossary rules are injected when glossary provided`() {
        val prompt =
            Constants.buildPrompt(
                "English",
                glossary = mapOf("Roronoa Zoro" to "Roronoa Zoro", "Bankai" to "Final Release"),
            )
        assertTrue(prompt.contains("Bankai"))
        assertTrue(prompt.contains("GLOSSARY RULES"))
    }

    @Test
    fun `prompt without glossary omits glossary section`() {
        val prompt = Constants.buildPrompt("English")
        assertFalse(prompt.contains("GLOSSARY RULES"))
    }

    @Test
    fun `sfx mode switches skip vs translate instruction`() {
        val skip = Constants.buildPrompt("English", translateSfx = false)
        val translate = Constants.buildPrompt("English", translateSfx = true)

        assertTrue(skip.contains("reply with 'SKIP'"))
        assertFalse(skip.contains("SFX ARE translatable"))

        assertTrue(translate.contains("Sound effects (SFX) ARE translatable"))
        assertFalse(translate.contains("If a bubble contains ONLY sound effects (SFX) with no dialogue, reply with 'SKIP'"))
    }

    @Test
    fun `prompt requires returning all ids as json`() {
        val prompt = Constants.buildPrompt("Japanese")
        assertTrue(prompt.contains("You MUST return a JSON entry for EVERY red ID"))
        assertTrue(prompt.contains("OUTPUT FORMAT"))
        assertTrue(prompt.contains("Example output"))
    }
}

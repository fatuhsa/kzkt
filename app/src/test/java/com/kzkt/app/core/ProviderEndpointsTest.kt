package com.kzkt.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderEndpointsTest {
    @Test
    fun geminiPlainBaseGetsV1BetaModels() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            modelsEndpointFor("gemini", "https://generativelanguage.googleapis.com"),
        )
    }

    @Test
    fun geminiWithV1BetaStaysV1Beta() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            modelsEndpointFor("gemini", "https://generativelanguage.googleapis.com/v1beta"),
        )
    }

    @Test
    fun geminiWithV1SuffixIsNormalized() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            modelsEndpointFor("gemini", "https://generativelanguage.googleapis.com/v1"),
        )
    }

    @Test
    fun openAiStyleBaseGetsV1Models() {
        assertEquals("https://api.openai.com/v1/models", modelsEndpointFor("openai", "https://api.openai.com"))
    }

    @Test
    fun openAiWithV1SuffixStaysV1Models() {
        assertEquals("https://api.openai.com/v1/models", modelsEndpointFor("openai", "https://api.openai.com/v1"))
    }

    @Test
    fun chatCompletionsSuffixIsStripped() {
        assertEquals(
            "https://api.openai.com/v1/models",
            modelsEndpointFor("openai", "https://api.openai.com/v1/chat/completions"),
        )
    }

    @Test
    fun trailingSlashIsTrimmed() {
        assertEquals("https://host/v1/models", modelsEndpointFor("openai", "https://host/"))
    }

    @Test
    fun anthropicUsesSameShapeAsOpenAiCompat() {
        assertEquals("https://api.anthropic.com/v1/models", modelsEndpointFor("anthropic", "https://api.anthropic.com"))
    }

    @Test
    fun customProviderUsesV1Models() {
        assertEquals("https://my-llm.example.com/v1/models", modelsEndpointFor("custom", "https://my-llm.example.com"))
    }
}

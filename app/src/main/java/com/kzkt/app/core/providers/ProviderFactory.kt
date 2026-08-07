package com.kzkt.app.core.providers

/**
 * Central factory for building provider instances. Shared by the translation
 * worker (primary + fallback chains) and the Settings "Test API Key" health
 * check so provider construction stays in exactly one place.
 */
object ProviderFactory {

    fun create(
        providerKey: String,
        apiKey: String,
        modelName: String,
        baseUrl: String,
        customTimeoutSec: Int = 30,
    ): LlmProvider? {
        return when (providerKey) {
            "gemini" -> GeminiProvider(apiKey, modelName, baseUrl)
            "openai" -> OpenAIProvider(apiKey, modelName, baseUrl)
            "openrouter" -> OpenRouterProvider(apiKey, modelName, baseUrl)
            "zen" -> ZenProvider(apiKey, modelName, baseUrl)
            "opencodego" -> OpenCodeGoProvider(apiKey, modelName, baseUrl)
            "custom" -> CustomProvider(apiKey, modelName, baseUrl, customTimeoutSec)
            else -> null
        }
    }
}

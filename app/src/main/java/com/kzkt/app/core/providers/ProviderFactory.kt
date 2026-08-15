package com.kzkt.app.core.providers

import com.kzkt.app.core.Config

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
        // Flexible auth from the registry — override ProviderMeta.authHeaderName /
        // authHeaderPrefix in Config.kt to support x-api-key style providers via
        // the Custom endpoint.
        val meta = Config.PROVIDER_REGISTRY[providerKey]
        val authHeaderName = meta?.authHeaderName ?: "Authorization"
        val authHeaderPrefix = meta?.authHeaderPrefix ?: "Bearer "
        return when (providerKey) {
            "gemini" -> GeminiProvider(apiKey, modelName, baseUrl)
            "openai" -> OpenAIProvider(apiKey, modelName, baseUrl)
            "openrouter" -> OpenRouterProvider(apiKey, modelName, baseUrl)
            "zen" -> ZenProvider(apiKey, modelName, baseUrl)
            "opencodego" -> OpenCodeGoProvider(apiKey, modelName, baseUrl)
            "custom" -> CustomProvider(
                apiKey, modelName, baseUrl, customTimeoutSec,
                authHeaderName = authHeaderName,
                authHeaderPrefix = authHeaderPrefix,
            )
            "anthropic" -> AnthropicProvider(apiKey, modelName, baseUrl, customTimeoutSec)
            else -> null
        }
    }
}

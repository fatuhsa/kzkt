package com.kzkt.app.core.providers

/**
 * OpenRouter provider — OpenAI-compatible REST API.
 * Supports 100+ models including Claude, Llama, Mistral, Gemini.
 */
class OpenRouterProvider(
    apiKey: String,
    modelName: String,
    customUrl: String = "",
    useSse: Boolean = true,
) : OpenAICompatProvider(apiKey, modelName, customUrl, useSse = useSse) {
    override val providerName: String = "OpenRouter"
    override val defaultEndpoint: String = "https://openrouter.ai/api/v1/chat/completions"
    override val textMaxTokens: Int? = null
}

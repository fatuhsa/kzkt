package com.kzkt.app.core.providers

/**
 * OpenCode Go provider (opencode.ai) — OpenAI-compatible with API key.
 */
class OpenCodeGoProvider(
    apiKey: String,
    modelName: String,
    customUrl: String = "",
) : OpenAICompatProvider(apiKey, modelName, customUrl) {

    override val providerName: String = "OpenCode Go"
    override val defaultEndpoint: String = "https://opencode.ai/zen/go/v1/chat/completions"
}

package com.kzkt.app.core.providers

/**
 * Zen provider (opencode.ai) — OpenAI-compatible, no key required.
 */
class ZenProvider(
    apiKey: String,
    modelName: String,
    customUrl: String = "",
    useSse: Boolean = true,
) : OpenAICompatProvider(apiKey, modelName, customUrl, useSse = useSse) {
    override val providerName: String = "Zen (opencode.ai)"
    override val defaultEndpoint: String = "https://opencode.ai/zen/v1/chat/completions"
}

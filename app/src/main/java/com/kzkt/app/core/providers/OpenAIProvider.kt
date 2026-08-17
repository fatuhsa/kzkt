package com.kzkt.app.core.providers

/**
 * OpenAI provider — REST API via the shared [OpenAICompatProvider] base.
 */
class OpenAIProvider(
    apiKey: String,
    modelName: String,
    customUrl: String = "",
    useSse: Boolean = true,
) : OpenAICompatProvider(apiKey, modelName, customUrl, useSse = useSse) {
    override val providerName: String = "OpenAI"
    override val defaultEndpoint: String = "https://api.openai.com/v1/chat/completions"
    override val forceJsonResponse: Boolean = true
    override val imageDetail: String? = "high"
}

package com.kzkt.app.core

/**
 * Normalize a provider base URL and build the model-list endpoint for it.
 *
 * Pure URL logic shared by the Settings model picker and the pre-flight model
 * validation in [com.kzkt.app.ui.MainViewModel], so both paths agree on how
 * base URLs like `https://host/v1` or `https://host/v1/chat/completions` map
 * to the model-list endpoint of each provider.
 */
fun modelsEndpointFor(
    providerKey: String,
    baseUrl: String,
): String {
    var normalized = baseUrl.trimEnd('/')
    if (normalized.endsWith("/chat/completions")) normalized = normalized.removeSuffix("/chat/completions")
    if (normalized.endsWith("/v1")) normalized = normalized.removeSuffix("/v1")
    return if (providerKey == "gemini") {
        val base = if (normalized.endsWith("/v1beta")) normalized else "$normalized/v1beta"
        "$base/models"
    } else {
        "$normalized/v1/models"
    }
}

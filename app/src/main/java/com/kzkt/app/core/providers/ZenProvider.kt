package com.kzkt.app.core.providers

import android.graphics.Bitmap
import com.kzkt.app.core.ImageProcessor
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Zen provider (opencode.ai) — OpenAI-compatible, no key required.
 * Ported from the original Python Zen provider
 */
class ZenProvider(
    override val apiKey: String,
    override val modelName: String,
    val customUrl: String = "",
) : LlmProvider {

    override val providerName: String = "Zen (opencode.ai)"
    private val baseUrl = if (customUrl.isNotBlank()) buildEndpoint(customUrl) else "https://opencode.ai/zen/v1/chat/completions"

    private fun buildEndpoint(raw: String): String {
        var base = raw.trimEnd('/')
        if (base.endsWith("/chat/completions")) return base
        if (base.endsWith("/v1")) return "$base/chat/completions"
        return "$base/v1/chat/completions"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(com.kzkt.app.core.Config.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(com.kzkt.app.core.Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(com.kzkt.app.core.Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override fun validateApiKey(): Boolean = true  // Zen works without API key

    override suspend fun translateImage(image: Bitmap, prompt: String): String? {
        val dataUri = ImageProcessor.bitmapToBase64DataUri(image)

        val headers = mutableMapOf("Content-Type" to "application/json")
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"

        val payload = mapOf(
            "model" to modelName,
            "temperature" to 0,
            "top_p" to 0.1,
            "messages" to listOf(mapOf(
                "role" to "user",
                "content" to listOf(
                    mapOf("type" to "image_url", "image_url" to mapOf("url" to dataUri)),
                    mapOf("type" to "text", "text" to prompt)
                )
            ))
        )

        val request = Request.Builder()
            .url(baseUrl)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return executeRequest(request)
    }

    override suspend fun translateText(textJson: String, prompt: String): String? {
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"

        val payload = mapOf(
            "model" to modelName,
            "temperature" to 0,
            "top_p" to 0.1,
            "max_tokens" to 4096,
            "messages" to listOf(mapOf(
                "role" to "user",
                "content" to prompt
            ))
        )

        val request = Request.Builder()
            .url(baseUrl)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return executeRequest(request)
    }

    private suspend fun executeRequest(request: Request): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.code == 401) throw ValueError("API_KEY_ERROR")
                if (!response.isSuccessful) {
                    val detail = try {
                        JsonParser.parseString(body).asJsonObject
                            .getAsJsonObject("error")
                            .get("message")?.asString ?: body.take(200)
                    } catch (_: Exception) { body.take(200) }
                    throw RuntimeException("Zen API error ${response.code}: $detail")
                }

                val json = JsonParser.parseString(body).asJsonObject
                val choices = json.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    return@withContext choices[0].asJsonObject
                        .getAsJsonObject("message")
                        .get("content")?.asString
                }
                body
            } catch (e: java.io.IOException) {
                throw RuntimeException("Zen network error: ${e.message}")
            }
        }
    }

    class ValueError(message: String) : Exception(message)
}

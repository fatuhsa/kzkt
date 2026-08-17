package com.kzkt.app.core.providers

import android.graphics.Bitmap
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.kzkt.app.core.ImageProcessor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Anthropic native Messages API provider (Claude models).
 * Uses the Messages endpoint directly instead of an OpenAI-compatible gateway:
 * `x-api-key` auth, base64 inline images, and SSE streaming with automatic
 * fallback to the non-streaming call.
 */
class AnthropicProvider(
    override val apiKey: String,
    override val modelName: String,
    val customUrl: String = "",
    timeoutSec: Int = 30,
    /** When false, skip the SSE stream and use the plain Messages call. */
    private val useSse: Boolean = true,
) : LlmProvider {

    override val providerName: String = "Anthropic"
    private val apiBaseUrl = if (customUrl.isNotBlank()) customUrl.trimEnd('/') else "https://api.anthropic.com"

    // Claude accepts images up to 8000 px per side; cap below that so the base64
    // payload stays small for the translation mosaics.
    private val maxImageDimension: Int = 4096

    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()

    override suspend fun translateImage(image: Bitmap, prompt: String): String? {
        val prepared = ImageProcessor.prepareImageForProvider(image, maxImageDimension)
        val base64 = ImageProcessor.bitmapToBase64(prepared)
        if (prepared !== image && !prepared.isRecycled) prepared.recycle()
        val payload = mapOf(
            "model" to modelName,
            "max_tokens" to 4096,
            "temperature" to 0,
            "messages" to listOf(mapOf(
                "role" to "user",
                "content" to listOf(
                    mapOf(
                        "type" to "image",
                        "source" to mapOf(
                            "type" to "base64",
                            "media_type" to "image/jpeg",
                            "data" to base64,
                        ),
                    ),
                    mapOf("type" to "text", "text" to prompt),
                ),
            )),
        )
        return executeWithStreamFallback(payload)
    }

    override suspend fun translateText(textJson: String, prompt: String): String? {
        val payload = mapOf(
            "model" to modelName,
            "max_tokens" to 4096,
            "temperature" to 0,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
        )
        return executeWithStreamFallback(payload)
    }

    /**
     * Send [payload] with `stream: true` and parse the SSE response, falling back
     * to the non-streaming call when the endpoint ignores the flag or fails —
     * the outcome is identical to a plain request in every case.
     */
    private suspend fun executeWithStreamFallback(payload: Map<String, Any>): String? {
        val plainRequest = buildRequest(payload)
        if (!useSse) return executePlain(plainRequest)
        val streamPayload = payload.toMutableMap().apply { put("stream", true) }
        val streamRequest = buildRequest(streamPayload)
        return try {
            executeStreaming(streamRequest)?.takeIf { it.isNotBlank() } ?: executePlain(plainRequest)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            executePlain(plainRequest)
        }
    }

    private fun buildRequest(payload: Map<String, Any>): Request {
        return Request.Builder()
            .url("$apiBaseUrl/v1/messages")
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
    }

    private suspend fun executePlain(request: Request): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""

                    if (response.code == 401 || response.code == 403) throw ValueError("API_KEY_ERROR")
                    if (!response.isSuccessful) {
                        throw RuntimeException("Anthropic API error ${response.code}: ${body.take(200)}")
                    }

                    // Parse response: content[*].text (first text block — skips any
                    // thinking/refusal blocks that may precede it).
                    val json = JsonParser.parseString(body).asJsonObject
                    val parts = json.getAsJsonArray("content")
                    if (parts != null) {
                        for (part in parts) {
                            if (part.isJsonObject) {
                                val text = part.asJsonObject.get("text")
                                if (text != null && !text.isJsonNull) {
                                    return@withContext text.asString
                                }
                            }
                        }
                    }
                    body
                }
            } catch (e: java.io.IOException) {
                throw RuntimeException("Anthropic network error: ${e.message}")
            }
        }
    }

    private suspend fun executeStreaming(request: Request): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 401 || response.code == 403) throw ValueError("API_KEY_ERROR")
                    if (!response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        throw RuntimeException("Anthropic API error ${response.code}: ${body.take(200)}")
                    }
                    val contentType = response.header("Content-Type") ?: ""
                    if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                        return@withContext null
                    }
                    val body = response.body ?: return@withContext null
                    SseParser.readStream(body.source(), SseParser::extractContentDelta)
                }
            } catch (e: java.io.IOException) {
                throw RuntimeException("Anthropic network error: ${e.message}")
            }
        }
    }
}

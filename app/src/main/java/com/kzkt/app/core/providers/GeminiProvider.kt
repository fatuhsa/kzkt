package com.kzkt.app.core.providers

import android.graphics.Bitmap
import com.kzkt.app.core.ImageProcessor
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Google Gemini provider — direct REST API.
 * Ported from the original Python Gemini provider
 *
 * Uses REST API directly instead of SDK: POST /v1beta/models/{model}:generateContent
 */
class GeminiProvider(
    override val apiKey: String,
    override val modelName: String,
    val customUrl: String = "",
    /** When false, skip the SSE stream and use the plain generateContent call. */
    private val useSse: Boolean = true,
) : LlmProvider {

    override val providerName: String = "Google Gemini"
    private val apiBaseUrl = if (customUrl.isNotBlank()) customUrl.trimEnd('/') else "https://generativelanguage.googleapis.com/v1beta"

    // Gemini inline-data images are limited to 3072×3072 px — downscale larger
    // mosaics so big pages do not get rejected.
    private val maxImageDimension: Int = 3072

    private val client = OkHttpClient.Builder()
        .connectTimeout(com.kzkt.app.core.Config.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(com.kzkt.app.core.Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(com.kzkt.app.core.Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun translateImage(image: Bitmap, prompt: String): String? {
        val prepared = ImageProcessor.prepareImageForProvider(image, maxImageDimension)
        val base64 = ImageProcessor.bitmapToBase64(prepared)
        if (prepared !== image && !prepared.isRecycled) prepared.recycle()
        val endpoint = "$apiBaseUrl/models/$modelName"

        val requestBody = gson.toJson(mapOf(
            "contents" to listOf(mapOf(
                "parts" to listOf(
                    mapOf(
                        "inlineData" to mapOf(
                            "mimeType" to "image/jpeg",
                            "data" to base64
                        )
                    ),
                    mapOf("text" to prompt)
                )
            )),
            "generationConfig" to mapOf(
                "temperature" to 0,
                "topP" to 0.1,
                "responseMimeType" to "application/json"
            )
        ))

        return executeGeminiWithStream(endpoint, requestBody)
    }

    override suspend fun translateText(textJson: String, prompt: String): String? {
        val endpoint = "$apiBaseUrl/models/$modelName"
        val requestBody = gson.toJson(mapOf(
            "contents" to listOf(mapOf(
                "parts" to listOf(
                    mapOf("text" to prompt)
                )
            )),
            "generationConfig" to mapOf(
                "temperature" to 0,
                "topP" to 0.1,
                "maxOutputTokens" to 4096,
                "responseMimeType" to "application/json"
            )
        ))
        return executeGeminiWithStream(endpoint, requestBody)
    }

    /**
     * Try the SSE streaming endpoint first, falling back to the plain
     * generateContent endpoint when the provider ignores the stream or the
     * stream fails — the outcome is identical to the old behaviour.
     */
    private suspend fun executeGeminiWithStream(endpoint: String, requestBody: String): String? {
        val plainUrl = "$endpoint:generateContent?key=$apiKey"
        if (!useSse) return executeGeminiPlain(plainUrl, requestBody)
        val streamUrl = "$endpoint:streamGenerateContent?alt=sse&key=$apiKey"
        return try {
            executeGeminiStreaming(streamUrl, requestBody)?.takeIf { it.isNotBlank() }
                ?: executeGeminiPlain(plainUrl, requestBody)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Streaming attempt failed — retry once with the plain endpoint so the
            // result is identical to before.
            executeGeminiPlain(plainUrl, requestBody)
        }
    }

    /**
     * POST to the SSE streaming endpoint and accumulate the text deltas.
     * Returns null when the response is not SSE (caller falls back to plain).
     */
    private suspend fun executeGeminiStreaming(url: String, requestBody: String): String? {
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 403 || response.code == 401) {
                        throw ValueError("API_KEY_ERROR")
                    }
                    if (!response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        throw RuntimeException("Gemini API error ${response.code}: ${body.take(200)}")
                    }
                    val contentType = response.header("Content-Type") ?: ""
                    if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                        return@withContext null
                    }
                    val body = response.body ?: return@withContext null
                    SseParser.readStream(body.source(), SseParser::extractContentDelta)
                }
            } catch (e: IOException) {
                throw RuntimeException("Gemini network error: ${e.message}")
            }
        }
    }

    private suspend fun executeGeminiPlain(url: String, requestBody: String): String? {
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // use{} closes the response so the pooled connection is released promptly.
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""

                    if (response.code == 403 || response.code == 401) {
                        throw ValueError("API_KEY_ERROR")
                    }
                    if (!response.isSuccessful) {
                        throw RuntimeException("Gemini API error ${response.code}: ${body.take(200)}")
                    }

                    // Parse response: candidates[0].content.parts[0].text
                    val json = JsonParser.parseString(body).asJsonObject
                    val candidates = json.getAsJsonArray("candidates")
                    if (candidates != null && candidates.size() > 0) {
                        val content = candidates[0].asJsonObject.getAsJsonObject("content")
                        val parts = content.getAsJsonArray("parts")
                        if (parts != null && parts.size() > 0) {
                            return@withContext parts[0].asJsonObject.get("text")?.asString
                        }
                    }
                    body
                }
            } catch (e: IOException) {
                throw RuntimeException("Gemini network error: ${e.message}")
            }
        }
    }
}

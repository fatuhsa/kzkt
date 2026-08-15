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
 * Shared base for OpenAI-compatible chat-completions providers (OpenAI, OpenRouter,
 * Zen, OpenCode Go). Deduplicates the near-identical payload/response handling that
 * was previously copy-pasted across four provider classes.
 */
abstract class OpenAICompatProvider(
    override val apiKey: String,
    override val modelName: String,
    val customUrl: String = "",
    /** Header name + value prefix for the API key (default: `Authorization: Bearer`). */
    private val authHeaderName: String = "Authorization",
    private val authHeaderPrefix: String = "Bearer ",
    /** Longest-side limit before the mosaic is downscaled (provider image limits). */
    private val maxImageDimension: Int = 4096,
) : LlmProvider {

    /** The default chat-completions endpoint used when [customUrl] is blank. */
    abstract val defaultEndpoint: String

    /** Providers that enforce JSON output via `response_format` (OpenAI only). */
    protected open val forceJsonResponse: Boolean = false

    /** Optional image `detail` hint for the image_url part (OpenAI uses "high"). */
    protected open val imageDetail: String? = null

    /** Optional `max_tokens` for text-only requests (null = omit, e.g. OpenRouter). */
    protected open val textMaxTokens: Int? = 4096

    /** HTTP client used for requests — overridable (e.g. Custom provider's own timeout). */
    protected open val httpClient: OkHttpClient get() = sharedClient

    protected val gson = Gson()

    companion object {
        // One shared client + connection pool across all OpenAI-compatible providers
        // (previously every provider instance built its own OkHttpClient).
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(com.kzkt.app.core.Config.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(com.kzkt.app.core.Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(com.kzkt.app.core.Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    protected fun buildEndpoint(): String {
        if (customUrl.isBlank()) return defaultEndpoint
        var base = customUrl.trimEnd('/')
        if (base.endsWith("/chat/completions")) return base
        if (base.endsWith("/v1")) return "$base/chat/completions"
        return "$base/v1/chat/completions"
    }

    override suspend fun translateImage(image: Bitmap, prompt: String): String? {
        // Downscale oversized mosaics so provider image-size limits are respected.
        val prepared = ImageProcessor.prepareImageForProvider(image, maxImageDimension)
        val dataUri = ImageProcessor.bitmapToBase64DataUri(prepared)
        if (prepared !== image && !prepared.isRecycled) prepared.recycle()
        val imagePart: Map<String, Any> = if (imageDetail != null) {
            mapOf("type" to "image_url", "image_url" to mapOf("url" to dataUri, "detail" to imageDetail))
        } else {
            mapOf("type" to "image_url", "image_url" to mapOf("url" to dataUri))
        }
        val payload = buildPayload(
            content = listOf(imagePart, mapOf("type" to "text", "text" to prompt))
        )
        return executeWithStreamFallback(payload)
    }

    override suspend fun translateText(textJson: String, prompt: String): String? {
        val payload = buildPayload(content = prompt)
        textMaxTokens?.let { payload["max_tokens"] = it }
        return executeWithStreamFallback(payload)
    }

    /**
     * Send [payload] with `stream: true` and parse the SSE response, falling back
     * to the proven non-streaming path when the provider ignores the flag or the
     * stream fails — the outcome is identical to the old behaviour in every case.
     */
    protected suspend fun executeWithStreamFallback(payload: MutableMap<String, Any>): String? {
        val streamRequest = buildRequest(payload.toMutableMap().apply { put("stream", true) })
        val plainRequest = buildRequest(payload)
        return try {
            executeStreamingRequest(streamRequest)?.takeIf { it.isNotBlank() } ?: executeRequest(plainRequest)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Streaming attempt failed (provider may reject `stream: true`) — retry
            // once with the plain request so the result is identical to before.
            executeRequest(plainRequest)
        }
    }

    /**
     * Execute a request expecting an SSE (text/event-stream) response and return
     * the accumulated text. Returns null when the response is not SSE — the
     * caller then falls back to the regular non-streaming parse.
     */
    protected open suspend fun executeStreamingRequest(request: Request): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in listOf(401, 402)) throw ValueError("API_KEY_ERROR")
                    if (!response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val detail = try {
                            val errorObj = JsonParser.parseString(body).asJsonObject.getAsJsonObject("error")
                            errorObj.get("message")?.asString ?: body.take(200)
                        } catch (_: Exception) {
                            body.take(200)
                        }
                        throw RuntimeException("${providerName} API error ${response.code}: ${detail}")
                    }
                    val contentType = response.header("Content-Type") ?: ""
                    if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                        return@withContext null
                    }
                    val body = response.body ?: return@withContext null
                    SseParser.readStream(body.source(), SseParser::extractContentDelta)
                }
            } catch (e: java.io.IOException) {
                throw RuntimeException("${providerName} network error: ${e.message}")
            }
        }
    }

    private fun buildPayload(content: Any): MutableMap<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "model" to modelName,
            "temperature" to 0,
            "top_p" to 0.1,
            "messages" to listOf(mapOf("role" to "user", "content" to content)),
        )
        if (forceJsonResponse) payload["response_format"] = mapOf("type" to "json_object")
        return payload
    }

    private fun buildRequest(payload: Map<String, Any>): Request {
        val builder = Request.Builder()
            .url(buildEndpoint())
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaTypeOrNull()))
        if (apiKey.isNotBlank()) builder.addHeader(authHeaderName, authHeaderPrefix + apiKey)
        return builder.build()
    }

    protected open suspend fun executeRequest(request: Request): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // use{} closes the response so the pooled connection is released promptly.
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""

                    if (response.code in listOf(401, 402)) throw ValueError("API_KEY_ERROR")
                    if (!response.isSuccessful) {
                        val detail = try {
                            JsonParser.parseString(body).asJsonObject
                                .getAsJsonObject("error")
                                .get("message")?.asString ?: body.take(200)
                        } catch (_: Exception) {
                            body.take(200)
                        }
                        throw RuntimeException("${providerName} API error ${response.code}: $detail")
                    }

                    val json = JsonParser.parseString(body).asJsonObject
                    val choices = json.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        return@withContext choices[0].asJsonObject
                            .getAsJsonObject("message")
                            .get("content")?.asString
                    }
                    body
                }
            } catch (e: java.io.IOException) {
                throw RuntimeException("${providerName} network error: ${e.message}")
            }
        }
    }
}

class ValueError(message: String) : Exception(message)

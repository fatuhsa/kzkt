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
) : LlmProvider {

    /** The default chat-completions endpoint used when [customUrl] is blank. */
    abstract val defaultEndpoint: String

    /** Providers that enforce JSON output via `response_format` (OpenAI only). */
    protected open val forceJsonResponse: Boolean = false

    /** Optional image `detail` hint for the image_url part (OpenAI uses "high"). */
    protected open val imageDetail: String? = null

    /** Optional `max_tokens` for text-only requests (null = omit, e.g. OpenRouter). */
    protected open val textMaxTokens: Int? = 4096

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
        val dataUri = ImageProcessor.bitmapToBase64DataUri(image)
        val imagePart: Map<String, Any> = if (imageDetail != null) {
            mapOf("type" to "image_url", "image_url" to mapOf("url" to dataUri, "detail" to imageDetail))
        } else {
            mapOf("type" to "image_url", "image_url" to mapOf("url" to dataUri))
        }
        val payload = buildPayload(
            content = listOf(imagePart, mapOf("type" to "text", "text" to prompt))
        )
        return executeRequest(buildRequest(payload))
    }

    override suspend fun translateText(textJson: String, prompt: String): String? {
        val payload = buildPayload(content = prompt)
        textMaxTokens?.let { payload["max_tokens"] = it }
        return executeRequest(buildRequest(payload))
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
        if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
        return builder.build()
    }

    protected open suspend fun executeRequest(request: Request): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = sharedClient.newCall(request).execute()
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
            } catch (e: java.io.IOException) {
                throw RuntimeException("${providerName} network error: ${e.message}")
            }
        }
    }
}

class ValueError(message: String) : Exception(message)

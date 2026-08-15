package com.kzkt.app.core.providers

import android.graphics.Bitmap
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * Custom OpenAI-compatible provider with configurable base URL.
 * Extends [OpenAICompatProvider] to reuse the shared payload / endpoint / auth
 * handling; only the response parsing is overridden to be lenient so it also
 * accepts Ollama / LM Studio / direct formats (choices[0].text, top-level
 * `response`, or top-level `message.content` without a `choices` array).
 */
class CustomProvider(
    apiKey: String,
    modelName: String,
    baseUrl: String = "",
    timeoutSec: Int = 30,
    authHeaderName: String = "Authorization",
    authHeaderPrefix: String = "Bearer ",
) : OpenAICompatProvider(apiKey, modelName, baseUrl, authHeaderName, authHeaderPrefix) {

    override val providerName: String = "Custom"
    override val defaultEndpoint: String = "https://api.openai.com/v1/chat/completions"
    override val textMaxTokens: Int? = null

    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun translateImage(image: Bitmap, prompt: String): String? {
        if (customUrl.isBlank()) {
            throw RuntimeException("Custom provider base URL is not configured.")
        }
        return super.translateImage(image, prompt)
    }

    override suspend fun executeRequest(request: Request): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // use{} closes the response so the pooled connection is released promptly.
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""

                    if (response.code in listOf(401, 402)) throw ValueError("API_KEY_ERROR")
                    if (!response.isSuccessful) {
                        throw RuntimeException("Custom API error ${response.code}: ${body.take(200)}")
                    }

                    val lenientReader = JsonReader(StringReader(body)).apply { isLenient = true }
                    val json = JsonParser.parseReader(lenientReader)

                    if (!json.isJsonObject) return@withContext body
                    val jsonObj = json.asJsonObject

                    // 1. Standard OpenAI format: choices[0].message.content (string or parts array)
                    if (jsonObj.has("choices") && jsonObj.get("choices").isJsonArray) {
                        val choices = jsonObj.getAsJsonArray("choices")
                        if (choices.size() > 0 && choices[0].isJsonObject) {
                            val choiceObj = choices[0].asJsonObject
                            if (choiceObj.has("message") && choiceObj.get("message").isJsonObject) {
                                val msgObj = choiceObj.getAsJsonObject("message")
                                if (msgObj.has("content") && !msgObj.get("content").isJsonNull) {
                                    val contentElem = msgObj.get("content")
                                    if (contentElem.isJsonPrimitive) return@withContext contentElem.asString
                                    if (contentElem.isJsonArray) {
                                        return@withContext contentElem.asJsonArray
                                            .filter { it.isJsonObject && it.asJsonObject.has("text") }
                                            .joinToString("\n") { it.asJsonObject.get("text").asString }
                                    }
                                }
                            } else if (choiceObj.has("text") && !choiceObj.get("text").isJsonNull) {
                                // 1b. OpenAI completions format: choices[0].text
                                return@withContext choiceObj.get("text").asString
                            }
                        }
                    }

                    // 2. Ollama / direct response format
                    if (jsonObj.has("response") && !jsonObj.get("response").isJsonNull) {
                        return@withContext jsonObj.get("response").asString
                    }
                    if (jsonObj.has("message") && jsonObj.get("message").isJsonObject) {
                        val msg = jsonObj.getAsJsonObject("message")
                        if (msg.has("content") && !msg.get("content").isJsonNull) {
                            return@withContext msg.get("content").asString
                        }
                    }

                    body
                }
            } catch (e: java.io.IOException) {
                throw RuntimeException("Custom network error: ${e.message}")
            }
        }
    }
}

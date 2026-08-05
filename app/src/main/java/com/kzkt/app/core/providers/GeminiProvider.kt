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
) : LlmProvider {

    override val providerName: String = "Google Gemini"
    private val apiBaseUrl = if (customUrl.isNotBlank()) customUrl.trimEnd('/') else "https://generativelanguage.googleapis.com/v1beta"

    private val client = OkHttpClient.Builder()
        .connectTimeout(com.kzkt.app.core.Config.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(com.kzkt.app.core.Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(com.kzkt.app.core.Config.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun translateImage(image: Bitmap, prompt: String): String? {
        val base64 = ImageProcessor.bitmapToBase64(image)
        val url = "$apiBaseUrl/models/$modelName:generateContent"

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

        return executeGeminiRequest(url, requestBody)
    }

    override suspend fun translateText(textJson: String, prompt: String): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
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
        return executeGeminiRequest(url, requestBody)
    }

    private suspend fun executeGeminiRequest(url: String, requestBody: String): String? {
        val request = Request.Builder()
            .url("$url?key=$apiKey")
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
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
            } catch (e: IOException) {
                throw RuntimeException("Gemini network error: ${e.message}")
            }
        }
    }

    class ValueError(message: String) : Exception(message)
}

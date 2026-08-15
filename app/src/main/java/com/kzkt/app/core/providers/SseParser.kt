package com.kzkt.app.core.providers

import com.google.gson.JsonParser
import okio.BufferedSource

/**
 * Minimal SSE (Server-Sent Events) reader for LLM streaming responses.
 * Accumulates the incremental text deltas from `data:` lines for both OpenAI
 * chat-completions (choices[0].delta.content), Gemini streamGenerateContent
 * (candidates[0].content.parts[*].text) and Ollama (top-level `response`).
 * Keep-alive comment lines and non-data fields are ignored.
 */
object SseParser {
    /**
     * Consume an SSE stream from [source] and return the concatenated content.
     * Stops at the OpenAI `data: [DONE]` sentinel or when the stream ends.
     */
    fun readStream(
        source: BufferedSource,
        extractor: (String) -> String?,
    ): String? {
        val sb = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break
            extractor(data)?.let { sb.append(it) }
        }
        return sb.toString()
    }

    /**
     * Extract the incremental text delta from one SSE `data:` payload.
     * Returns null when the chunk carries no content (usage, role, keep-alive).
     */
    fun extractContentDelta(data: String): String? {
        return try {
            val json = JsonParser.parseString(data)
            if (!json.isJsonObject) return null
            val obj = json.asJsonObject
            // OpenAI chat completions streaming: choices[0].delta.content
            obj.getAsJsonArray("choices")?.let { choices ->
                if (choices.size() > 0 && choices[0].isJsonObject) {
                    val choice = choices[0].asJsonObject
                    choice.getAsJsonObject("delta")?.get("content")?.let { if (!it.isJsonNull) return it.asString }
                    choice.getAsJsonObject("message")?.get("content")?.let { if (!it.isJsonNull) return it.asString }
                }
            }
            // Anthropic Messages streaming: {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}
            obj.get("type")?.takeIf { it.isJsonPrimitive && it.asString == "content_block_delta" }?.let {
                obj.getAsJsonObject("delta")?.get("text")?.let { t -> if (!t.isJsonNull) return t.asString }
            }
            // Gemini streamGenerateContent: candidates[0].content.parts[*].text
            obj.getAsJsonArray("candidates")?.let { candidates ->
                if (candidates.size() > 0 && candidates[0].isJsonObject) {
                    val parts = candidates[0].asJsonObject.getAsJsonObject("content")?.getAsJsonArray("parts")
                    if (parts != null) {
                        for (part in parts) {
                            if (part.isJsonObject) {
                                part.asJsonObject.get("text")?.let { if (!it.isJsonNull) return it.asString }
                            }
                        }
                    }
                }
            }
            // Ollama / direct response format: top-level "response"
            obj.get("response")?.let { if (!it.isJsonNull) return it.asString }
            null
        } catch (_: Exception) {
            null
        }
    }
}

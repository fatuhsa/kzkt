package com.kzkt.app.core.providers

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the SSE reader used by LLM streaming responses.
 * Pure JVM: feeds okio Buffers with canned `data:` lines.
 */
class SseParserTest {
    private fun streamOf(text: String): Buffer = Buffer().writeUtf8(text)

    private fun sseLine(json: String): String = "data: $json\n\n"

    @Test
    fun `accumulates openai chat completions deltas`() {
        val sse =
            streamOf(
                sseLine("""{"choices":[{"delta":{"role":"assistant"}}]}""") +
                    sseLine("""{"choices":[{"delta":{"content":"{\"1\":\"Hal"}}]}""") +
                    sseLine("""{"choices":[{"delta":{"content":"o\"}"}}]}""") +
                    sseLine("[DONE]"),
            )
        val result = SseParser.readStream(sse, SseParser::extractContentDelta)
        assertEquals("""{"1":"Halo"}""", result)
    }

    @Test
    fun `accumulates gemini streamGenerateContent parts`() {
        val sse =
            streamOf(
                sseLine("""{"candidates":[{"content":{"parts":[{"text":"{\"1\":\"Halo\"}"}]}}]}"""),
            )
        val result = SseParser.readStream(sse, SseParser::extractContentDelta)
        assertEquals("""{"1":"Halo"}""", result)
    }

    @Test
    fun `accumulates anthropic content block deltas`() {
        val sse =
            streamOf(
                sseLine("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +
                    sseLine("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"{\"1\":\"Ha"}}""") +
                    sseLine("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo\"}"}}""") +
                    sseLine("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""),
            )
        val result = SseParser.readStream(sse, SseParser::extractContentDelta)
        assertEquals("""{"1":"Halo"}""", result)
    }

    @Test
    fun `accumulates ollama top-level response field`() {
        val sse =
            streamOf(
                sseLine("""{"model":"llama","response":"Halo","done":false}""") +
                    sseLine("""{"model":"llama","response":" semuanya","done":true}"""),
            )
        val result = SseParser.readStream(sse, SseParser::extractContentDelta)
        assertEquals("Halo semuanya", result)
    }

    @Test
    fun `stops at DONE and skips non-content chunks`() {
        val sse =
            streamOf(
                sseLine("""{"choices":[{"delta":{"content":"A"}}]}""") +
                    sseLine("""{"usage":{"total_tokens":5}}""") +
                    sseLine("""{"choices":[{"delta":{"content":"B"}}]}""") +
                    sseLine("[DONE]") +
                    sseLine("""{"choices":[{"delta":{"content":"IGNORED"}}]}"""),
            )
        val result = SseParser.readStream(sse, SseParser::extractContentDelta)
        assertEquals("AB", result)
    }

    @Test
    fun `extract returns null for chunks without text`() {
        assertNull(SseParser.extractContentDelta("""{"usage":{"total_tokens":5}}"""))
        assertNull(SseParser.extractContentDelta("not-json"))
    }

    @Test
    fun `empty stream returns empty string`() {
        val result = SseParser.readStream(streamOf(": keep-alive\n\n"), SseParser::extractContentDelta)
        assertEquals("", result)
    }
}

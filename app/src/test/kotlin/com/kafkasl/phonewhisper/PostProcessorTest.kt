package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PostProcessorTest {

    @Test
    fun parseSuccess() {
        val json = """
        {
            "id": "chatcmpl-123",
            "object": "chat.completion",
            "created": 1677652288,
            "model": "gpt-4o-mini",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "Hello there, how are you?"
                },
                "finish_reason": "stop"
            }],
            "usage": {
                "prompt_tokens": 9,
                "completion_tokens": 12,
                "total_tokens": 21
            }
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals("Hello there, how are you?", result.text)
        assertEquals(null, result.error)
    }

    @Test
    fun parseError() {
        val json = """
        {
            "error": {
                "message": "Incorrect API key provided.",
                "type": "invalid_request_error",
                "param": null,
                "code": "invalid_api_key"
            }
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals(null, result.text)
        assertEquals("Incorrect API key provided.", result.error)
    }

    @Test
    fun parseEmptyChoices() {
        val json = """
        {
            "choices": []
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals(null, result.text)
        assertEquals("No choices in response", result.error)
    }

    @Test
    fun parseInvalidJson() {
        val result = PostProcessor.parseResponse("invalid json")
        assertEquals(null, result.text)
        assertTrue(
            result.error?.contains("JSONObject") == true ||
                result.error?.contains("must begin with '{'") == true
        )
    }

    @Test fun `detects models that reject temperature`() {
        val body = """{"error":{"message":"Unsupported value: 'temperature' does not support 0.0 with this model.","param":"temperature"}}"""
        assertTrue(PostProcessor.rejectsTemperature(400, body))
        assertFalse(PostProcessor.rejectsTemperature(401, body))
        assertFalse(PostProcessor.rejectsTemperature(400, """{"error":{"message":"Invalid model"}}"""))
    }

    @Test fun `request body only sets temperature when asked`() {
        val with = org.json.JSONObject(PostProcessor.requestBody("hi", "p", "m", withTemperature = true))
        val without = org.json.JSONObject(PostProcessor.requestBody("hi", "p", "m", withTemperature = false))
        assertEquals(0.0, with.getDouble("temperature"), 0.0)
        assertFalse(without.has("temperature"))
        assertEquals("hi", without.getJSONArray("messages").getJSONObject(1).getString("content"))
    }
}

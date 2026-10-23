package com.kafkasl.phonewhisper

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudProviderTest {

    @Test fun `defaults to OpenAI with current models`() {
        val prefs = FakePrefs()
        assertEquals(CloudTarget(CloudProvider.OPENAI, "", "gpt-4o-mini-transcribe"), prefs.transcriptionTarget())
        assertEquals(CloudTarget(CloudProvider.OPENAI, "", "gpt-4o-mini"), prefs.postProcessingTarget())
    }

    @Test fun `each task has its own provider, key and model`() {
        val prefs = FakePrefs(
            "transcription_provider" to "openrouter",
            "openrouter_api_key" to "sk-or-1",
            "api_key" to "sk-1",
            "openrouter_transcription_model" to "mistralai/voxtral-mini-transcribe",
            "openai_post_processing_model" to " gpt-4.1-mini ",
        )
        assertEquals(
            CloudTarget(CloudProvider.OPENROUTER, "sk-or-1", "mistralai/voxtral-mini-transcribe"),
            prefs.transcriptionTarget()
        )
        assertEquals(CloudTarget(CloudProvider.OPENAI, "sk-1", "gpt-4.1-mini"), prefs.postProcessingTarget())
    }

    @Test fun `blank model falls back to default`() {
        val prefs = FakePrefs("openai_transcription_model" to "  ")
        assertEquals("gpt-4o-mini-transcribe", prefs.transcriptionTarget().model)
    }

    @Test fun `every provider has a default among its presets`() {
        for (task in CloudTask.entries) for (provider in CloudProvider.entries) {
            assertTrue("$task/$provider", task.defaultModel(provider) in task.presets(provider))
        }
    }

    @Test fun `api error message`() {
        assertEquals("Bad key", apiErrorMessage(401, """{"error":{"message":"Bad key"}}"""))
        assertEquals("HTTP 502", apiErrorMessage(502, "<html>Bad gateway</html>"))
        assertEquals("HTTP 500", apiErrorMessage(500, ""))
    }

    @Test fun `OpenAI model list is filtered by task`() {
        val json = JSONObject().put("data", org.json.JSONArray(listOf(
            "gpt-4o-mini", "gpt-4o-mini-transcribe", "whisper-1", "gpt-4o-mini-tts", "o4-mini",
            "text-embedding-3-small", "gpt-4o-realtime-preview", "dall-e-3", "gpt-4.1-nano",
        ).map { JSONObject().put("id", it) })).toString()

        assertEquals(listOf("gpt-4o-mini-transcribe", "whisper-1"), OpenAiModels.parseModels(json, CloudTask.TRANSCRIPTION))
        assertEquals(listOf("gpt-4.1-nano", "gpt-4o-mini", "o4-mini"), OpenAiModels.parseModels(json, CloudTask.POST_PROCESSING))
    }
}

/** Map-backed SharedPreferences for reading in JVM tests. */
class FakePrefs(vararg values: Pair<String, Any>) : SharedPreferences {
    private val map = values.toMap()
    override fun getAll() = map
    override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in map
    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
}

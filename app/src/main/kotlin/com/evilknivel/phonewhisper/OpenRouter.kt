package com.evilknivel.phonewhisper

import okhttp3.*
import org.json.JSONObject
import java.io.IOException

object OpenRouter {
    const val BASE_URL = "https://openrouter.ai/api/v1"

    /**
     * Model IDs whose output modalities include [outputModality] ("transcription" or "text"), sorted.
     * Entries without architecture info are kept so a schema change doesn't empty the list.
     */
    fun parseModels(json: String, outputModality: String): List<String> {
        val data = JSONObject(json).getJSONArray("data")
        return (0 until data.length()).mapNotNull { i ->
            val model = data.getJSONObject(i)
            val modalities = model.optJSONObject("architecture")?.optJSONArray("output_modalities")
            val matches = modalities == null ||
                (0 until modalities.length()).any { modalities.optString(it) == outputModality }
            model.optString("id").takeIf { matches && it.isNotBlank() }
        }.distinct().sorted()
    }

    /** Fetch available models. The endpoint is public, no API key needed. Callback fires on a background thread. */
    fun listModels(outputModality: String, callback: (List<String>?, String?) -> Unit) {
        val url = "$BASE_URL/models?output_modalities=$outputModality"
        fetch(Request.Builder().url(url).build(), callback) { parseModels(it, outputModality) }
    }
}

object OpenAiModels {
    private val NOT_CHAT = listOf("audio", "realtime", "tts", "transcribe", "image", "search", "embedding", "instruct", "moderation")

    /**
     * OpenAI's `/v1/models` has no modality info, so filter by ID: speech-to-text models for
     * [CloudTask.TRANSCRIPTION], chat models (gpt-*, o-series) for [CloudTask.POST_PROCESSING].
     */
    fun parseModels(json: String, task: CloudTask): List<String> {
        val data = JSONObject(json).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it).optString("id") }
            .filter { id ->
                when (task) {
                    CloudTask.TRANSCRIPTION -> "transcribe" in id || id.startsWith("whisper")
                    CloudTask.POST_PROCESSING ->
                        (id.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(id)) && NOT_CHAT.none { it in id }
                }
            }
            .distinct().sorted()
    }

    /**
     * `/models` of OpenAI (needs the API key) or of a custom OpenAI-compatible server. Custom
     * servers name models freely, so their list isn't filtered. Callback fires on a background thread.
     */
    fun listModels(target: CloudTarget, task: CloudTask, callback: (List<String>?, String?) -> Unit) {
        val request = try {
            Request.Builder().url("${target.baseUrl}/models").auth(target).build()
        } catch (e: IllegalArgumentException) {
            callback(null, "Invalid API address"); return
        }
        fetch(request, callback) {
            if (target.provider == CloudProvider.CUSTOM) parseIds(it) else parseModels(it, task)
        }
    }

    fun parseIds(json: String): List<String> {
        val data = JSONObject(json).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it).optString("id") }
            .filter { it.isNotBlank() }.distinct().sorted()
    }
}

private fun fetch(request: Request, callback: (List<String>?, String?) -> Unit, parse: (String) -> List<String>) {
    Http.client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = callback(null, e.message ?: "Network error")
        override fun onResponse(call: Call, response: Response) {
            val body = response.use { it.body.string() }
            if (!response.isSuccessful) { callback(null, apiErrorMessage(response.code, body)); return }
            try {
                callback(parse(body), null)
            } catch (e: Exception) {
                callback(null, e.message ?: "Parse error")
            }
        }
    })
}

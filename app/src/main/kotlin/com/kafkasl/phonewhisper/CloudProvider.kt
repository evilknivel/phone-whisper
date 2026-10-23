package com.kafkasl.phonewhisper

import android.content.SharedPreferences

enum class CloudProvider(val label: String, val keyPref: String) {
    OPENAI("OpenAI", "api_key"),
    OPENROUTER("OpenRouter", "openrouter_api_key");

    val prefValue get() = name.lowercase()

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.prefValue == value } ?: OPENAI
    }
}

/** Provider, credentials and model for one kind of cloud request, resolved from prefs. */
data class CloudTarget(val provider: CloudProvider, val apiKey: String, val model: String)

/**
 * The two kinds of cloud requests. Each picks its provider and, per provider, its model
 * separately. [outputModality] is the OpenRouter `/models` filter for the picker.
 */
enum class CloudTask(
    val providerPref: String,
    val outputModality: String,
    private val modelPrefs: Map<CloudProvider, String>,
    private val defaults: Map<CloudProvider, String>,
    private val presets: Map<CloudProvider, List<String>>,
) {
    TRANSCRIPTION(
        providerPref = "transcription_provider",
        outputModality = "transcription",
        modelPrefs = mapOf(
            CloudProvider.OPENAI to "openai_transcription_model",
            CloudProvider.OPENROUTER to "openrouter_transcription_model",
        ),
        defaults = mapOf(
            CloudProvider.OPENAI to "gpt-4o-mini-transcribe",
            CloudProvider.OPENROUTER to "openai/whisper-large-v3",
        ),
        presets = mapOf(
            CloudProvider.OPENAI to listOf(
                "gpt-4o-mini-transcribe",
                "gpt-4o-transcribe",
                "whisper-1",
            ),
            CloudProvider.OPENROUTER to listOf(
                "openai/whisper-large-v3",
                "openai/whisper-large-v3-turbo",
                "openai/gpt-4o-mini-transcribe",
                "openai/gpt-4o-transcribe",
                "mistralai/voxtral-mini-transcribe",
                "google/chirp-3",
                "elevenlabs/scribe-v2",
            ),
        ),
    ),
    POST_PROCESSING(
        providerPref = "post_processing_provider",
        outputModality = "text",
        modelPrefs = mapOf(
            CloudProvider.OPENAI to "openai_post_processing_model",
            CloudProvider.OPENROUTER to "openrouter_post_processing_model",
        ),
        defaults = mapOf(
            CloudProvider.OPENAI to "gpt-4o-mini",
            CloudProvider.OPENROUTER to "openai/gpt-4o-mini",
        ),
        presets = mapOf(
            CloudProvider.OPENAI to listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4.1-nano"),
            CloudProvider.OPENROUTER to listOf(
                "openai/gpt-4o-mini",
                "openai/gpt-4.1-mini",
                "google/gemini-2.5-flash-lite",
            ),
        ),
    );

    fun modelPref(provider: CloudProvider) = modelPrefs.getValue(provider)
    fun defaultModel(provider: CloudProvider) = defaults.getValue(provider)
    fun presets(provider: CloudProvider) = presets.getValue(provider)
}

fun SharedPreferences.cloudTarget(task: CloudTask): CloudTarget {
    val provider = CloudProvider.from(getString(task.providerPref, null))
    val model = getString(task.modelPref(provider), null)?.trim()
        ?.takeIf { it.isNotEmpty() } ?: task.defaultModel(provider)
    return CloudTarget(provider, getString(provider.keyPref, "") ?: "", model)
}

fun SharedPreferences.transcriptionTarget() = cloudTarget(CloudTask.TRANSCRIPTION)
fun SharedPreferences.postProcessingTarget() = cloudTarget(CloudTask.POST_PROCESSING)

/** Error message from an OpenAI-style error body (`{"error": {"message": ...}}`), else `HTTP <code>`. */
fun apiErrorMessage(code: Int, body: String): String =
    runCatching { org.json.JSONObject(body).getJSONObject("error").getString("message") }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"

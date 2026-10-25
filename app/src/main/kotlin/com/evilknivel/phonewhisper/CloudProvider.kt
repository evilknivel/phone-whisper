package com.evilknivel.phonewhisper

import android.content.SharedPreferences

enum class CloudProvider(val label: String, val keyPref: String) {
    OPENAI("OpenAI", "api_key"),
    OPENROUTER("OpenRouter", "openrouter_api_key"),
    /** Any OpenAI-compatible server (Groq, a self-hosted Whisper server, ...) at the `custom_base_url` pref. */
    CUSTOM("Custom (OpenAI-compatible)", "custom_api_key");

    val prefValue get() = name.lowercase()

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.prefValue == value } ?: OPENAI
    }
}

const val CUSTOM_BASE_URL_PREF = "custom_base_url"

/**
 * Provider, credentials, model and API base URL (ending in `/v1` or the provider's equivalent)
 * for one kind of cloud request, resolved from prefs. [baseUrl] is blank for a custom provider
 * whose address isn't set.
 */
data class CloudTarget(
    val provider: CloudProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String = defaultBaseUrl(provider),
) {
    /** A custom server may run without a key; the hosted providers need one. */
    val keyRequired get() = provider != CloudProvider.CUSTOM
    val isConfigured get() = baseUrl.isNotBlank() && (!keyRequired || apiKey.isNotBlank())
}

fun defaultBaseUrl(provider: CloudProvider) = when (provider) {
    CloudProvider.OPENAI -> "https://api.openai.com/v1"
    CloudProvider.OPENROUTER -> OpenRouter.BASE_URL
    CloudProvider.CUSTOM -> ""
}

/**
 * Normalize a user-entered custom API address to a base URL. Accepts a host (`http://pc:8000`),
 * a `/v1` base, or a full `/audio/transcriptions` or `/chat/completions` endpoint.
 * Returns "" for blank input.
 */
fun normalizeBaseUrl(raw: String): String {
    var url = raw.trim().trimEnd('/')
    if (url.isEmpty()) return ""
    if (!url.contains("://")) url = "https://$url"
    for (endpoint in listOf("/audio/transcriptions", "/chat/completions", "/models")) {
        if (url.endsWith(endpoint)) url = url.removeSuffix(endpoint)
    }
    val path = url.substringAfter("://").substringAfter('/', "")
    return if (path.isEmpty()) "$url/v1" else url
}

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
            CloudProvider.CUSTOM to "custom_transcription_model",
        ),
        defaults = mapOf(
            CloudProvider.OPENAI to "gpt-4o-mini-transcribe",
            CloudProvider.OPENROUTER to "openai/whisper-large-v3",
            CloudProvider.CUSTOM to "whisper-1",
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
            CloudProvider.CUSTOM to listOf("whisper-1", "whisper-large-v3-turbo", "whisper-large-v3"),
        ),
    ),
    POST_PROCESSING(
        providerPref = "post_processing_provider",
        outputModality = "text",
        modelPrefs = mapOf(
            CloudProvider.OPENAI to "openai_post_processing_model",
            CloudProvider.OPENROUTER to "openrouter_post_processing_model",
            CloudProvider.CUSTOM to "custom_post_processing_model",
        ),
        defaults = mapOf(
            CloudProvider.OPENAI to "gpt-4o-mini",
            CloudProvider.OPENROUTER to "openai/gpt-4o-mini",
            CloudProvider.CUSTOM to "gpt-4o-mini",
        ),
        presets = mapOf(
            CloudProvider.OPENAI to listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4.1-nano"),
            CloudProvider.OPENROUTER to listOf(
                "openai/gpt-4o-mini",
                "openai/gpt-4.1-mini",
                "google/gemini-2.5-flash-lite",
            ),
            CloudProvider.CUSTOM to listOf("gpt-4o-mini"),
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
    val baseUrl = if (provider == CloudProvider.CUSTOM) normalizeBaseUrl(getString(CUSTOM_BASE_URL_PREF, "").orEmpty())
        else defaultBaseUrl(provider)
    return CloudTarget(provider, getString(provider.keyPref, "") ?: "", model, baseUrl)
}

/** `Authorization: Bearer` only when there is a key; custom servers may run without one. */
fun okhttp3.Request.Builder.auth(target: CloudTarget): okhttp3.Request.Builder =
    if (target.apiKey.isBlank()) this else header("Authorization", "Bearer ${target.apiKey}")

fun SharedPreferences.transcriptionTarget() = cloudTarget(CloudTask.TRANSCRIPTION)
fun SharedPreferences.postProcessingTarget() = cloudTarget(CloudTask.POST_PROCESSING)

/** Error message from an OpenAI-style error body (`{"error": {"message": ...}}`), else `HTTP <code>`. */
fun apiErrorMessage(code: Int, body: String): String =
    runCatching { org.json.JSONObject(body).getJSONObject("error").getString("message") }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"

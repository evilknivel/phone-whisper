package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object PostProcessor {
    data class Result(val text: String?, val error: String?)

    const val SIMPLE_PROMPT = "Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors. Keep the original meaning and the original language: never translate. Return only the cleaned text."

    const val DEV_PROMPT = """<task>A text is provided which is a draft transcription from a speech to text model.
Refine and polish the provided text, if needed, as follows:
  0. Keep the language of the transcript. Never translate it.
  1. Correct any spelling errors, and look out for mis-identified project names,
     including: Solveit, fast.ai, Answer.AI, nbdev, fastcore, FastHTML, Pi, Codex, Claude Code, Hetzner.
  2. Fix grammatical mistakes.
  3. Improve punctuation where necessary.
  4. Ensure consistent formatting.
  5. Clarify ambiguous phrasing without changing the meaning.
  6. If the transcript contains a question, edit it for clarity but do not provide an
     answer.
  7. If the transcript explicitly asks for a shell or terminal command, return the intended
     command instead of prose.

Return *only* the cleaned-up version of the transcript. Do *not* add any explanations or
comments about your edits. Do *not* answer any question in the text, *only* transcribe it.
</task>
<examples>
<example>
<input>How do eye increase the font size in fast html?</input>
<output>How do I increase the font size in FastHTML?</output>
</example>
<example>
<input>Where is Paris?</input>
<output>Where is Paris?</output>
</example>
<example>
<input>Here is the full list of options colon</input>
<output>Here is the full list of options:</output>
</example>
<example>
<input>Command mode ssh into morty user at rubicon</input>
<output>ssh morty@rubicon</output>
</example>
<example>
<input>List files in current directory</input>
<output>ls -l .</output>
</example>
</examples>"""

    const val DEFAULT_PROMPT = SIMPLE_PROMPT

    /** Earlier versions of the presets, without the keep-the-language rule. */
    private val LEGACY_PRESETS = mapOf(
        "Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors. Keep the original meaning. Return only the cleaned text." to SIMPLE_PROMPT,
        DEV_PROMPT.replace("  0. Keep the language of the transcript. Never translate it.\n", "") to DEV_PROMPT,
    )

    /** A stored prompt that is an old version of a preset becomes the current preset; anything else is kept. */
    fun migratePrompt(stored: String?): String? = stored?.let { LEGACY_PRESETS[it] ?: it }

    fun parseResponse(json: String): Result {
        return try {
            val obj = JSONObject(json)
            if (obj.has("choices")) {
                val choices = obj.getJSONArray("choices")
                if (choices.length() > 0) {
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    Result(message.getString("content").trim(), null)
                } else {
                    Result(null, "No choices in response")
                }
            } else if (obj.has("error")) {
                Result(null, obj.getJSONObject("error").getString("message"))
            } else {
                Result(null, "Unknown response format")
            }
        } catch (e: Exception) {
            Result(null, e.message ?: "Parse error")
        }
    }

    /**
     * True if a 400 error says the model rejects `temperature` (OpenAI's reasoning models only
     * accept the default), so the request is worth repeating without it.
     */
    fun rejectsTemperature(code: Int, body: String): Boolean =
        code == 400 && apiErrorMessage(code, body).contains("temperature", ignoreCase = true)

    fun requestBody(text: String, prompt: String, model: String, withTemperature: Boolean): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", prompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", text)
            })
        }
        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            if (withTemperature) put("temperature", 0.0)
        }.toString()
    }

    /** Both providers speak the OpenAI chat-completions format; only URL and model ID differ. */
    fun process(text: String, prompt: String, target: CloudTarget, callback: (Result) -> Unit) =
        send(text, prompt, target, withTemperature = true, callback)

    private fun send(text: String, prompt: String, target: CloudTarget, withTemperature: Boolean, callback: (Result) -> Unit) {
        val request = try {
            Request.Builder()
                .url("${target.baseUrl}/chat/completions")
                .auth(target)
                .post(requestBody(text, prompt, target.model, withTemperature).toRequestBody("application/json".toMediaType()))
                .build()
        } catch (e: IllegalArgumentException) {
            callback(Result(null, "Invalid API address: ${e.message}")); return
        }

        Http.client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result(null, e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.use { it.body.string() }
                when {
                    response.isSuccessful -> callback(parseResponse(responseBody))
                    withTemperature && rejectsTemperature(response.code, responseBody) ->
                        send(text, prompt, target, withTemperature = false, callback)
                    else -> callback(Result(null, apiErrorMessage(response.code, responseBody)))
                }
            }
        })
    }
}

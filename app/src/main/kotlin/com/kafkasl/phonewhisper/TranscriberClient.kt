package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

object TranscriberClient {
    /** [statusCode] is the HTTP status of a failed request, null for network errors and successes. */
    data class Result(val text: String?, val error: String?, val statusCode: Int? = null)

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text"), null)
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    /** Successful bodies are parsed; error bodies give the API's message, or `HTTP <code>` if they have none. */
    fun parseResponse(code: Int, body: String): Result =
        if (code in 200..299) parseResponse(body) else Result(null, apiErrorMessage(code, body), code)

    fun transcribe(wavData: ByteArray, target: CloudTarget, callback: (Result) -> Unit) {
        if (target.baseUrl.isBlank()) { callback(Result(null, "Set the custom API address in Phone Whisper")); return }
        val request = try {
            when (target.provider) {
                CloudProvider.OPENAI, CloudProvider.CUSTOM -> openAiRequest(wavData, target)
                CloudProvider.OPENROUTER -> openRouterRequest(wavData, target)
            }
        } catch (e: IllegalArgumentException) {
            callback(Result(null, "Invalid API address: ${e.message}")); return
        }

        Http.client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(null, e.message ?: "Network error"))
            override fun onResponse(call: Call, response: Response) {
                val body = response.use { it.body.string() }
                callback(parseResponse(response.code, body))
            }
        })
    }

    private fun openAiRequest(wavData: ByteArray, target: CloudTarget): Request {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", target.model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
            .build()

        return Request.Builder()
            .url("${target.baseUrl}/audio/transcriptions")
            .auth(target)
            .post(body)
            .build()
    }

    /** OpenRouter's STT endpoint also takes multipart now, but JSON with base64 audio is its documented main format. */
    private fun openRouterRequest(wavData: ByteArray, target: CloudTarget): Request =
        Request.Builder()
            .url("${target.baseUrl}/audio/transcriptions")
            .auth(target)
            .post(openRouterBody(wavData, target.model).toRequestBody("application/json".toMediaType()))
            .build()

    fun openRouterBody(wavData: ByteArray, model: String): String = JSONObject().apply {
        put("model", model)
        put("input_audio", JSONObject().apply {
            put("data", Base64.getEncoder().encodeToString(wavData))
            put("format", "wav")
        })
    }.toString()
}

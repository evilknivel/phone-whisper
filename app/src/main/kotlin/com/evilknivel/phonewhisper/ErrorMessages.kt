package com.evilknivel.phonewhisper

/** Short, readable messages for the overlay instead of raw HTTP or exception text. */
object ErrorMessages {
    fun cloud(error: String?, statusCode: Int?): String {
        val raw = error.orEmpty()
        val lower = raw.lowercase()
        return when {
            statusCode == 401 || statusCode == 403 || "incorrect api key" in lower || "unauthorized" in lower ->
                "API key rejected"
            statusCode == 402 || "insufficient" in lower || "quota" in lower || "credits" in lower ->
                "No credit left on the API account"
            statusCode == 429 || "rate limit" in lower -> "Rate limited, try again shortly"
            statusCode == 404 || "model" in lower && ("not found" in lower || "does not exist" in lower) ->
                "Model not found: check the model setting"
            statusCode != null && statusCode >= 500 -> "Server error ($statusCode)"
            "timeout" in lower || "timed out" in lower -> "Request timed out"
            "unable to resolve host" in lower || "failed to connect" in lower || "network" in lower ||
                "connection" in lower -> "No connection"
            raw.isBlank() -> "No transcript returned"
            else -> raw.take(120)
        }
    }

    fun local(error: String?): String =
        "Local transcription failed: ${error.orEmpty().ifBlank { "unknown error" }.take(80)}"
}

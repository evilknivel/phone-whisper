package com.evilknivel.phonewhisper

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/** API keys of the hosted providers, and the address and key of a custom OpenAI-compatible server. */
class ApiKeysActivity : SettingsScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen("API keys") {
            addView(sectionHeader("Hosted providers"))
            addView(keyRow(CloudProvider.OPENAI, "OpenAI API key"))
            addView(keyRow(CloudProvider.OPENROUTER, "OpenRouter API key"))

            addView(sectionHeader("Custom server"))
            val addressRow = settingsRow("API address", "") { promptAddress() }
            val addressSub = addressRow.subtitle()
            onRefresh {
                val url = normalizeBaseUrl(prefs().getString(CUSTOM_BASE_URL_PREF, "").orEmpty())
                addressSub.visibility = View.VISIBLE
                addressSub.text = when {
                    url.isEmpty() -> "Not set"
                    url.startsWith("http://") -> "$url\nUnencrypted: only use this on your own network"
                    else -> url
                }
            }
            addView(addressRow)
            addView(keyRow(CloudProvider.CUSTOM, "API key (optional)"))
            addView(note(
                "Any OpenAI-compatible server, e.g. Groq (https://api.groq.com/openai/v1) or a Whisper " +
                    "server on your computer. Pick \"Custom\" as transcription or cleanup provider to use it."
            ))
        }
    }

    private fun keyRow(provider: CloudProvider, title: String) =
        settingsRow(title, "") { promptApiKey(provider) }.also { row ->
            val sub = row.subtitle()
            onRefresh { sub.visibility = View.VISIBLE; sub.text = maskedKey(provider) }
        }

    private fun maskedKey(provider: CloudProvider): String {
        val key = prefs().getString(provider.keyPref, "") ?: ""
        return if (key.isBlank()) "Tap to set"
               else if (key.length > 7) "...${key.takeLast(4)}"
               else "...***"
    }

    /**
     * The saved key is never shown: the field starts empty and masks its input, the dialog can't
     * be screenshotted or recorded, and the keyboard is asked not to learn or autofill it.
     */
    private fun promptApiKey(provider: CloudProvider) {
        val saved = prefs().getString(provider.keyPref, "").orEmpty()
        val input = EditText(this).apply {
            hint = if (saved.isBlank()) "Paste your API key" else "Saved key ${maskedKey(provider)} — leave empty to keep"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSingleLine = true
        }
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("${provider.label} API key")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) prefs().edit().putString(provider.keyPref, key).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
        if (saved.isNotBlank()) {
            builder.setNeutralButton("Remove") { _, _ ->
                prefs().edit().remove(provider.keyPref).apply()
                refresh()
            }
        }
        builder.create().apply {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            show()
        }
    }

    private fun promptAddress() {
        val input = EditText(this).apply {
            hint = "https://api.groq.com/openai/v1"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setText(prefs().getString(CUSTOM_BASE_URL_PREF, ""))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Custom API address")
            .setMessage("A host (http://192.168.1.10:8000), a /v1 base URL, or a full /audio/transcriptions URL.")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString(CUSTOM_BASE_URL_PREF, input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

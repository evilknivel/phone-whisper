package com.evilknivel.phonewhisper

import android.os.Bundle
import android.widget.Toast
import com.evilknivel.phonewhisper.Prefs.language
import com.evilknivel.phonewhisper.Prefs.translationLanguages

/** The target languages offered on the arc when the button is held, in the order they were picked. */
class TranslationSettingsActivity : SettingsScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen("Translation") {
            val status = settingsRow("How it works", "")
            val statusSub = status.subtitle()
            onRefresh {
                statusSub.visibility = android.view.View.VISIBLE
                statusSub.text = statusText()
            }
            addView(status)

            addView(sectionHeader("Languages on the arc"))
            addView(note(
                "Pick up to ${Translation.MAX_LANGUAGES}. They appear in this order around the button. " +
                    "Your own language (set on the main screen) is left out."
            ))
            for ((code, name) in Prefs.LANGUAGES.filter { it.first.isNotEmpty() }) {
                addView(toggleRow(
                    name, "",
                    get = { code in prefs().translationLanguages() },
                ) { on ->
                    val current = prefs().translationLanguages()
                    val updated = Translation.toggle(current, code)
                    if (on && updated == current) {
                        Toast.makeText(this@TranslationSettingsActivity,
                            "At most ${Translation.MAX_LANGUAGES} languages fit on the arc", Toast.LENGTH_SHORT).show()
                    }
                    prefs().edit().putString(Prefs.TRANSLATION_LANGUAGES, updated.joinToString(",")).apply()
                })
            }
        }
    }

    private fun statusText(): String {
        val selected = prefs().translationLanguages()
        val order = if (selected.isEmpty()) "No languages picked yet."
            else "On the arc: " + selected.joinToString(", ") { Translation.languageName(it) } + "."
        val spoken = prefs().language().takeIf { it.isNotEmpty() }?.let { " from ${Translation.languageName(it)}" }.orEmpty()
        val method = when {
            prefs().postProcessingTarget().isConfigured ->
                "The AI cleanup model translates$spoken into the language you pick."
            LocalTranscriber.whisperTranslators(this).isNotEmpty() ->
                "No AI cleanup provider is set up, so only English is offered: an installed Whisper model " +
                    "translates offline. Add an API key to translate into other languages."
            else ->
                "Needs an AI cleanup provider (API key), or Whisper Base or Small for English only."
        }
        return "Hold the button until the ring is full, slide your thumb onto a language and let go. " +
            "Your dictation is transcribed as usual, then translated.\n\n$method\n\n$order"
    }
}

package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

/**
 * Keys and defaults of the settings that MainActivity's sub-pages edit and the service reads.
 * The older keys (`use_local`, `model_name`, providers, models, API keys, prompts) are still
 * spelled out where they are used.
 */
object Prefs {
    const val FILE = "phonewhisper"

    /** "always" or "keyboard" (only while a keyboard is open). */
    const val OVERLAY_MODE = "overlay_mode"
    const val OVERLAY_ALWAYS = "always"
    const val OVERLAY_KEYBOARD = "keyboard"

    const val CANCEL_ON_SCREEN_OFF = "cancel_on_screen_off"
    const val HAPTICS = "haptics"
    const val EDGE_GLOW = "edge_glow"
    const val PRESERVE_CLIPBOARD = "preserve_clipboard"
    const val TRIM_SILENCE = "trim_silence"
    const val NUM_THREADS = "num_threads"

    /** Minutes without dictation before the local model is unloaded; 0 = never. */
    const val IDLE_RELEASE_MINUTES = "idle_release_minutes"
    val IDLE_RELEASE_CHOICES = listOf(0, 2, 5, 15, 30)

    /** "voice_recognition" (tuned for speech recognition) or "mic" (the raw microphone). */
    const val MIC_SOURCE = "mic_source"
    const val MIC_VOICE_RECOGNITION = "voice_recognition"
    const val MIC_RAW = "mic"

    /** ISO 639-1 code of the spoken language, or "" to let the model detect it. */
    const val LANGUAGE = "language"
    val LANGUAGES = listOf(
        "" to "Detect automatically",
        "de" to "German", "en" to "English", "fr" to "French", "es" to "Spanish", "it" to "Italian",
        "nl" to "Dutch", "pl" to "Polish", "pt" to "Portuguese", "cs" to "Czech", "sv" to "Swedish",
        "da" to "Danish", "fi" to "Finnish", "hu" to "Hungarian", "ro" to "Romanian", "el" to "Greek",
        "uk" to "Ukrainian", "ru" to "Russian", "tr" to "Turkish", "ar" to "Arabic", "hi" to "Hindi",
        "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean",
    )

    /** When dictations are cleaned up by a chat model: "never", "always", or "hold" (only when the button is held). */
    const val CLEANUP_MODE = "cleanup_mode"
    const val CLEANUP_NEVER = "never"
    const val CLEANUP_ALWAYS = "always"
    const val CLEANUP_HOLD = "hold"

    /** Older versions stored an on/off switch in `use_post_processing`: on becomes "always", off "hold". */
    fun SharedPreferences.cleanupMode(): String = getString(CLEANUP_MODE, null)
        ?: if (getBoolean("use_post_processing", false)) CLEANUP_ALWAYS else CLEANUP_HOLD

    val CLEANUP_CHOICES = listOf(
        CLEANUP_HOLD to "Only when I hold the button",
        CLEANUP_ALWAYS to "Always",
        CLEANUP_NEVER to "Never",
    )

    /** Target languages offered on the arc when the button is held, comma-separated in arc order. */
    const val TRANSLATION_LANGUAGES = "translation_languages"

    /** Set once the setup assistant was finished or skipped. */
    const val SETUP_DONE = "setup_done"

    fun SharedPreferences.translationLanguages() = Translation.parse(getString(TRANSLATION_LANGUAGES, null))

    fun SharedPreferences.language() = getString(LANGUAGE, "").orEmpty()
    fun SharedPreferences.overlayKeyboardOnly() = getString(OVERLAY_MODE, OVERLAY_KEYBOARD) == OVERLAY_KEYBOARD
    fun SharedPreferences.cancelOnScreenOff() = getBoolean(CANCEL_ON_SCREEN_OFF, true)
    fun SharedPreferences.haptics() = getBoolean(HAPTICS, true)
    fun SharedPreferences.edgeGlow() = getBoolean(EDGE_GLOW, false)
    fun SharedPreferences.preserveClipboard() = getBoolean(PRESERVE_CLIPBOARD, false)
    fun SharedPreferences.trimSilence() = getBoolean(TRIM_SILENCE, true)
    fun SharedPreferences.numThreads() = getInt(NUM_THREADS, LocalTranscriber.DEFAULT_THREADS)
    fun SharedPreferences.idleReleaseMinutes() = getInt(IDLE_RELEASE_MINUTES, 5)
    fun SharedPreferences.micSource() = getString(MIC_SOURCE, MIC_VOICE_RECOGNITION) ?: MIC_VOICE_RECOGNITION

    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

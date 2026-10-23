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

    fun SharedPreferences.overlayKeyboardOnly() = getString(OVERLAY_MODE, OVERLAY_KEYBOARD) == OVERLAY_KEYBOARD
    fun SharedPreferences.cancelOnScreenOff() = getBoolean(CANCEL_ON_SCREEN_OFF, true)
    fun SharedPreferences.haptics() = getBoolean(HAPTICS, true)
    fun SharedPreferences.preserveClipboard() = getBoolean(PRESERVE_CLIPBOARD, false)
    fun SharedPreferences.trimSilence() = getBoolean(TRIM_SILENCE, true)
    fun SharedPreferences.numThreads() = getInt(NUM_THREADS, LocalTranscriber.DEFAULT_THREADS)
    fun SharedPreferences.idleReleaseMinutes() = getInt(IDLE_RELEASE_MINUTES, 5)
    fun SharedPreferences.micSource() = getString(MIC_SOURCE, MIC_VOICE_RECOGNITION) ?: MIC_VOICE_RECOGNITION

    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

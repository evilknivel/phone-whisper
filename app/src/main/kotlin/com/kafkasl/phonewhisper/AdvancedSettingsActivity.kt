package com.kafkasl.phonewhisper

import android.os.Bundle
import com.kafkasl.phonewhisper.Prefs.idleReleaseMinutes
import com.kafkasl.phonewhisper.Prefs.micSource
import com.kafkasl.phonewhisper.Prefs.numThreads

class AdvancedSettingsActivity : SettingsScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cores = Runtime.getRuntime().availableProcessors()
        setScreen("Advanced") {
            addView(sectionHeader("Local models"))
            addView(choiceRow(
                "CPU threads",
                (1..cores).map { it to if (it == LocalTranscriber.DEFAULT_THREADS) "$it of $cores (default)" else "$it of $cores" },
                current = { prefs().numThreads().coerceIn(1, cores) },
                onPick = {
                    prefs().edit().putInt(Prefs.NUM_THREADS, it).apply()
                    WhisperAccessibilityService.instance?.reloadModel()
                },
            ))
            addView(note("More threads transcribe faster but use more battery."))
            addView(choiceRow(
                "Free model memory when idle",
                Prefs.IDLE_RELEASE_CHOICES.map { it to if (it == 0) "Never" else "After $it minutes" },
                current = { prefs().idleReleaseMinutes() },
                onPick = { prefs().edit().putInt(Prefs.IDLE_RELEASE_MINUTES, it).apply() },
            ))
            addView(note(
                "Large models use several hundred MB while loaded, which makes Android more likely to stop " +
                    "the service. Once freed, the model loads again while you speak; the first dictation " +
                    "after a pause can take a few seconds longer."
            ))

            addView(sectionHeader("Audio"))
            addView(switchRow(
                "Trim silence",
                "Cuts pauses before transcribing: faster, cheaper, and fewer invented words",
                Prefs.TRIM_SILENCE, true
            ))
            addView(choiceRow(
                "Microphone",
                listOf(
                    Prefs.MIC_VOICE_RECOGNITION to "Speech recognition (recommended)",
                    Prefs.MIC_RAW to "Standard microphone",
                ),
                current = { prefs().micSource() },
                onPick = { prefs().edit().putString(Prefs.MIC_SOURCE, it).apply() },
            ))
            addView(note("Try \"Standard microphone\" if recordings are too quiet on your phone."))
        }
    }
}

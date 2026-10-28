package com.evilknivel.phonewhisper

import android.os.Bundle

/** The maker-specific settings that keep the accessibility service running (see [BackgroundSettings]). */
class BackgroundSettingsActivity : SettingsScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen("Keep running") {
            addView(note(BACKGROUND_INTRO))
            addBackgroundSteps()
        }
    }

    companion object {
        const val BACKGROUND_INTRO =
            "Many phones stop apps in the background to save battery, or when you clear the recent apps. " +
                "Android then often doesn't restart Phone Whisper: the accessibility settings show it as not " +
                "working until you turn it off and on again. These settings prevent that."
    }
}

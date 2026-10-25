package com.evilknivel.phonewhisper

import android.os.Bundle
import com.evilknivel.phonewhisper.Prefs.overlayKeyboardOnly

/** How the floating button behaves and how dictated text gets into the field. */
class ButtonSettingsActivity : SettingsScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen("Button & insertion") {
            addView(sectionHeader("Button"))
            addView(choiceRow(
                "Show the button",
                listOf(
                    Prefs.OVERLAY_KEYBOARD to "Only while a keyboard is open",
                    Prefs.OVERLAY_ALWAYS to "Always",
                ),
                current = { if (prefs().overlayKeyboardOnly()) Prefs.OVERLAY_KEYBOARD else Prefs.OVERLAY_ALWAYS },
                onPick = { prefs().edit().putString(Prefs.OVERLAY_MODE, it).apply() },
            ))
            addView(note(
                "While recording, transcribing or offering a retry, the button stays visible. If it never " +
                    "shows in an app (e.g. with a hardware keyboard), choose \"Always\"."
            ))
            addView(switchRow(
                "Vibrate",
                "Click when recording starts and stops, double pulse for AI cleanup, long buzz when discarding",
                Prefs.HAPTICS, true
            ))
            addView(switchRow(
                "Glowing screen edge",
                "While recording, the screen edges glow red (purple with AI cleanup), so you see it even when your thumb covers the button",
                Prefs.EDGE_GLOW, false
            ))
            addView(switchRow(
                "Cancel when the screen turns off",
                "Discards the recording, so audio from a pocket isn't transcribed",
                Prefs.CANCEL_ON_SCREEN_OFF, true
            ))

            addView(sectionHeader("Insertion"))
            addView(switchRow(
                "Keep my clipboard",
                "Write text straight into the field. Falls back to the clipboard where that isn't possible",
                Prefs.PRESERVE_CLIPBOARD, false
            ))
            addView(note(
                "By default the text is copied to the clipboard and pasted, which works in the most apps. " +
                    "Copied transcripts are marked sensitive, so Android hides them in the clipboard preview."
            ))
        }
    }
}

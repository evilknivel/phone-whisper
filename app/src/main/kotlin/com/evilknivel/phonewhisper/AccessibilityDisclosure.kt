package com.evilknivel.phonewhisper

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The prominent disclosure Google Play requires before an app that isn't an accessibility tool
 * sends the user to enable its accessibility service: what the service accesses and why, and an
 * explicit agreement. The agreement is remembered; [open] shows the disclosure until then.
 */
object AccessibilityDisclosure {
    const val CONSENT_PREF = "accessibility_consent"

    const val TEXT =
        "Phone Whisper uses Android's Accessibility Service for two things:\n\n" +
            "• to insert your dictated text into the text field that has focus, after you tap the button\n" +
            "• to see whether a keyboard is open, so the button can be shown only then\n\n" +
            "It does nothing until you tap the button. It does not read, log or store what is on your " +
            "screen, and it sends no screen content anywhere. Your audio goes only where you chose: " +
            "nowhere in offline mode, to your chosen provider in online mode."

    fun hasConsent(activity: Activity) = Prefs.of(activity).getBoolean(CONSENT_PREF, false)

    fun giveConsent(activity: Activity) = Prefs.of(activity).edit().putBoolean(CONSENT_PREF, true).apply()

    /** Open the system's accessibility settings, after showing the disclosure if not agreed yet. */
    fun open(activity: Activity) {
        if (hasConsent(activity)) {
            openSettings(activity)
            return
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Accessibility permission")
            .setMessage(TEXT)
            .setPositiveButton("Agree and continue") { _, _ ->
                giveConsent(activity)
                openSettings(activity)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    fun openSettings(activity: Activity) =
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /**
     * App info, where "Allow restricted settings" lives (⋮ menu). Android blocks the accessibility
     * permission for apps installed from a file until that is allowed.
     */
    fun openAppInfo(activity: Activity) = activity.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
    )
}

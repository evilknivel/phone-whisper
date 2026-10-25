package com.evilknivel.phonewhisper

/** Whether the accessibility service is switched on in system settings, and whether it actually runs. */
enum class AccessibilityState { OFF, ENABLED_NOT_RUNNING, RUNNING }

/**
 * What still blocks dictation, in the order the settings screen lists it. Empty means ready.
 * Shown as the status line, so the user sees *what* is missing instead of just "Setup required".
 */
fun missingSetup(
    audioGranted: Boolean,
    accessibility: AccessibilityState,
    useLocal: Boolean,
    hasLocalModel: Boolean,
    transcription: CloudTarget,
    usePostProcessing: Boolean,
    postProcessing: CloudTarget,
): List<String> = buildList {
    if (!audioGranted) add("audio permission")
    when (accessibility) {
        AccessibilityState.OFF -> add("accessibility service")
        AccessibilityState.ENABLED_NOT_RUNNING -> add("accessibility service is on but not running")
        AccessibilityState.RUNNING -> {}
    }
    if (useLocal && !hasLocalModel) add("a local model (download one below)")
    if (!useLocal) missingCloud(transcription)?.let { add(it) }
    if (usePostProcessing) missingCloud(postProcessing)?.let { add("$it for cleanup") }
}

private fun missingCloud(target: CloudTarget): String? = when {
    target.baseUrl.isBlank() -> "custom API address"
    target.keyRequired && target.apiKey.isBlank() -> "${target.provider.label} API key"
    else -> null
}

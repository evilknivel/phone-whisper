package com.evilknivel.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class SetupStatusTest {
    private val openAi = CloudTarget(CloudProvider.OPENAI, "", "gpt-4o-mini-transcribe")
    private val openRouterWithKey = CloudTarget(CloudProvider.OPENROUTER, "sk-or", "openai/gpt-4o-mini")

    @Test fun `ready when local model and service are there`() {
        assertEquals(emptyList<String>(), missingSetup(true, AccessibilityState.RUNNING, true, true, openAi, false, openAi))
    }

    @Test fun `names each missing step`() {
        assertEquals(
            listOf("audio permission", "accessibility service", "a local model (download one below)"),
            missingSetup(false, AccessibilityState.OFF, true, false, openAi, false, openAi)
        )
    }

    @Test fun `distinguishes a service that is on but not running`() {
        assertEquals(
            listOf("accessibility service is on but not running"),
            missingSetup(true, AccessibilityState.ENABLED_NOT_RUNNING, true, true, openAi, false, openAi)
        )
    }

    @Test fun `cloud and cleanup need the key of their provider`() {
        assertEquals(
            listOf("OpenAI API key"),
            missingSetup(true, AccessibilityState.RUNNING, false, false, openAi, true, openRouterWithKey)
        )
        assertEquals(
            listOf("OpenAI API key for cleanup"),
            missingSetup(true, AccessibilityState.RUNNING, true, true, openAi, true, openAi)
        )
    }
}

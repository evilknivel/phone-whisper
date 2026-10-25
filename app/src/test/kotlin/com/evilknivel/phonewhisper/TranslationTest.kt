package com.evilknivel.phonewhisper

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class TranslationTest {

    @Test fun `AI translates whenever a cleanup provider is set up, the settings' model still transcribes`() {
        assertEquals(Translation.Method.AI, Translation.method("es", aiConfigured = true, whisperInstalled = false))
        assertEquals(Translation.Method.AI, Translation.method("en", aiConfigured = true, whisperInstalled = true))
    }

    @Test fun `without a provider only Whisper into English is possible`() {
        assertEquals(Translation.Method.WHISPER, Translation.method("en", aiConfigured = false, whisperInstalled = true))
        assertNull(Translation.method("en", aiConfigured = false, whisperInstalled = false))
        assertNull(Translation.method("es", aiConfigured = false, whisperInstalled = true))
    }

    @Test fun `prompt keeps the cleanup rules and overrides the keep-the-language rule`() {
        val prompt = Translation.prompt(PostProcessor.SIMPLE_PROMPT, "de", "es")
        assertTrue(prompt.startsWith(PostProcessor.SIMPLE_PROMPT))
        assertTrue(prompt.endsWith("translate the result from German into Spanish. Return only the translation."))
        assertTrue("from" !in Translation.prompt("x", "", "fr").substringAfter("result"))
    }

    @Test fun `stored languages parse in order and toggle up to the maximum`() {
        assertEquals(listOf("es", "en"), Translation.parse(" es, en,,es "))
        assertEquals(emptyList<String>(), Translation.parse(null))
        assertEquals(listOf("es", "fr"), Translation.toggle(listOf("es"), "fr"))
        assertEquals(listOf("fr"), Translation.toggle(listOf("es", "fr"), "es"))
        val full = listOf("a", "b", "c", "d", "e", "f")
        assertEquals(full, Translation.toggle(full, "g"))
    }

    @Test fun `the spoken language is left off the arc`() {
        assertEquals(listOf("en", "es"), Translation.arcLanguages(listOf("de", "en", "es"), "de"))
        assertEquals(listOf("de", "en"), Translation.arcLanguages(listOf("de", "en"), ""))
    }

    @Test fun `arc opens away from the edge, top to bottom`() {
        val right = RadialMenu.angles(3, onRightEdge = true)
        // Button on the right: every chip is left of it, the first above, the last below.
        right.forEach { assertTrue(cos(it) < 0.2) }
        assertTrue(sin(right.first()) < 0 && sin(right.last()) > 0)
        assertEquals(PI, right[1], 1e-9)

        val left = RadialMenu.angles(3, onRightEdge = false)
        left.forEach { assertTrue(cos(it) > -0.2) }
        assertTrue(sin(left.first()) < 0 && sin(left.last()) > 0)
        assertEquals(listOf(PI), RadialMenu.angles(1, onRightEdge = true))
    }

    @Test fun `arc tilts down near the top of the screen`() {
        assertEquals(0.0, RadialMenu.tilt(roomAbove = 500f, roomBelow = 500f, reach = 300f), 0.0)
        val tilt = RadialMenu.tilt(roomAbove = 0f, roomBelow = 2000f, reach = 300f)
        assertEquals(PI / 2, tilt, 1e-9)
        // Tilted on the right edge, the top chip is no longer above the button.
        assertTrue(sin(RadialMenu.angles(3, onRightEdge = true, tilt = tilt).first()) >= -1e-9)
        assertTrue(RadialMenu.tilt(roomAbove = 2000f, roomBelow = 100f, reach = 300f) < 0)
    }

    @Test fun `hit picks the nearest chip outside the dead zone`() {
        val angles = RadialMenu.angles(3, onRightEdge = true)
        assertEquals(-1, RadialMenu.hit(-10f, 0f, angles, deadZone = 50f))
        assertEquals(1, RadialMenu.hit(-100f, 0f, angles, deadZone = 50f))
        assertEquals(0, RadialMenu.hit(-20f, -100f, angles, deadZone = 50f))
        assertEquals(2, RadialMenu.hit(-20f, 100f, angles, deadZone = 50f))
        assertEquals(-1, RadialMenu.hit(-100f, 0f, emptyList(), deadZone = 50f))
    }

    @Test fun `English-only Whisper cannot translate`() {
        fun config(encoder: String) = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(whisper = OfflineWhisperModelConfig(encoder = encoder, decoder = "d"))
        )
        assertTrue(LocalTranscriber.isMultilingualWhisper(config("/m/sherpa-onnx-whisper-small/small-encoder.int8.onnx")))
        assertFalse(LocalTranscriber.isMultilingualWhisper(config("/m/sherpa-onnx-whisper-base.en/base.en-encoder.int8.onnx")))
        assertFalse(LocalTranscriber.isMultilingualWhisper(config("")))
    }
}

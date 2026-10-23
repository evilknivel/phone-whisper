package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CustomServerTest {
    @Test fun `normalizes custom API addresses`() {
        assertEquals("https://api.groq.com/openai/v1", normalizeBaseUrl("https://api.groq.com/openai/v1/"))
        assertEquals("http://192.168.1.10:8000/v1", normalizeBaseUrl(" http://192.168.1.10:8000 "))
        assertEquals("https://example.com/v1", normalizeBaseUrl("example.com"))
        assertEquals("http://pc:8000/v1", normalizeBaseUrl("http://pc:8000/v1/audio/transcriptions"))
        assertEquals("https://x.io/api/v1", normalizeBaseUrl("https://x.io/api/v1/chat/completions"))
        assertEquals("", normalizeBaseUrl("  "))
    }

    @Test fun `custom target uses the address and needs no key`() {
        val prefs = FakePrefs(
            "transcription_provider" to "custom",
            CUSTOM_BASE_URL_PREF to "http://pc:8000",
            "custom_transcription_model" to "Systran/faster-whisper-small",
        )
        val target = prefs.transcriptionTarget()
        assertEquals("http://pc:8000/v1", target.baseUrl)
        assertEquals("Systran/faster-whisper-small", target.model)
        assertTrue(target.isConfigured)
        assertFalse(FakePrefs("transcription_provider" to "custom").transcriptionTarget().isConfigured)
        assertEquals(
            listOf("custom API address"),
            missingSetup(true, AccessibilityState.RUNNING, false, false,
                FakePrefs("transcription_provider" to "custom").transcriptionTarget(), false,
                FakePrefs().postProcessingTarget())
        )
    }

    @Test fun `custom model list is not filtered`() {
        val json = """{"data":[{"id":"whisper-large-v3-turbo"},{"id":"llama-3.3-70b-versatile"}]}"""
        assertEquals(listOf("llama-3.3-70b-versatile", "whisper-large-v3-turbo"), OpenAiModels.parseIds(json))
    }
}

class ErrorMessagesTest {
    @Test fun `maps common failures to short messages`() {
        assertEquals("API key rejected", ErrorMessages.cloud("Incorrect API key provided", 401))
        assertEquals("Rate limited, try again shortly", ErrorMessages.cloud("x", 429))
        assertEquals("Server error (503)", ErrorMessages.cloud("HTTP 503", 503))
        assertEquals("Request timed out", ErrorMessages.cloud("timeout", null))
        assertEquals("No connection", ErrorMessages.cloud("Unable to resolve host \"api.openai.com\"", null))
        assertEquals("Model not found: check the model setting", ErrorMessages.cloud("The model `foo` does not exist", 404))
        assertEquals("Audio file is too short", ErrorMessages.cloud("Audio file is too short", 400))
    }
}

class InsertTextTest {
    @Test fun `inserts at the caret and replaces a selection`() {
        assertEquals("Hello big world", textAfterInsert("Hello world", null, false, 6, 6, "big "))
        assertEquals("Hello there", textAfterInsert("Hello world", null, false, 6, 11, "there"))
    }

    @Test fun `placeholder counts as empty`() {
        assertEquals("hi", textAfterInsert("Message", "Message", false, -1, -1, "hi"))
        assertEquals("hi", textAfterInsert("Type here", null, true, 0, 0, "hi"))
        assertEquals("hi", textAfterInsert("", null, false, -1, -1, "hi"))
    }

    @Test fun `unknown caret in a non-empty field means paste instead`() {
        // Telegram reports its placeholder as text without hint info; guessing would keep it.
        assertNull(textAfterInsert("Message", null, false, -1, -1, "hi"))
        assertNull(textAfterInsert("abc", null, false, 5, 5, "hi"))
    }
}

class SilenceTrimmerTest {
    @Test fun `pads, clamps and merges speech ranges`() {
        assertEquals(
            listOf(0..35, 60..90),
            SilenceTrimmer.speechRanges(listOf(70..80, 5..20, 15..25), total = 100, pad = 10)
        )
        assertEquals(emptyList<IntRange>(), SilenceTrimmer.speechRanges(emptyList(), 100, 10))
    }

    @Test fun `joins ranges with a gap of silence`() {
        val samples = FloatArray(10) { it.toFloat() }
        val joined = SilenceTrimmer.joinRanges(samples, listOf(1..2, 7..8), gap = 3)
        assertArrayEquals(floatArrayOf(1f, 2f, 0f, 0f, 0f, 7f, 8f), joined, 0f)
    }
}

class PromptMigrationTest {
    @Test fun `old preset versions become the current presets`() {
        val oldDev = javaClass.classLoader!!.getResource("legacy_dev_prompt.txt")!!.readText()
        assertEquals(PostProcessor.DEV_PROMPT, PostProcessor.migratePrompt(oldDev))
        assertEquals(
            PostProcessor.SIMPLE_PROMPT,
            PostProcessor.migratePrompt("Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors. Keep the original meaning. Return only the cleaned text.")
        )
        assertEquals("my own prompt", PostProcessor.migratePrompt("my own prompt"))
        assertNull(PostProcessor.migratePrompt(null))
        assertTrue("never translate" in PostProcessor.SIMPLE_PROMPT)
        assertEquals(PostProcessor.SIMPLE_PROMPT, PostProcessor.DEFAULT_PROMPT)
    }
}

class CatalogTest {
    @Test fun `every model says which languages it understands`() {
        assertTrue(MODEL_CATALOG.all { it.languages.isNotBlank() })
        assertTrue(MODEL_CATALOG.filter { "German" in it.languages }.map { it.name }.containsAll(listOf("Parakeet 0.6B", "Parakeet Ultra")))
    }

    @Test fun `loose-file models are pinned to a commit and sized correctly`() {
        for (model in MODEL_CATALOG.filter { it.files.isNotEmpty() }) {
            assertTrue(model.name, Regex("/resolve/[0-9a-f]{40}$").containsMatchIn(model.baseUrl!!))
            val mb = model.files.sumOf { it.size } / 1_000_000.0
            assertEquals(model.name, mb, model.sizeMb.toDouble(), 1.0)
        }
    }

    @Test fun `prune keeps only the files the recognizer loads`() {
        val dir = Files.createTempDirectory("model").toFile()
        try {
            listOf("base.en-encoder.onnx", "base.en-encoder.int8.onnx", "base.en-decoder.onnx",
                "base.en-decoder.int8.onnx", "base.en-tokens.txt", ModelDownloader.INSTALLED_MARKER)
                .forEach { File(dir, it).writeText("x") }
            File(dir, "test_wavs").mkdirs()
            File(dir, "test_wavs/0.wav").writeText("x")

            ModelDownloader.pruneUnusedFiles(dir)

            assertEquals(
                setOf("base.en-encoder.int8.onnx", "base.en-decoder.int8.onnx", "base.en-tokens.txt", ModelDownloader.INSTALLED_MARKER),
                dir.list()!!.toSet()
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}

class LevelTest {
    @Test fun `silence is zero and loud audio saturates`() {
        assertEquals(0f, LevelBarsView.levelOf(ByteArray(640), 640), 0f)
        val loud = ByteArray(640) { if (it % 2 == 1) 0x40 else 0 } // 0x4000 = half scale
        assertEquals(1f, LevelBarsView.levelOf(loud, loud.size), 0f)
    }
}

class LanguageTest {
    @Test fun `cloud requests carry the language only when one is set`() {
        val wav = ByteArray(4)
        assertEquals("de", org.json.JSONObject(TranscriberClient.openRouterBody(wav, "m", "de")).getString("language"))
        assertFalse(org.json.JSONObject(TranscriberClient.openRouterBody(wav, "m", "")).has("language"))
    }

    @Test fun `whisper transcribes in the chosen or detected language instead of translating`() {
        val dir = Files.createTempDirectory("model").toFile()
        try {
            listOf("small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt").forEach { File(dir, it).writeText("x") }
            val auto = LocalTranscriber.detectModelConfig(dir, 2)!!.modelConfig.whisper
            assertEquals("", auto.language)
            assertEquals("transcribe", auto.task)
            assertEquals("de", LocalTranscriber.detectModelConfig(dir, 2, "de")!!.modelConfig.whisper.language)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `the English-only Whisper is only kept for existing installs`() {
        val legacy = MODEL_CATALOG.filter { it.legacy }
        assertEquals(listOf("sherpa-onnx-whisper-base.en"), legacy.map { it.archive })
        assertTrue(MODEL_CATALOG.filter { !it.legacy && it.name.startsWith("Whisper") }.all { it.languages == "99 languages" })
    }
}

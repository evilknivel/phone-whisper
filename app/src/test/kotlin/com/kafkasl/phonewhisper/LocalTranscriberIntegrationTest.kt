package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads every catalog model through [LocalTranscriber] and the sherpa-onnx JNI library and
 * transcribes a test recording. Skipped unless these are set (see CLAUDE.md):
 * - SHERPA_JNI_DIR: dir with the desktop libsherpa-onnx-jni.so of the same sherpa-onnx version
 * - PHONE_WHISPER_MODELS_DIR: dir with the models, one sub-dir per catalog `archive` name
 * - PHONE_WHISPER_TEST_WAV: 16-bit mono WAV of "Ask not what your country can do for you..."
 * - PHONE_WHISPER_TEST_WAV_DE (optional): the German test WAV of the Parakeet 0.6B archive
 */
class LocalTranscriberIntegrationTest {

    @Test fun `every catalog model loads and transcribes`() {
        val modelsDir = System.getenv("PHONE_WHISPER_MODELS_DIR")?.let(::File)
        val wav = System.getenv("PHONE_WHISPER_TEST_WAV")?.let(::File)
        assumeTrue(System.getenv("SHERPA_JNI_DIR") != null && modelsDir != null && wav != null)

        val (samples, sampleRate) = readWav(wav!!)
        for (model in MODEL_CATALOG) {
            val dir = File(modelsDir, model.archive)
            assertTrue("${model.name}: missing $dir", dir.isDirectory)
            val transcriber = assertNotNull(model.name, LocalTranscriber.create(dir, 2))
            try {
                val text = transcriber!!.transcribe(samples, sampleRate)
                println("${model.name}: $text")
                val words = text.lowercase().replace(Regex("[^a-z ]"), " ")
                // Both sentences: a model that drops the end of the recording fails here.
                val count = Regex("your country").findAll(words.replace(Regex(" +"), " ")).count()
                assertTrue("${model.name}: '$text'", count >= 2)
            } finally {
                transcriber!!.release()
            }
        }
    }

    /** Optional PHONE_WHISPER_TEST_WAV_DE: "Alles hat ein Ende, nur die Wurst hat zwei." */
    @Test fun `multilingual models transcribe German as German`() {
        val modelsDir = System.getenv("PHONE_WHISPER_MODELS_DIR")?.let(::File)
        val wav = System.getenv("PHONE_WHISPER_TEST_WAV_DE")?.let(::File)
        assumeTrue(System.getenv("SHERPA_JNI_DIR") != null && modelsDir != null && wav != null)

        val (samples, sampleRate) = readWav(wav!!)
        for (model in MODEL_CATALOG.filter { "English only" !in it.languages }) {
            // "" = detect; Whisper also gets the explicit language a user can pick.
            val languages = if (model.name.startsWith("Whisper")) listOf("", "de") else listOf("")
            for (language in languages) {
                val transcriber = LocalTranscriber.create(File(modelsDir, model.archive), 2, language)!!
                try {
                    val text = transcriber.transcribe(samples, sampleRate)
                    println("${model.name} [${language.ifEmpty { "auto" }}]: $text")
                    assertTrue("${model.name}: '$text'", "Wurst" in text && "Ende" in text)
                } finally {
                    transcriber.release()
                }
            }
        }
    }

    @Test fun `silence trimmer cuts pauses and keeps the speech`() {
        val modelsDir = System.getenv("PHONE_WHISPER_MODELS_DIR")?.let(::File)
        val wav = System.getenv("PHONE_WHISPER_TEST_WAV")?.let(::File)
        assumeTrue(System.getenv("SHERPA_JNI_DIR") != null && modelsDir != null && wav != null)

        val (raw, rate) = readWav(wav!!)
        val speech = resampleTo16k(raw, rate)
        val pause = FloatArray(2 * 16000)
        val padded = pause + speech + pause + speech + pause

        val trimmer = SilenceTrimmer.create(File("src/main/assets/silero_vad.onnx"))!!
        try {
            assertNull("pure silence", trimmer.trim(FloatArray(5 * 16000)))
            val trimmed = trimmer.trim(padded)!!
            println("Trimmed ${padded.size / 16000.0}s -> ${trimmed.size / 16000.0}s (speech ${2 * speech.size / 16000.0}s)")
            assertTrue("removes most of the 6 s of pauses", trimmed.size < padded.size - 4 * 16000)
            assertTrue("keeps the speech", trimmed.size >= 2 * speech.size * 0.9)

            val model = MODEL_CATALOG.first()
            val transcriber = LocalTranscriber.create(File(modelsDir, model.archive), 2)!!
            try {
                val text = transcriber.transcribe(trimmed, 16000)
                println("Trimmed audio with ${model.name}: $text")
                assertEquals(2, Regex("your country", RegexOption.IGNORE_CASE).findAll(text.replace(",", "")).count() / 2)
            } finally {
                transcriber.release()
            }
        } finally {
            trimmer.release()
        }
    }

    private fun resampleTo16k(samples: FloatArray, rate: Int): FloatArray {
        if (rate == 16000) return samples
        val out = FloatArray((samples.size.toLong() * 16000 / rate).toInt())
        for (i in out.indices) {
            val pos = i.toDouble() * rate / 16000
            val j = pos.toInt().coerceAtMost(samples.size - 2)
            val f = (pos - j).toFloat()
            out[i] = samples[j] * (1 - f) + samples[j + 1] * f
        }
        return out
    }

    private fun <T> assertNotNull(message: String, value: T?): T? {
        org.junit.Assert.assertNotNull(message, value)
        return value
    }

    /** Minimal reader for 16-bit PCM WAV; returns the first channel as floats and the sample rate. */
    private fun readWav(file: File): Pair<FloatArray, Int> {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12)
        var channels = 1
        var rate = 16000
        while (buf.remaining() >= 8) {
            val id = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val size = buf.int
            when (id) {
                "fmt " -> {
                    val start = buf.position()
                    buf.short // format
                    channels = buf.short.toInt()
                    rate = buf.int
                    buf.position(start + size)
                }
                "data" -> {
                    val frames = size / 2 / channels
                    val out = FloatArray(frames) { i -> buf.getShort(buf.position() + i * 2 * channels) / 32768f }
                    return out to rate
                }
                else -> buf.position(buf.position() + size + (size and 1))
            }
        }
        error("No data chunk in $file")
    }
}

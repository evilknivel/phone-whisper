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
                assertTrue("${model.name}: '$text'", "your country" in words.replace(Regex(" +"), " "))
            } finally {
                transcriber!!.release()
            }
        }
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

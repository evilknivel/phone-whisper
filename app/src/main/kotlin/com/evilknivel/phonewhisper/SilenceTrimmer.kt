package com.evilknivel.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Cuts silence out of a recording with Silero VAD before it is transcribed: local models get less
 * audio to decode, cloud requests get smaller, and models can't invent text for silent stretches.
 * Speech keeps [PAD_S] of context on both sides, and segments are joined with a short pause.
 * Not thread-safe.
 */
class SilenceTrimmer private constructor(private val vad: Vad) {

    /** The speech parts of 16 kHz [samples], or null if there is no speech at all. */
    fun trim(samples: FloatArray): FloatArray? {
        vad.reset()
        val window = WINDOW
        var i = 0
        while (i + window <= samples.size) {
            vad.acceptWaveform(samples.copyOfRange(i, i + window))
            i += window
        }
        if (i < samples.size) vad.acceptWaveform(samples.copyOfRange(i, samples.size))
        vad.flush()

        val segments = mutableListOf<IntRange>()
        while (!vad.empty()) {
            val segment = vad.front()
            segments += segment.start until segment.start + segment.samples.size
            vad.pop()
        }
        val ranges = speechRanges(segments, samples.size, (PAD_S * SAMPLE_RATE).toInt())
        if (ranges.isEmpty()) return null
        return joinRanges(samples, ranges, (GAP_S * SAMPLE_RATE).toInt())
    }

    fun release() = vad.release()

    companion object {
        private const val TAG = "SilenceTrimmer"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW = 512
        private const val PAD_S = 0.3f
        private const val GAP_S = 0.2f
        private const val ASSET = "silero_vad.onnx"

        fun create(modelFile: File): SilenceTrimmer? = try {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelFile.absolutePath,
                    // A little more sensitive than the default, so quiet words aren't cut.
                    threshold = 0.4f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.1f,
                    windowSize = WINDOW,
                    maxSpeechDuration = 30f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            )
            SilenceTrimmer(Vad(assetManager = null, config = config))
        } catch (e: Throwable) {
            Log.e(TAG, "Could not load VAD: ${e.message}")
            null
        }

        /** The bundled Silero model, copied out of the APK once (the recognizer loads files by path). */
        fun modelFile(ctx: Context): File {
            val file = File(ctx.filesDir, "vad/$ASSET")
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "$ASSET.tmp")
                ctx.assets.open(ASSET).use { src -> tmp.outputStream().use { src.copyTo(it) } }
                tmp.renameTo(file)
            }
            return file
        }

        /** Widen each speech segment by [pad] samples, clamp to [0, total) and merge overlaps. */
        fun speechRanges(segments: List<IntRange>, total: Int, pad: Int): List<IntRange> {
            val merged = mutableListOf<IntRange>()
            for (s in segments.sortedBy { it.first }) {
                if (s.isEmpty()) continue
                val r = maxOf(0, s.first - pad)..minOf(total - 1, s.last + pad)
                val last = merged.lastOrNull()
                if (last != null && r.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
                else merged += r
            }
            return merged
        }

        /** Concatenate [ranges] of [samples] with [gap] samples of silence between them. */
        fun joinRanges(samples: FloatArray, ranges: List<IntRange>, gap: Int): FloatArray {
            val size = ranges.sumOf { it.last - it.first + 1 } + gap * (ranges.size - 1).coerceAtLeast(0)
            val out = FloatArray(size)
            var pos = 0
            ranges.forEachIndexed { index, r ->
                if (index > 0) pos += gap
                samples.copyInto(out, pos, r.first, r.last + 1)
                pos += r.last - r.first + 1
            }
            return out
        }
    }
}

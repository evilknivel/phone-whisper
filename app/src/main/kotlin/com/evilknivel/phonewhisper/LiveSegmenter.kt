package com.evilknivel.phonewhisper

import com.k2fsa.sherpa.onnx.Vad
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * Transcribes a local dictation in parts while it is still being recorded: once Silero VAD sees a
 * pause after at least [MIN_PART_S] of speech, that part is handed to [transcribe] (which queues
 * it on the model thread) while the user keeps talking. After stopping, only the last part is
 * left, so the wait no longer grows with the length of the dictation. Each part is cut down to
 * its speech, like [SilenceTrimmer] does for a whole recording.
 *
 * [feed] runs on the recorder thread, [finish] or [cancel] once afterwards; the [vad] belongs to
 * this object and is released by them.
 */
class LiveSegmenter(
    private val vad: Vad,
    private val transcribe: (FloatArray) -> Future<String?>,
) {
    sealed class Result {
        data class Text(val text: String, val parts: Int) : Result()
        object NoSpeech : Result()
        /** A part could not be transcribed; the caller transcribes the whole recording instead. */
        object Failed : Result()
    }

    private val lock = Any()
    private var audio = FloatArray(16000 * 30)
    private var size = 0
    private val planner = PartPlanner((MIN_PART_S * SAMPLE_RATE).toInt())
    private val parts = mutableListOf<Future<String?>>()
    private var closed = false

    /** Number of parts handed to the model so far. */
    val partCount get() = synchronized(lock) { parts.size }

    fun feed(samples: FloatArray) {
        synchronized(lock) {
            if (closed || samples.isEmpty()) return
            if (size + samples.size > audio.size) audio = audio.copyOf(maxOf(audio.size * 2, size + samples.size))
            samples.copyInto(audio, size)
            size += samples.size
            vad.acceptWaveform(samples)
            drain()
        }
    }

    /** Transcribe the rest, wait for all parts and join them. Blocks: not on the model thread. */
    fun finish(): Result {
        val pending = synchronized(lock) {
            if (closed) return Result.Failed
            vad.flush()
            drain()
            planner.rest()?.let(::submit)
            close()
            parts.toList()
        }
        if (pending.isEmpty()) return Result.NoSpeech
        val texts = try {
            pending.map { it.get() ?: return Result.Failed }
        } catch (_: ExecutionException) {
            return Result.Failed
        }
        return Result.Text(joinParts(texts), pending.size)
    }

    /** Drop the dictation; parts that haven't started yet are skipped. */
    fun cancel() {
        synchronized(lock) {
            if (closed) return
            close()
            parts.forEach { it.cancel(false) }
        }
    }

    private fun close() {
        closed = true
        vad.release()
    }

    private fun drain() {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            planner.add(segment.start until segment.start + segment.samples.size)?.let(::submit)
        }
    }

    private fun submit(speech: List<IntRange>) {
        val ranges = SilenceTrimmer.speechRanges(speech, size, (PAD_S * SAMPLE_RATE).toInt())
        if (ranges.isEmpty()) return
        parts += transcribe(SilenceTrimmer.joinRanges(audio, ranges, (GAP_S * SAMPLE_RATE).toInt()))
    }

    /**
     * Groups the speech segments the VAD reports (each ends at a pause) into parts of at least
     * [minSamples], so short phrases aren't transcribed alone without context.
     */
    class PartPlanner(private val minSamples: Int) {
        private val pending = mutableListOf<IntRange>()

        /** The part to transcribe now, or null to wait for more speech. */
        fun add(segment: IntRange): List<IntRange>? {
            if (segment.isEmpty()) return null
            pending += segment
            return if (pending.last().last - pending.first().first + 1 >= minSamples) take() else null
        }

        /** Whatever is left when the recording stops. */
        fun rest(): List<IntRange>? = if (pending.isEmpty()) null else take()

        private fun take() = pending.toList().also { pending.clear() }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        /** A part needs this much speech before a pause ends it. */
        const val MIN_PART_S = 3f
        /** The pause that ends a part, a little longer than between words. */
        const val PAUSE_S = 0.6f
        private const val PAD_S = 0.3f
        private const val GAP_S = 0.2f

        /** Join the parts' texts; a part that starts lowercase after a sentence end is capitalized. */
        fun joinParts(texts: List<String>): String {
            val sb = StringBuilder()
            for (text in texts.map { it.trim() }.filter { it.isNotEmpty() }) {
                var next = text
                if (sb.isNotEmpty()) {
                    if (sb.last() in ".?!" && next[0].isLowerCase()) next = next[0].uppercase() + next.substring(1)
                    sb.append(' ')
                }
                sb.append(next)
            }
            return sb.toString()
        }
    }
}

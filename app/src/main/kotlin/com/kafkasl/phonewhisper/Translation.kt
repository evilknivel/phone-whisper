package com.kafkasl.phonewhisper

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Translating a dictation into another language. The transcription always uses the model from
 * the settings; the chat model of the cleanup provider then translates. Only when no cleanup
 * provider is set up does an installed multilingual Whisper model translate into English, the
 * one target Whisper supports, for that dictation alone.
 */
object Translation {
    /** How many languages fit on the arc under the thumb. */
    const val MAX_LANGUAGES = 6

    enum class Method { AI, WHISPER }

    fun method(target: String, aiConfigured: Boolean, whisperInstalled: Boolean): Method? = when {
        aiConfigured -> Method.AI
        target == "en" && whisperInstalled -> Method.WHISPER
        else -> null
    }

    fun languageName(code: String): String = Prefs.LANGUAGES.firstOrNull { it.first == code }?.second ?: code

    /** The selected targets in arc order, without the language you speak. */
    fun arcLanguages(selected: List<String>, spoken: String): List<String> = selected.filter { it != spoken }

    /** The cleanup prompt with a final instruction to translate, which overrides its keep-the-language rule. */
    fun prompt(cleanupPrompt: String, source: String, target: String): String {
        val from = if (source.isEmpty()) "" else " from ${languageName(source)}"
        return "$cleanupPrompt\n\nIgnore any instruction above to keep the original language: translate the " +
            "result$from into ${languageName(target)}. Return only the translation."
    }

    /** Selected languages are stored comma-separated, in the order they were picked. */
    fun parse(stored: String?): List<String> =
        stored.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun toggle(selected: List<String>, code: String): List<String> =
        if (code in selected) selected - code
        else if (selected.size >= MAX_LANGUAGES) selected
        else selected + code
}

/**
 * Geometry of the arc of choices that appears around the held button. The arc opens towards the
 * middle of the screen, since the button sits at an edge: angles are in radians, 0 pointing
 * right, positive clockwise (screen coordinates, y down).
 */
object RadialMenu {
    /**
     * Half-circle facing away from the edge, from top to bottom. [tilt] turns it towards the
     * bottom (positive) or the top (negative), so it stays on screen near the top or bottom edge.
     */
    fun angles(count: Int, onRightEdge: Boolean, tilt: Double = 0.0): List<Double> {
        if (count <= 0) return emptyList()
        val center = if (onRightEdge) PI - tilt else tilt
        val span = PI * 0.9
        if (count == 1) return listOf(center)
        val step = span / (count - 1)
        // From the top (-90°) down to the bottom (+90°), on the inner side.
        return (0 until count).map { i ->
            if (onRightEdge) center + span / 2 - i * step else center - span / 2 + i * step
        }
    }

    /** How far to tilt the arc when there is less than [reach] room above ([roomAbove]) or below the button. */
    fun tilt(roomAbove: Float, roomBelow: Float, reach: Float): Double = when {
        roomAbove < reach -> (reach - roomAbove) / reach * PI / 2
        roomBelow < reach -> -(reach - roomBelow) / reach * PI / 2
        else -> 0.0
    }.coerceIn(-PI / 2, PI / 2)

    fun position(angle: Double, radius: Float): Pair<Float, Float> =
        (cos(angle) * radius).toFloat() to (sin(angle) * radius).toFloat()

    /**
     * The choice under the finger at offset ([dx], [dy]) from the button's center, or -1 while it
     * is still near the button (that releases into a normal dictation).
     */
    fun hit(dx: Float, dy: Float, angles: List<Double>, deadZone: Float): Int {
        if (angles.isEmpty() || hypot(dx, dy) < deadZone) return -1
        val a = atan2(dy.toDouble(), dx.toDouble())
        return angles.indices.minByOrNull { angleDistance(angles[it], a) } ?: -1
    }

    private fun angleDistance(a: Double, b: Double): Double {
        val d = ((a - b) % (2 * PI) + 3 * PI) % (2 * PI) - PI
        return kotlin.math.abs(d)
    }
}

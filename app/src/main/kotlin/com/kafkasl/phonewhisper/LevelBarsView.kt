package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Three bars that follow the microphone level while recording, so the user sees the mic is live. */
class LevelBarsView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private var level = 0f

    /** [value] in 0..1. Rises immediately, falls smoothly. */
    fun setLevel(value: Float) {
        val v = value.coerceIn(0f, 1f)
        level = if (v > level) v else level * 0.7f + v * 0.3f
        invalidate()
    }

    fun reset() { level = 0f; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val barW = width / 5f
        val radius = barW / 2
        // Middle bar reacts most, outer bars less, so the shape reads as a level meter.
        val weights = floatArrayOf(0.6f, 1f, 0.75f)
        weights.forEachIndexed { i, weight ->
            val h = height * (0.2f + 0.8f * level * weight)
            val left = barW * (i * 2)
            val top = (height - h) / 2
            canvas.drawRoundRect(left, top, left + barW, top + h, radius, radius, paint)
        }
    }

    companion object {
        /** RMS of 16-bit PCM bytes mapped to 0..1 (speech at normal distance ends up around 0.3–0.8). */
        fun levelOf(pcm: ByteArray, count: Int): Float {
            val samples = count / 2
            if (samples == 0) return 0f
            var sum = 0.0
            for (i in 0 until samples) {
                val s = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort().toDouble()
                sum += s * s
            }
            val rms = kotlin.math.sqrt(sum / samples) / 32768.0
            return (rms * 8.5).coerceIn(0.0, 1.0).toFloat()
        }
    }
}

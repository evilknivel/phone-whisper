package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * A glow along all four screen edges while recording, visible wherever the thumb is. Its color
 * says what kind of dictation runs; [level] (0..1) lets it breathe with the voice.
 */
class EdgeGlowView(context: Context) : View(context) {
    private val thickness = 14 * resources.displayMetrics.density
    private val paint = Paint()
    private var color = 0xFFEF4444.toInt()
    private var level = 0f

    fun setColor(value: Int) { color = value; invalidate() }

    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Base glow plus a little more with the voice, so it is never invisible during pauses.
        val alpha = (140 + 115 * level).toInt().coerceIn(0, 255)
        val solid = (color and 0x00FFFFFF) or (alpha shl 24)
        val clear = color and 0x00FFFFFF
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, l: Float, t: Float, r: Float, b: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, solid, clear, Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, r, b, paint)
        }
        edge(0f, 0f, 0f, thickness, 0f, 0f, w, thickness)                  // top
        edge(0f, h, 0f, h - thickness, 0f, h - thickness, w, h)            // bottom
        edge(0f, 0f, thickness, 0f, 0f, 0f, thickness, h)                  // left
        edge(w, 0f, w - thickness, 0f, w - thickness, 0f, w, h)            // right
    }
}

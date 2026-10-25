package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * The choices that appear around the held button (target languages, "+" and the settings
 * gear), drawn as chips on an arc. It only draws; the button's touch handler picks the chip
 * under the thumb with [RadialMenu.hit] and sets [highlighted].
 */
class RadialMenuView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density

    var labels: List<String> = emptyList()
        set(value) { field = value; invalidate() }
    var angles: List<Double> = emptyList()
        set(value) { field = value; invalidate() }
    var highlighted = -1
        set(value) { if (field != value) { field = value; invalidate() } }
    var highlightColor = 0xDD8B5CF6.toInt()

    /** Distance of the chip centers from the button's center. */
    val radius = RADIUS_DP * density
    private val chipRadius = CHIP_DP * density

    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 14 * density
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val baseline = (textPaint.descent() + textPaint.ascent()) / 2
        labels.forEachIndexed { i, label ->
            val angle = angles.getOrNull(i) ?: return@forEachIndexed
            val (dx, dy) = RadialMenu.position(angle, radius)
            val selected = i == highlighted
            chipPaint.color = if (selected) highlightColor else 0xEE1C1C1E.toInt()
            val r = if (selected) chipRadius * 1.2f else chipRadius
            canvas.drawCircle(cx + dx, cy + dy, r, chipPaint)
            canvas.drawText(label, cx + dx, cy + dy - baseline, textPaint)
        }
    }

    companion object {
        const val RADIUS_DP = 100
        const val CHIP_DP = 20
        /** Window size: the arc plus the enlarged highlighted chip. */
        const val SIZE_DP = 2 * (RADIUS_DP + CHIP_DP + 8)
    }
}

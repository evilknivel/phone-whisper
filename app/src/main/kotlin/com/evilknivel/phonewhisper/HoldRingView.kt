package com.evilknivel.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * A ring that grows from nothing to a full circle while the button is held, drawn larger than
 * the button so it shows around the thumb that covers the button itself. Its [color] is the color
 * of what the hold will do; full means the hold has triggered.
 */
class HoldRingView(context: Context) : View(context) {
    private val stroke = 6 * resources.displayMetrics.density
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()

    var color: Int
        get() = arc.color
        set(value) { arc.color = value or 0xFF000000.toInt(); invalidate() }

    /** 0..1 */
    var progress = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        val inset = stroke / 2
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(bounds, -90f, 360f * progress, false, arc)
    }
}

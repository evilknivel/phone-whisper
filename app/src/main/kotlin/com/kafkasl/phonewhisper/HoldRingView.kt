package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * A ring that fills while the button is held, drawn larger than the button so it shows around the
 * thumb that covers the button itself. Full means the hold has triggered.
 */
class HoldRingView(context: Context, private val color: Int) : View(context) {
    private val stroke = 6 * resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; this.color = color; alpha = 60
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; this.color = color
    }
    private val bounds = RectF()

    /** 0..1 */
    var progress = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val inset = stroke / 2
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(bounds, track)
        canvas.drawArc(bounds, -90f, 360f * progress, false, arc)
    }
}

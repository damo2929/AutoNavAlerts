// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import io.autonav.alerts.R

/**
 * Zoom in over zoom out in one pill, like Google Maps' (DESIGN.md §8), in the column down the right
 * of the car map. White by day and dark by night. Taps arrive through the surface's onClick: the top
 * half zooms in, the bottom half out ([tap]).
 */
class ZoomPillView(context: Context, private val onZoom: (Float) -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plus = ContextCompat.getDrawable(context, R.drawable.ic_action_zoom_in)!!.mutate()
    private val minus = ContextCompat.getDrawable(context, R.drawable.ic_action_zoom_out)!!.mutate()
    private val rect = RectF()

    /** Day or night colours; redraws only when it changes. */
    var dark = true
        set(v) {
            if (v == field) return
            field = v
            invalidate()
        }

    /** A tap [y] pixels below the pill's top: returns which half it hit ("zoomIn" or "zoomOut"). */
    fun tap(y: Float): String = if (y < height / 2f) { onZoom(1f); "zoomIn" } else { onZoom(-1f); "zoomOut" }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((WIDTH_DP * density).toInt(), (HEIGHT_DP * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        MapButtonColors.apply(dark, bg, edge, density)
        rect.set(edge.strokeWidth / 2, edge.strokeWidth / 2, w - edge.strokeWidth / 2, h - edge.strokeWidth / 2)
        canvas.drawRoundRect(rect, w / 2, w / 2, bg)
        if (!dark) canvas.drawRoundRect(rect, w / 2, w / 2, edge)
        line.color = if (dark) Color.argb(90, 255, 255, 255) else edge.color
        canvas.drawRect(w * 0.2f, h / 2 - density / 2, w * 0.8f, h / 2 + density / 2, line)
        val tint = MapButtonColors.icon(dark)
        // Each icon half the pill's width, centred in its half.
        val size = width / 2
        val left = (width - size) / 2
        val top = (height / 4) - size / 2
        plus.setTint(tint)
        plus.setBounds(left, top, left + size, top + size)
        plus.draw(canvas)
        minus.setTint(tint)
        minus.setBounds(left, top + height / 2, left + size, top + height / 2 + size)
        minus.draw(canvas)
    }

    companion object {
        const val WIDTH_DP = 56
        const val HEIGHT_DP = 112
    }
}

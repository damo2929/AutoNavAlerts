// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import io.autonav.alerts.R

/**
 * Zoom in over zoom out in one pill, like Google Maps' (DESIGN.md §8), in the column down the right
 * of the car map. White by day and dark by night. Taps arrive through the surface's onClick: the top
 * half zooms in, the bottom half out ([tap]).
 *
 * Created by `MapSurface.attach` only from car API 5 (below that the host's own strip has zoom
 * in and out instead) and released with the surface; main thread. `MapSurface.zoom` applies the
 * step: while following, the follow zoom changes and lasts the session (§8 "Zooming keeps your
 * zoom"); the + button stops at the 17 cap. Hard rules: right-hand side only (rule 4); the one
 * zoom control (rule 2); redrawn only on a day/night change (rule 3).
 *
 * @param onZoom called with +1 (in) or −1 (out) zoom levels.
 */
class ZoomPillView(context: Context, private val onZoom: (Float) -> Unit) : MapButton(context, WIDTH_DP, HEIGHT_DP) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plus = ContextCompat.getDrawable(context, R.drawable.ic_action_zoom_in)!!.mutate()
    private val minus = ContextCompat.getDrawable(context, R.drawable.ic_action_zoom_out)!!.mutate()
    private val rect = RectF()

    /** A tap [y] pixels below the pill's top: returns which half it hit ("zoomIn" or "zoomOut"), for the tap log. */
    fun tap(y: Float): String = if (y < height / 2f) { onZoom(1f); "zoomIn" } else { onZoom(-1f); "zoomOut" }

    /** The pill, the hairline between the halves and the + and − icons; runs only on a day/night change. */
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(edge.strokeWidth / 2, edge.strokeWidth / 2, w - edge.strokeWidth / 2, h - edge.strokeWidth / 2)
        drawShape(canvas) { drawRoundRect(rect, w / 2, w / 2, it) }
        // The hairline between the halves (§8 "Map buttons"), 1 dp, over the middle 60 % of the width.
        line.color = if (dark) Color.argb(90, 255, 255, 255) else edge.color
        canvas.drawRect(w * 0.2f, h / 2 - density / 2, w * 0.8f, h / 2 + density / 2, line)
        // Each icon half the pill's width, centred in its half.
        val size = width / 2
        val left = (width - size) / 2
        val top = (height / 4) - size / 2
        drawIcon(canvas, plus, left, top, size)
        drawIcon(canvas, minus, left, top + height / 2, size)
    }

    companion object {
        /** The pill's size (§8 "Map buttons": 56 × 112 dp, the width of the round recenter below it). */
        const val WIDTH_DP = 56
        const val HEIGHT_DP = 112
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.content.ContextCompat
import io.autonav.alerts.R

/**
 * The satellite toggle at the top right of the car map (DESIGN.md §8): a pill (white by day, dark by
 * night, like Google Maps' buttons) with the layers icon and the map type a tap switches to ("Satellite" on the road map, "Map" on satellite).
 * Taps arrive through the surface's onClick, as the map surface has no touch events of its own.
 *
 * Created by `MapSurface.attach` on every car API level (unlike the zoom and recenter column it
 * has no host-strip fallback; rotary cars use the same switch in More), placed 16 px inside the
 * visible area's top-right corner and below the action strip by `MapSurface.layoutOverlays`,
 * released with the surface; main thread. It shows the one `satelliteMap` setting, which the map
 * follows, so the pill, More's toggle and the phone always agree (§8 "Satellite view").
 *
 * Hard rules: on the right-hand side only (rule 4); redrawn only when its state or day/night
 * changes (rule 3).
 */
class MapTypeButtonView(context: Context) : View(context) {
    /** Whether the map is on satellite now; the label names the other type, the one a tap switches to. */
    var satellite = false
        set(v) {
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18 * density; typeface = Typeface.DEFAULT_BOLD
    }
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_action_layers)!!.mutate()
    private val rect = RectF()

    /** Day or night colours; redraws only when it changes. */
    var dark = true
        set(v) {
            if (v == field) return
            field = v
            invalidate()
        }

    /** A fixed [WIDTH_DP] × [HEIGHT_DP] pill. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((WIDTH_DP * density).toInt(), (HEIGHT_DP * density).toInt())
    }

    /** The pill, the layers icon and the label of the type a tap switches to; runs only on a state or day/night change. */
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        // Same palette as the zoom pill and recenter, so the column reads as one set (§8).
        MapButtonColors.apply(dark, bg, edge, density)
        rect.set(edge.strokeWidth / 2, edge.strokeWidth / 2, width - edge.strokeWidth / 2, h - edge.strokeWidth / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, bg)
        if (!dark) canvas.drawRoundRect(rect, h / 2, h / 2, edge)
        text.color = MapButtonColors.icon(dark)
        icon.setTint(text.color)
        val pad = 12 * density
        val iconSize = (h - pad * 2).toInt()
        icon.setBounds(pad.toInt(), pad.toInt(), pad.toInt() + iconSize, pad.toInt() + iconSize)
        icon.draw(canvas)
        val label = if (satellite) "Map" else "Satellite"
        canvas.drawText(label, pad * 1.6f + iconSize, h / 2 - (text.ascent() + text.descent()) / 2, text)
    }

    companion object {
        /** The pill's size (§8 "Satellite view": 150 × 52 dp); `MapSurface` stacks the column below it. */
        const val WIDTH_DP = 150
        const val HEIGHT_DP = 52
    }
}

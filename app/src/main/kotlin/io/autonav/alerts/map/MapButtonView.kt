// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * A round map button (recenter) in the column down the right of the car map, under the satellite
 * toggle and the zoom pill (DESIGN.md §8). White by day and dark by night, like Google Maps'.
 * Taps arrive through the surface's onClick and run [onTap].
 */
class MapButtonView(context: Context, @DrawableRes iconRes: Int, val onTap: () -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val icon = ContextCompat.getDrawable(context, iconRes)!!.mutate()

    /** Day or night colours; redraws only when it changes. */
    var dark = true
        set(v) {
            if (v == field) return
            field = v
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (SIZE_DP * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        MapButtonColors.apply(dark, bg, edge, density)
        canvas.drawCircle(r, r, r - edge.strokeWidth / 2, bg)
        if (!dark) canvas.drawCircle(r, r, r - edge.strokeWidth / 2, edge)
        val inset = (width * 0.25f).toInt()
        icon.setTint(MapButtonColors.icon(dark))
        icon.setBounds(inset, inset, width - inset, width - inset)
        icon.draw(canvas)
    }

    companion object {
        const val SIZE_DP = 56
        /** Space between buttons in the column. */
        const val GAP_DP = 10
    }
}

/** The map buttons' day and night colours, shared by the toggle, the zoom pill and recenter (DESIGN.md §8). */
internal object MapButtonColors {
    fun apply(dark: Boolean, bg: Paint, edge: Paint, density: Float) {
        bg.color = if (dark) Color.argb(235, 32, 33, 36) else Color.WHITE
        edge.color = Color.rgb(218, 220, 224)
        edge.strokeWidth = density
    }

    fun icon(dark: Boolean): Int = if (dark) Color.WHITE else Color.rgb(60, 64, 67)
}

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
 *
 * Created by `MapSurface.attach` only from car API 5 (before that there is no `onClick`, so the
 * host's own strip has recenter instead) and released with the surface; main thread. It is the
 * one recenter on the car screen (hard rule 2; the SDK's is turned off), on the right-hand side
 * only (hard rule 4), and it redraws only when day/night changes (hard rule 3).
 *
 * @param onTap run by `MapSurface.onClick` when the tap lands inside the button.
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

    /** A fixed [SIZE_DP] square, whatever the parent offers. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (SIZE_DP * density).toInt()
        setMeasuredDimension(size, size)
    }

    /** The disc and its tinted icon; runs only on size and day/night changes. */
    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        MapButtonColors.apply(dark, bg, edge, density)
        canvas.drawCircle(r, r, r - edge.strokeWidth / 2, bg)
        // The hairline edge only by day: a white disc on a light map needs it, a dark one doesn't.
        if (!dark) canvas.drawCircle(r, r, r - edge.strokeWidth / 2, edge)
        // The icon fills the middle half of the disc.
        val inset = (width * 0.25f).toInt()
        icon.setTint(MapButtonColors.icon(dark))
        icon.setBounds(inset, inset, width - inset, width - inset)
        icon.draw(canvas)
    }

    companion object {
        /** The round button's diameter (§8 "Map buttons": a 56 dp recenter). */
        const val SIZE_DP = 56
        /** Space between buttons in the column. */
        const val GAP_DP = 10
    }
}

/** The map buttons' day and night colours, shared by the toggle, the zoom pill and recenter (DESIGN.md §8). */
internal object MapButtonColors {
    /** Sets [bg] and [edge] for [dark] or day: dark grey at 92 % with white icons by night, white with a 1 dp light-grey edge by day. */
    fun apply(dark: Boolean, bg: Paint, edge: Paint, density: Float) {
        bg.color = if (dark) Color.argb(235, 32, 33, 36) else Color.WHITE
        edge.color = Color.rgb(218, 220, 224)
        edge.strokeWidth = density
    }

    /** The icon and label colour for [dark] or day. */
    fun icon(dark: Boolean): Int = if (dark) Color.WHITE else Color.rgb(60, 64, 67)
}

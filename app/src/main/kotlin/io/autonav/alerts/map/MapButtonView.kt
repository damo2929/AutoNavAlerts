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
 * What the map buttons share (DESIGN.md §8 "Map buttons", "Looking like Google Maps"): a fixed
 * dp size, the day and night palette (dark grey at 92 % with white icons by night, white with a
 * 1 dp light-grey edge and dark icons by day) and the [dark] switch that redraws only on a change
 * (hard rule 3). The satellite toggle, the zoom pill and recenter extend it; `MapSurface` places
 * them down the right edge (hard rule 4) and reads [heightDp] to stack the column. Main thread.
 */
abstract class MapButton(context: Context, private val widthDp: Int, val heightDp: Int) : View(context) {
    protected val density = resources.displayMetrics.density
    protected val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(218, 220, 224)
        strokeWidth = density
    }

    /** Day or night colours; redraws only when it changes. */
    var dark = true
        set(v) {
            if (v == field) return
            field = v
            invalidate()
        }

    /** The icon and label colour for day or night. */
    protected val iconColor: Int get() = if (dark) Color.WHITE else Color.rgb(60, 64, 67)

    /** A fixed size in dp, whatever the parent offers. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((widthDp * density).toInt(), (heightDp * density).toInt())
    }

    /** Draws [shape] filled for day or night, then outlined with the hairline edge by day only: a white shape on a light map needs it, a dark one doesn't. */
    protected fun drawShape(canvas: Canvas, shape: Canvas.(Paint) -> Unit) {
        bg.color = if (dark) Color.argb(235, 32, 33, 36) else Color.WHITE
        canvas.shape(bg)
        if (!dark) canvas.shape(edge)
    }
}

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
class MapButtonView(context: Context, @DrawableRes iconRes: Int, val onTap: () -> Unit) : MapButton(context, SIZE_DP, SIZE_DP) {
    private val icon = ContextCompat.getDrawable(context, iconRes)!!.mutate()

    /** The disc and its tinted icon; runs only on size and day/night changes. */
    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        drawShape(canvas) { drawCircle(r, r, r - edge.strokeWidth / 2, it) }
        // The icon fills the middle half of the disc.
        val inset = (width * 0.25f).toInt()
        icon.setTint(iconColor)
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

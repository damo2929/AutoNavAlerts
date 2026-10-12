// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.content.ContextCompat
import io.autonav.alerts.R

/**
 * The satellite toggle at the top right of the car map (DESIGN.md §8): a dark pill with the layers
 * icon and the map type a tap switches to ("Satellite" on the road map, "Map" on satellite).
 * Taps arrive through the surface's onClick, as the map surface has no touch events of its own.
 */
class MapTypeButtonView(context: Context) : View(context) {
    var satellite = false
        set(v) {
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 32, 33, 36) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 18 * density; typeface = Typeface.DEFAULT_BOLD
    }
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_action_layers)!!.mutate().apply { setTint(Color.WHITE) }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((WIDTH_DP * density).toInt(), (HEIGHT_DP * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, h / 2, h / 2, bg)
        val pad = 12 * density
        val iconSize = (h - pad * 2).toInt()
        icon.setBounds(pad.toInt(), pad.toInt(), pad.toInt() + iconSize, pad.toInt() + iconSize)
        icon.draw(canvas)
        val label = if (satellite) "Map" else "Satellite"
        canvas.drawText(label, pad * 1.6f + iconSize, h / 2 - (text.ascent() + text.descent()) / 2, text)
    }

    companion object {
        const val WIDTH_DP = 150
        const val HEIGHT_DP = 52
    }
}

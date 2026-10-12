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
 * A round map button (zoom in, zoom out, recenter) in the column down the right of the car map,
 * under the satellite toggle (DESIGN.md §8). Drawn like the toggle; taps arrive through the
 * surface's onClick and run [onTap].
 */
class MapButtonView(context: Context, @DrawableRes iconRes: Int, val onTap: () -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 32, 33, 36) }
    private val icon = ContextCompat.getDrawable(context, iconRes)!!.mutate().apply { setTint(Color.WHITE) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (SIZE_DP * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        canvas.drawCircle(r, r, r, bg)
        val inset = (width * 0.25f).toInt()
        icon.setBounds(inset, inset, width - inset, width - inset)
        icon.draw(canvas)
    }

    companion object {
        const val SIZE_DP = 56
        /** Space between buttons in the column. */
        const val GAP_DP = 10
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat

/** Small helpers shared by the car screens. */
object CarUi {
    fun icon(ctx: CarContext, @DrawableRes res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()

    /** A bitmap as a car icon, drawn as-is (pair it with `Row.IMAGE_TYPE_SMALL` so the host doesn't tint it). */
    fun icon(bmp: android.graphics.Bitmap): CarIcon = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()

    /** [persistent]: the host keeps it on screen instead of fading it out on the map (DESIGN.md §8). */
    fun action(ctx: CarContext, @DrawableRes res: Int, title: String? = null, persistent: Boolean = false, onClick: () -> Unit): Action =
        Action.Builder().setIcon(icon(ctx, res)).apply { title?.let { setTitle(it) } }
            .apply { if (persistent) setFlags(Action.FLAG_IS_PERSISTENT) }.setOnClickListener(onClick).build()

    fun textAction(title: String, persistent: Boolean = false, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).apply { if (persistent) setFlags(Action.FLAG_IS_PERSISTENT) }.setOnClickListener(onClick).build()

    /** A UK-style speed limit sign as a car icon (used by the report speed grid). */
    fun speedSign(value: String, sizePx: Int = 128): CarIcon {
        val bmp = createBitmap(sizePx, sizePx)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, p)
        p.color = Color.rgb(220, 30, 30); p.style = Paint.Style.STROKE; p.strokeWidth = sizePx * 0.1f
        c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - p.strokeWidth / 2, p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
            textSize = sizePx * if (value.length > 2) 0.3f else 0.42f
        }
        c.drawText(value, sizePx / 2f, sizePx / 2f - (t.ascent() + t.descent()) / 2, t)
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600 + 59) / 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    fun formatDistance(meters: Int, imperial: Boolean): String = if (imperial) {
        val mi = meters / 1609.344
        if (mi < 0.2) "${(meters / 0.9144 / 10).toInt() * 10} yd" else "%.1f mi".format(mi)
    } else {
        if (meters < 1000) "$meters m" else "%.1f km".format(meters / 1000.0)
    }
}

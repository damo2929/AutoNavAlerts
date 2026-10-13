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

    /** How many rows the host lets a list show (6 if it won't say). */
    fun listLimit(ctx: CarContext): Int = runCatching {
        ctx.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
            .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6)

    fun textAction(title: String, persistent: Boolean = false, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).apply { if (persistent) setFlags(Action.FLAG_IS_PERSISTENT) }.setOnClickListener(onClick).build()

    fun formatDistance(meters: Int, imperial: Boolean): String = if (imperial) {
        val mi = meters / 1609.344
        if (mi < 0.2) "${(meters / 0.9144 / 10).toInt() * 10} yd" else "%.1f mi".format(mi)
    } else {
        if (meters < 1000) "$meters m" else "%.1f km".format(meters / 1000.0)
    }
}

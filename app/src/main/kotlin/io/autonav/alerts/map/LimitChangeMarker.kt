// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

/**
 * The next speed-limit change on the map (DESIGN.md §8.4a, owner 2026-10-23): just the new limit's
 * number in dark text on a light-grey square, where the change starts. One marker, its image made
 * once per value; [show] moves or redraws it only when the value or the position changed (no shake,
 * hard rule 3). Called once a second at most; map calls are posted to the main thread.
 */
class LimitChangeMarker(private val density: Float) {
    var map: GoogleMap? = null
    private val main = Handler(Looper.getMainLooper())
    private var marker: Marker? = null
    private var shownValue: Int? = null
    private var shownAt: LatLng? = null
    private val images = HashMap<Int, BitmapDescriptor>()

    /** Shows [value] at ([lat], [lon]), or clears the marker with a null [value]. */
    fun show(value: Int?, lat: Double, lon: Double) {
        if (value == null || lat.isNaN() || lon.isNaN()) { clear(); return }
        val at = LatLng(lat, lon)
        // A few metres of drift between evaluations isn't a change worth a redraw.
        val moved = shownAt?.let { io.autonav.alerts.core.geo.Geo.distanceM(it.latitude, it.longitude, lat, lon) > MOVE_M } ?: true
        if (value == shownValue && !moved) return
        shownValue = value
        shownAt = at
        main.post {
            val m = map ?: return@post
            val icon = images.getOrPut(value) { BitmapDescriptorFactory.fromBitmap(draw(value)) }
            val mk = marker
            if (mk == null) {
                marker = m.addMarker(MarkerOptions().position(at).icon(icon).anchor(0.5f, 0.5f).zIndex(Z))
            } else {
                mk.position = at
                mk.setIcon(icon)
            }
        }
    }

    fun clear() {
        if (shownValue == null && marker == null) return
        shownValue = null
        shownAt = null
        main.post { marker?.remove(); marker = null }
    }

    private fun draw(value: Int): Bitmap {
        val side = (30 * density).toInt()
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(0f, 0f, side.toFloat(), side.toFloat())
        c.drawRoundRect(r, 4 * density, 4 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKING })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = (if (value >= 100) 13 else 16) * density
        }
        c.drawText(value.toString(), side / 2f, side / 2f - (text.descent() + text.ascent()) / 2, text)
        return bmp
    }

    private companion object {
        val BACKING = Color.rgb(0xDA, 0xDC, 0xE0)
        val TEXT = Color.rgb(0x20, 0x21, 0x24)
        const val MOVE_M = 15.0
        /** Under every camera marker (they start at 1). */
        const val Z = 0.5f
    }
}

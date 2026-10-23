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

/**
 * Current speed next to the road's limit (DESIGN.md §8.4), in one white pill like Google Maps':
 * the limit ring on the left, the speed and its unit on the right. The speed turns amber/red with
 * the Navigation SDK's SpeedingListener severity. A limit that isn't Google's has a small dot so it
 * isn't mistaken for Google's figure: blue for a camera or zone, grey for OpenStreetMap (§8.4a).
 * The panel sits bottom-right (§8). A change of limit ahead is marked on the map, not here
 * (`LimitChangeMarker`).
 */
class SpeedPanelView(context: Context) : View(context) {
    enum class Severity { NONE, MINOR, MAJOR }
    enum class Source { GOOGLE, CAMERA, OSM }

    private var speed: Int? = null
    private var limit: Int? = null
    private var source = Source.GOOGLE
    private var severity = Severity.NONE
    private var unit = "mph"

    private val density = resources.displayMetrics.density
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(218, 220, 224) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.rgb(95, 99, 104) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cameraDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 101, 192) }
    private val osmDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(117, 117, 117) }
    private val rect = RectF()

    /** Updates the panel; only redraws when something shown changes. */
    fun update(speed: Int?, limit: Int?, source: Source, severity: Severity, unit: String) {
        if (speed == this.speed && limit == this.limit && source == this.source &&
            severity == this.severity && unit == this.unit
        ) return
        this.speed = speed; this.limit = limit; this.source = source
        this.severity = severity; this.unit = unit
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((WIDTH_DP * density).toInt(), (HEIGHT_DP * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val s = speed ?: return
        val h = height.toFloat()
        val w = width.toFloat()
        val e = density
        edge.strokeWidth = e
        rect.set(e / 2, e / 2, w - e / 2, h - e / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, pill)
        canvas.drawRoundRect(rect, h / 2, h / 2, edge)

        // The limit sign is always there (DESIGN.md §8.4): "–" in a grey ring when it isn't known.
        val l = limit
        val pad = (h - RING_DP * density) / 2
        val rr = RING_DP * density / 2
        val cx = pad + rr
        val cy = h / 2
        ring.strokeWidth = 5 * density
        ring.color = if (l == null) Color.rgb(158, 158, 158) else Color.rgb(217, 48, 37)
        canvas.drawCircle(cx, cy, rr - ring.strokeWidth / 2, ring)
        text.color = if (l == null) Color.rgb(117, 117, 117) else Color.rgb(32, 33, 36)
        text.textSize = 20 * density
        canvas.drawText(l?.toString() ?: "–", cx, cy - (text.ascent() + text.descent()) / 2, text)
        if (l != null) when (source) {
            Source.CAMERA -> canvas.drawCircle(cx + rr * 0.72f, cy - rr * 0.72f, 5 * density, cameraDot)
            Source.OSM -> canvas.drawCircle(cx + rr * 0.72f, cy - rr * 0.72f, 5 * density, osmDot)
            Source.GOOGLE -> Unit
        }

        // The speed and its unit, coloured like Google's when speeding.
        val sx = (cx + rr + w) / 2 - 2 * density
        text.color = when (severity) {
            Severity.NONE -> Color.rgb(32, 33, 36)
            Severity.MINOR -> Color.rgb(227, 116, 0)
            Severity.MAJOR -> Color.rgb(217, 48, 37)
        }
        text.textSize = 24 * density
        canvas.drawText(s.toString(), sx, h * 0.56f, text)
        small.textSize = 11 * density
        canvas.drawText(unit, sx, h * 0.84f, small)
    }

    companion object {
        const val WIDTH_DP = 112
        const val HEIGHT_DP = 56
        private const val RING_DP = 46
    }
}

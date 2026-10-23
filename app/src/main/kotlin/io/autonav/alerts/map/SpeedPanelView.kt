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
 * Current speed next to the road's limit (DESIGN.md §8.4). Amber/red follow the Navigation SDK's
 * SpeedingListener severity. A limit that isn't Google's has a small dot so it isn't mistaken for
 * Google's figure: blue for a camera or zone, grey for OpenStreetMap (§8.4a). A change of limit
 * ahead is a small "→ 30" chevron beside the ring, on the panel's right (§8.4a, "Limit changes
 * ahead"); the panel sits bottom-right (§8) and grows to the left to make room for it.
 */
class SpeedPanelView(context: Context) : View(context) {
    enum class Severity { NONE, MINOR, MAJOR }
    enum class Source { GOOGLE, CAMERA, OSM }

    private var speed: Int? = null
    private var limit: Int? = null
    private var source = Source.GOOGLE
    private var severity = Severity.NONE
    private var unit = "mph"

    /** The limit the car is about to be under, in the display units, or null: drawn as "→ 30" beside the ring (§8.4a). */
    var upcoming: Int? = null
        set(v) {
            if (v == field) return
            field = v
            requestLayout()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(220, 30, 30) }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
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

    private val box get() = 64 * density
    private val gap get() = 8 * density
    /** Room for the "→ 30" chevron to the right of the ring, only while there is one. */
    private val chevron get() = if (upcoming != null) 44 * density else 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = box * 2 + gap + chevron
        setMeasuredDimension(w.toInt(), box.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val s = speed ?: return
        val r = 12 * density
        bg.color = when (severity) {
            Severity.NONE -> Color.argb(230, 255, 255, 255)
            Severity.MINOR -> Color.rgb(255, 179, 0)
            Severity.MAJOR -> Color.rgb(211, 47, 47)
        }
        rect.set(0f, 0f, box, box)
        canvas.drawRoundRect(rect, r, r, bg)
        text.color = if (severity == Severity.MAJOR) Color.WHITE else Color.BLACK
        text.textSize = 28 * density
        canvas.drawText(s.toString(), box / 2, box * 0.58f, text)
        small.color = text.color
        small.textSize = 11 * density
        canvas.drawText(unit, box / 2, box * 0.85f, small)

        // The limit sign is always there (DESIGN.md §8.4): "–" in a grey ring when it isn't known.
        val l = limit
        val cx = box + gap + box / 2
        val cy = box / 2
        canvas.drawCircle(cx, cy, box / 2, white)
        ring.strokeWidth = 6 * density
        ring.color = if (l == null) Color.rgb(158, 158, 158) else Color.rgb(220, 30, 30)
        canvas.drawCircle(cx, cy, box / 2 - ring.strokeWidth / 2, ring)
        text.color = if (l == null) Color.rgb(117, 117, 117) else Color.BLACK
        text.textSize = 24 * density
        canvas.drawText(l?.toString() ?: "–", cx, cy + 8 * density, text)
        if (l != null) when (source) {
            Source.CAMERA -> canvas.drawCircle(cx + box * 0.32f, cy - box * 0.32f, 5 * density, cameraDot)
            Source.OSM -> canvas.drawCircle(cx + box * 0.32f, cy - box * 0.32f, 5 * density, osmDot)
            Source.GOOGLE -> Unit
        }
        // The limit ahead: "→ 30" in a small white pill to the right of the ring (DESIGN.md §8.4a).
        val up = upcoming ?: return
        val px = box * 2 + gap + 4 * density
        val ph = 26 * density
        rect.set(px, cy - ph / 2, px + chevron - 4 * density, cy + ph / 2)
        canvas.drawRoundRect(rect, ph / 2, ph / 2, white)
        small.color = Color.BLACK
        small.textSize = 14 * density
        small.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("→$up", rect.centerX(), cy + 5 * density, small)
        small.typeface = Typeface.DEFAULT
    }
}

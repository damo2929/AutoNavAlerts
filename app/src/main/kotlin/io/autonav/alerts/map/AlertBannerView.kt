// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/**
 * The alert banner at the bottom of the car map (DESIGN.md §12.1): icon, text, subtitle and a
 * progress bar for the alert's time. Shown and hidden in place with a short fade; tapping it
 * (via the surface's onClick) dismisses it.
 */
class AlertBannerView(context: Context) : View(context) {
    private var icon: Bitmap? = null
    private var title = ""
    private var subtitle = ""
    private var shownAt = 0L
    private var durationMs = 0L
    private var alertId: Long? = null

    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 32, 33, 36) }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 70, 72, 77) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(138, 180, 248) }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26 * density }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 202, 206); textSize = 20 * density }
    private val rect = RectF()
    private val hideRunnable = Runnable { hide() }

    init {
        visibility = GONE
        alpha = 0f
    }

    /** The POI the banner is showing, or null when hidden. */
    val showing: Long? get() = alertId

    /** Shows or updates the banner. The same [id] (a later stage for the same POI) updates it in place. */
    fun show(id: Long, icon: Bitmap?, title: String, subtitle: String, durationMs: Long) {
        val fresh = alertId != id || visibility != VISIBLE
        alertId = id
        this.icon = icon
        this.title = title
        this.subtitle = subtitle
        this.durationMs = durationMs
        shownAt = SystemClock.uptimeMillis()
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, durationMs)
        if (fresh) {
            visibility = VISIBLE
            animate().cancel()
            animate().alpha(1f).setDuration(FADE_MS).start()
        }
        invalidate()
    }

    fun hide() {
        removeCallbacks(hideRunnable)
        alertId = null
        animate().cancel()
        animate().alpha(0f).setDuration(FADE_MS).withEndAction { visibility = GONE }.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (HEIGHT_DP * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 16 * density
        val pad = 14 * density
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, bg)

        val iconSize = h - pad * 2 - 8 * density
        var x = pad
        icon?.let { b ->
            rect.set(x, pad, x + iconSize, pad + iconSize)
            canvas.drawBitmap(b, null, rect, null)
            x += iconSize + pad
        }
        val textWidth = w - x - pad
        val t = TextUtils.ellipsize(title, titlePaint, textWidth, TextUtils.TruncateAt.END)
        canvas.drawText(t, 0, t.length, x, pad + titlePaint.textSize, titlePaint)
        if (subtitle.isNotEmpty()) {
            val s = TextUtils.ellipsize(subtitle, subPaint, textWidth, TextUtils.TruncateAt.END)
            canvas.drawText(s, 0, s.length, x, pad + titlePaint.textSize + 8 * density + subPaint.textSize, subPaint)
        }

        // Progress bar along the bottom for the alert's time.
        val left = (durationMs - (SystemClock.uptimeMillis() - shownAt)).coerceAtLeast(0).toFloat() / durationMs.coerceAtLeast(1)
        val barH = 4 * density
        val y = h - pad / 2 - barH
        rect.set(pad, y, w - pad, y + barH)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, track)
        rect.set(pad, y, pad + (w - pad * 2) * (1 - left), y + barH)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, bar)
        if (alertId != null) postInvalidateDelayed(100)
    }

    companion object {
        const val HEIGHT_DP = 96
        /** Widest the banner gets, so it stays a banner on wide screens. */
        const val MAX_WIDTH_DP = 640
        private const val FADE_MS = 150L
    }
}

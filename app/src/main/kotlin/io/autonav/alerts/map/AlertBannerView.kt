// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/**
 * The alert banner at the bottom of the car map (DESIGN.md §12.1): icon, text, subtitle and a
 * progress bar for the alert's time. Shown and hidden in place with a short fade; tapping it
 * (via the surface's onClick) dismisses it.
 *
 * The icon and text are centred together in the banner, which sits at the bottom centre of the map.
 *
 * GPU work (§12.1): the background, icon and text are drawn once per alert into a hardware layer
 * (a GPU texture); the progress bar is its own view whose scale is animated on the render thread,
 * so nothing is redrawn while the bar runs.
 *
 * Lifecycle: created by `MapSurface.attach` as a child of the map window's frame (after the
 * SDK view and the speed panel) and placed by `MapSurface.layoutOverlays` at the bottom centre of
 * the visible area, 16 px up; it goes with the surface on release. `NavSession.showCarAlert`
 * posts [show] to this view's thread (the main thread) from the alert thread (§12.1
 * "Threading"); `MapSurface.onClick` hit-tests the tap and calls [hide].
 *
 * Hard rules: the one alert surface on the map, with no buttons on it (rule 2; Mute is in More);
 * drawn once per alert and otherwise only animated on the render thread, never redrawn on a
 * timer (rule 3).
 */
class AlertBannerView(context: Context) : FrameLayout(context) {
    private var icon: Bitmap? = null
    private var title = ""
    private var subtitle = ""
    /** The title and subtitle after ellipsising to the width, done once in [fitText]. */
    private var titleShown: CharSequence = ""
    private var subtitleShown: CharSequence = ""
    /** Where the icon starts, so the icon and text sit centred in the banner. */
    private var contentX = 0f
    private var alertId: Long? = null

    private val density = resources.displayMetrics.density
    // The look of §12.1: #202124 at 92 % (235/255), title 26 dp white, subtitle 20 dp grey.
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 32, 33, 36) }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26 * density }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 202, 206); textSize = 20 * density }
    private val rect = RectF()
    private val hideRunnable = Runnable { hide() }

    private val pad = 14 * density
    // The progress bar: 4 dp, #8AB4F8 on a dark track, along the bottom (§12.1).
    private val barH = (4 * density).toInt()
    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = barH / 2f }
    private val track = View(context).apply { background = pill(Color.argb(255, 70, 72, 77)) }
    private val bar = View(context).apply { background = pill(Color.rgb(138, 180, 248)); pivotX = 0f; scaleX = 0f }

    init {
        setWillNotDraw(false)
        val lp = { LayoutParams(LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM).apply {
            leftMargin = pad.toInt(); rightMargin = pad.toInt(); bottomMargin = (pad / 2).toInt()
        } }
        addView(track, lp())
        addView(bar, lp())
        visibility = GONE
        alpha = 0f
    }

    /** The POI the banner is showing, or null when hidden. */
    val showing: Long? get() = alertId

    /**
     * Shows or updates the banner. The same [id] (a later stage for the same POI) updates it in place.
     *
     * @param id the POI id, the banner's identity (§12.1 "Identity").
     * @param icon the set's icon bitmap (128 px from `IconRepository`), or none.
     * @param title the alert text; [subtitle] the distance and limit line, may be empty.
     * @param durationMs how long it stays: 8 s, or 5 s for a passing alert (§12.1); restarted by every call.
     */
    fun show(id: Long, icon: Bitmap?, title: String, subtitle: String, durationMs: Long) {
        // A different POI, or a return from hidden, fades in; the same POI changes text with no fade (§12.1).
        val fresh = alertId != id || visibility != VISIBLE
        alertId = id
        this.icon = icon
        this.title = title
        this.subtitle = subtitle
        fitText()
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, durationMs)
        if (fresh) {
            visibility = VISIBLE
            // The static content lives in a GPU texture while the banner is up.
            setLayerType(LAYER_TYPE_HARDWARE, null)
            animate().cancel()
            animate().alpha(1f).setDuration(FADE_MS).start()
        }
        // The bar fills over the alert's time, animated on the render thread (no redraws here).
        bar.animate().cancel()
        bar.scaleX = 0f
        bar.animate().scaleX(1f).setDuration(durationMs).setInterpolator(LinearInterpolator()).start()
        invalidate() // the new text, drawn once into the layer
    }

    /** Hides the banner with the 150 ms fade: on a tap, or when its time runs out. Safe when already hidden. */
    fun hide() {
        removeCallbacks(hideRunnable)
        alertId = null
        bar.animate().cancel()
        animate().cancel()
        animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            visibility = GONE
            setLayerType(LAYER_TYPE_NONE, null) // free the texture while hidden
        }.start()
    }

    /** Always [HEIGHT_DP] high; the width is whatever `MapSurface` lays out (up to [MAX_WIDTH_DP]). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((HEIGHT_DP * density).toInt(), MeasureSpec.EXACTLY))
    }

    /** A new width (the host's visible area changed): the text is fitted and centred again, once. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitText()
    }

    private val iconSize get() = height - pad * 2 - 8 * density
    private val iconSpace get() = if (icon != null) iconSize + pad else 0f

    /**
     * Shortens the text to the width and centres the icon and text in the banner, once per alert
     * or size change, not on every frame.
     */
    private fun fitText() {
        val textWidth = (width - iconSpace - pad * 2).coerceAtLeast(0f)
        titleShown = TextUtils.ellipsize(title, titlePaint, textWidth, TextUtils.TruncateAt.END)
        subtitleShown = if (subtitle.isEmpty()) "" else TextUtils.ellipsize(subtitle, subPaint, textWidth, TextUtils.TruncateAt.END)
        val used = maxOf(titlePaint.measureText(titleShown, 0, titleShown.length),
            subPaint.measureText(subtitleShown, 0, subtitleShown.length))
        contentX = ((width - iconSpace - used) / 2).coerceAtLeast(pad)
    }

    // Runs once per show (into the hardware layer), not per frame: the bar animates on its own.
    override fun onDraw(canvas: Canvas) {
        // 16 dp corners (§12.1).
        val r = 16 * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bg)
        icon?.let { b ->
            rect.set(contentX, pad, contentX + iconSize, pad + iconSize)
            canvas.drawBitmap(b, null, rect, null)
        }
        val x = contentX + iconSpace
        canvas.drawText(titleShown, 0, titleShown.length, x, pad + titlePaint.textSize, titlePaint)
        if (subtitleShown.isNotEmpty()) {
            canvas.drawText(subtitleShown, 0, subtitleShown.length, x, pad + titlePaint.textSize + 8 * density + subPaint.textSize, subPaint)
        }
    }

    companion object {
        /** The banner's height (§12.1); `MapSurface.applyPadding` keeps the car above it. */
        const val HEIGHT_DP = 96
        /** Widest the banner gets, so it stays a banner on wide screens. */
        const val MAX_WIDTH_DP = 640
        /** Fade in and out, never a slide (§12.1). */
        private const val FADE_MS = 150L
    }
}

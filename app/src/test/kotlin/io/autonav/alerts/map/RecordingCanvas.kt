// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * A software canvas that records the text, circles, rectangles and bitmaps a car-map view draws, with each
 * paint's colour and style, so the view tests (DESIGN.md §8, §8.4, §12.1) can read what a frame shows without
 * comparing pixels. Test-only; runs on the Robolectric main thread. Upholds nothing itself: it is how the
 * hard-rule 2/3/4 view tests see a frame.
 */
internal class RecordingCanvas(w: Int, h: Int) : Canvas(Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)) {
    /** One draw call: what kind, its text (for text), its colour and style, and where. */
    data class Op(val kind: String, val text: String?, val color: Int, val style: Paint.Style, val x: Float, val y: Float, val size: Float)

    val ops = ArrayList<Op>()
    val texts: List<Op> get() = ops.filter { it.kind == "text" }
    val circles: List<Op> get() = ops.filter { it.kind == "circle" }
    val roundRects: List<Op> get() = ops.filter { it.kind == "roundRect" }
    val bitmaps: List<Op> get() = ops.filter { it.kind == "bitmap" }

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        ops += Op("text", text, paint.color, paint.style, x, y, paint.textSize)
    }

    override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
        ops += Op("text", text.subSequence(start, end).toString(), paint.color, paint.style, x, y, paint.textSize)
    }

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        ops += Op("circle", null, paint.color, paint.style, cx, cy, radius)
    }

    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
        ops += Op("roundRect", null, paint.color, paint.style, rect.width(), rect.height(), rx)
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        ops += Op("rect", null, paint.color, paint.style, left, top, right - left)
    }

    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        ops += Op("bitmap", null, 0, Paint.Style.FILL, dst.left, dst.top, dst.width())
    }

    companion object {
        /** Measures and lays [view] out at [w] × [h] (or its own measured size) and records one frame of it. */
        fun frameOf(view: View, w: Int? = null, h: Int? = null): RecordingCanvas {
            val ws = if (w == null) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY
            view.measure(View.MeasureSpec.makeMeasureSpec(w ?: 0, ws), View.MeasureSpec.makeMeasureSpec(h ?: 0, if (h == null) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            return RecordingCanvas(view.width, view.height).also { view.draw(it) }
        }
    }
}

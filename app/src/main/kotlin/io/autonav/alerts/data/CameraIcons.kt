// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import io.autonav.alerts.R

/**
 * Our own camera icons (DESIGN.md §7.1, owner 2026-10-25): a UK speed-limit roundel carrying the limit,
 * with a tab in the camera type's colour and its glyph, drawn from vectors at the size it is shown.
 * The glyphs are original drawings (`art/icons/glyph_<type>.svg`, made into `ic_cam_glyph_<type>`
 * by `tools/camera_icons.py`); nothing comes from the PocketGPSWorld icons (hard rule 6).
 *
 * [CameraLooks] decides which icon a camera gets (pure, any thread); [CameraIconPainter] draws it
 * (any thread; `IconRepository` caches the bitmaps). The direction arrow under a camera is drawn
 * separately and is unchanged (§8.1, hard rule 1).
 */
/**
 * A camera type's tab: its [glyph], the [tab] colour and the glyph's [tabInk]. A [sign] tab is the UK speed-camera
 * sign (fixed: white panel; SPECS: yellow panel; owner 2026-10-25): shaped like the sign, the glyph its frame and
 * camera, the [tab] colour its panel, and a temporary one keeps its dark ink on the orange panel (DESIGN.md §7.1).
 */
enum class CameraType(
    @DrawableRes val glyph: Int,
    val tab: Int,
    val tabInk: Int,
    val sign: Boolean = false,
    /** A traffic light's lamps, drawn in colour in the holes the glyph leaves for them (owner 2026-10-25). */
    val lamps: List<Lamp> = emptyList(),
    /** The glyph alone, filling the icon on a [tab]-coloured backing: no roundel and no limit (red light, owner 2026-10-25). */
    val bare: Boolean = false,
) {
    FIXED(R.drawable.ic_cam_glyph_fixed, Color.WHITE, Color.BLACK, sign = true),
    MOBILE(R.drawable.ic_cam_glyph_mobile, Color.WHITE, Color.BLACK),
    AVERAGE(R.drawable.ic_cam_glyph_fixed, 0xFFF2B705.toInt(), Color.BLACK, sign = true),
    REDLIGHT(R.drawable.ic_cam_glyph_redlight, Color.WHITE, Color.BLACK, lamps = Lamp.trafficLight(12f, 6f, 5.2f, 2f), bare = true),
    REDLIGHT_SPEED(R.drawable.ic_cam_glyph_redlight_speed, 0xFFC62828.toInt(), Color.WHITE, lamps = Lamp.trafficLight(5.75f, 6.4f, 4.8f, 1.6f)),
    // The mobile glyph in grey on white (owner 2026-10-25).
    PENDING(R.drawable.ic_cam_glyph_mobile, Color.WHITE, 0xFF757575.toInt()),
}

/** A lamp of a traffic-light glyph: centre ([cx], [cy]) and radius [r] in the glyph's 24 units, and its [color] (DESIGN.md §7.1). */
data class Lamp(val cx: Float, val cy: Float, val r: Float, val color: Int) {
    companion object {
        val RED = 0xFFE53935.toInt()
        val AMBER = 0xFFFDD835.toInt()
        val GREEN = 0xFF43A047.toInt()

        /** Red, amber and green down a column at [cx], the top one at [top], [step] apart. */
        fun trafficLight(cx: Float, top: Float, step: Float, r: Float) =
            listOf(RED, AMBER, GREEN).mapIndexed { i, c -> Lamp(cx, top + i * step, r, c) }
    }
}

/** One camera's icon: its [type], the [limit] text in the roundel ("30", "VAR", or [NO_LIMIT]), and a [temporary] (roadworks) tab. */
data class CameraLook(val type: CameraType, val limit: String = NO_LIMIT, val temporary: Boolean = false) {
    companion object {
        /** Shown in the number's place when no limit is known: a camera always shows a speed (DESIGN.md §7.1, owner 2026-10-25). */
        const val NO_LIMIT = "*"
    }
}

/** Which [CameraLook] a marker or row gets, from its icon id, category code and limit (DESIGN.md §7.1). Pure. */
object CameraLooks {
    /**
     * The look for [iconId], or null when the icon is not one of ours to draw: a user-picked image, a zip
     * import's icon, a non-camera built-in, or a download bitmap while [ours] is off (the "From the
     * download" setting). [categoryCode] is the POI's category source code (an OV2 type name such as
     * "GATSO" or "tSPECS", or an iGO type number); [speed] the POI's limit, already in the units shown.
     */
    fun of(iconId: String, categoryCode: String?, speed: Int?, ours: Boolean): CameraLook? {
        val temporary = categoryCode != null && categoryCode.length > 1 && categoryCode[0] == 't' && categoryCode[1].isUpperCase()
        if (iconId.startsWith("builtin:camera_")) {
            val type = BUILTIN[iconId] ?: return null
            return CameraLook(type, speed?.toString() ?: CameraLook.NO_LIMIT, temporary)
        }
        if (!ours || !iconId.startsWith("user:pgw_")) return null
        // A download file stem such as "gatso_30", "specs_var", "redlight" (DESIGN.md §5.2a).
        val stem = iconId.removePrefix("user:pgw_")
        val typePart = stem.substringBefore('_')
        val speedPart = stem.substringAfter('_', "").ifEmpty { null }
        val type = categoryCode?.let(::typeOfCode) ?: typeOfCode(typePart) ?: return null
        val limit = speed?.toString() ?: when (speedPart) {
            "var" -> "VAR"
            else -> speedPart?.toIntOrNull()?.toString()
        } ?: CameraLook.NO_LIMIT
        return CameraLook(type, limit, temporary)
    }

    /** An OV2 type name (any case, optional `t` prefix) or an iGO type number → the camera type; null if unknown. */
    fun typeOfCode(code: String): CameraType? {
        val c = if (code.length > 1 && code[0] == 't' && code[1].isUpperCase()) code.substring(1) else code
        return when (c.lowercase()) {
            "gatso", "monitron", "truvelo", "1" -> CameraType.FIXED
            "mobile", "5" -> CameraType.MOBILE
            "specs", "4" -> CameraType.AVERAGE
            "redlight", "3" -> CameraType.REDLIGHT
            "redspeed", "11" -> CameraType.REDLIGHT_SPEED
            "pmobile" -> CameraType.PENDING
            else -> null
        }
    }

    private val BUILTIN = mapOf(
        IconRepository.CAMERA_FIXED to CameraType.FIXED,
        IconRepository.CAMERA_MOBILE to CameraType.MOBILE,
        IconRepository.CAMERA_AVERAGE to CameraType.AVERAGE,
        IconRepository.CAMERA_REDLIGHT to CameraType.REDLIGHT,
        IconRepository.CAMERA_REDLIGHT_SPEED to CameraType.REDLIGHT_SPEED,
        IconRepository.CAMERA_PENDING to CameraType.PENDING,
    )
}

/**
 * Draws a [CameraLook] as a square bitmap (DESIGN.md §7.1). The layout is on a 48-unit box and must
 * match `tools/camera_icons.py`, which writes the review previews: change both together.
 * With a limit: roundel at (21, 21) r 20, red ring r 17 × 4.2, the limit in bold condensed digits
 * (17 units, 12 for three characters), tab 20 units square at (27, 27), corner 5, white edge 1.5,
 * glyph 72% of the tab. Without: roundel at the centre r 22.5, ring r 19.5 × 4.6, the glyph 24 units.
 */
class CameraIconPainter(private val context: Context) {
    private val font: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun draw(look: CameraLook, sizePx: Int): Bitmap {
        val out = createBitmap(sizePx, sizePx)
        val c = Canvas(out)
        val k = sizePx / 48f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val type = look.type
        if (type.bare) return bare(out, c, look, k)
        roundel(c, fill, 21f * k, 21f * k, 20f * k, 17f * k, 4.2f * k)
        val star = look.limit == CameraLook.NO_LIMIT
        val size = (if (star) 28f else if (look.limit.length <= 2) 17f else 12f) * k
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = size; typeface = font; textAlign = Paint.Align.CENTER }
        // Digits sit on the baseline; "*" sits high, so it is centred by its own bounds.
        val y = if (star) 21f * k - android.graphics.Rect().also { text.getTextBounds(look.limit, 0, 1, it) }.exactCenterY() else 21f * k + size * 0.36f
        c.drawText(look.limit, 21f * k, y, text)
        // A sign tab is the sign's own shape, bottom right; any other tab a rounded square (DESIGN.md §7.1).
        val tab = if (type.sign) signRect((47f - 10f * SIGN_W) * k, (47f - 10f * SIGN_H) * k, 20f * k) else RectF(27f * k, 27f * k, 47f * k, 47f * k)
        val rx = (if (type.sign) SIGN_RX * 20f / 24f else 5f) * k
        fill.style = Paint.Style.FILL
        fill.color = if (look.temporary) TEMPORARY else type.tab
        c.drawRoundRect(tab, rx, rx, fill)
        fill.style = Paint.Style.STROKE; fill.strokeWidth = 1.5f * k; fill.color = Color.WHITE
        c.drawRoundRect(tab, rx, rx, fill)
        val ink = if (look.temporary && !type.sign) Color.WHITE else type.tabInk
        typeGlyph(c, type, ink, tab.centerX(), tab.centerY(), (if (type.sign) 20f else 20f * 0.72f) * k)
        return out
    }

    /**
     * A [CameraType.bare] icon: the glyph filling the box ([BARE_GLYPH] units) on a rounded backing round its
     * housing ([BARE_BACKING], glyph units), the type's colour or orange when temporary, then its lamps (§7.1).
     */
    private fun bare(out: Bitmap, c: Canvas, look: CameraLook, k: Float): Bitmap {
        val g = BARE_GLYPH * k
        val u = g / 24f
        val left = 24f * k - g / 2
        val top = 24f * k - g / 2
        val (bl, bt, br, bb) = BARE_BACKING
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (look.temporary) TEMPORARY else look.type.tab }
        c.drawRoundRect(RectF(left + bl * u, top + bt * u, left + br * u, top + bb * u), 3.5f * u, 3.5f * u, fill)
        typeGlyph(c, look.type, look.type.tabInk, 24f * k, 24f * k, g)
        return out
    }

    /** [type]'s glyph tinted [ink], [size] square centred on ([cx], [cy]), then its lamps in colour in the same box. Both layouts draw through here. */
    private fun typeGlyph(c: Canvas, type: CameraType, ink: Int, cx: Float, cy: Float, size: Float) {
        glyph(c, type.glyph, ink, cx, cy, size)
        val u = size / 24f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        for (lamp in type.lamps) {
            fill.color = lamp.color
            c.drawCircle(cx - size / 2 + lamp.cx * u, cy - size / 2 + lamp.cy * u, lamp.r * u, fill)
        }
    }

    /** The sign's frame when its glyph is [size] square centred on ([cx], [cy]), so the panel never shows outside it. */
    private fun signRect(cx: Float, cy: Float, size: Float) =
        RectF(cx - size * SIGN_W / 2, cy - size * SIGN_H / 2, cx + size * SIGN_W / 2, cy + size * SIGN_H / 2)

    /** The white disc and red ring of a UK limit sign. */
    private fun roundel(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, ringR: Float, ringW: Float) {
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = ringW; p.color = RING
        c.drawCircle(cx, cy, ringR, p)
    }

    /** One glyph drawable, tinted [color], [size] square centred on ([cx], [cy]). */
    private fun glyph(c: Canvas, @DrawableRes res: Int, color: Int, cx: Float, cy: Float, size: Float) {
        val d = ContextCompat.getDrawable(context, res)!!.mutate()
        d.setTint(color)
        d.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        d.draw(c)
    }

    companion object {
        val RING = 0xFFD32F2F.toInt()
        val INK = 0xFF212121.toInt()
        val TEMPORARY = 0xFFEF6C00.toInt()
        /** The sign glyph's frame as fractions of the glyph (23 × 19.68 of 24 units: the sign is 340 × 291) and its corner radius in glyph units; mirrored in tools/camera_icons.py. */
        const val SIGN_W = 23f / 24f
        const val SIGN_H = 19.68f / 24f
        const val SIGN_RX = 2.84f
        /** A bare icon's glyph size in the 48-unit box, and its backing (left, top, right, bottom in glyph units) round the traffic light's housing and pole; mirrored in tools/camera_icons.py. */
        const val BARE_GLYPH = 46f
        val BARE_BACKING = floatArrayOf(5.6f, 0.3f, 18.4f, 23.7f)
    }
}

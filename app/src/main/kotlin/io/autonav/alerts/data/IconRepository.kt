// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.R
import io.autonav.alerts.core.poi.DirectionType
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Built-in and user icons, rendered to cached bitmaps (DESIGN.md §7.1), and the map marker and direction
 * arrow drawn from them (§8.1). A Hilt singleton. Called by `PoiMarkerLayer` for every marker it adds or
 * changes (so a repeat call must be a cache hit: the first render of an icon decodes a file), by
 * `AlertDispatcher` for the car `Alert` icon, by `PoiRepository` when a download brings icons, and by
 * the phone's icon picker and set editor. The bitmap LruCache is synchronised (a race only costs a duplicate
 * render); the arrow map is not, and only `PoiMarkerLayer` uses it, from one thread.
 * Icon ids: "builtin:<name>" for the vector pack, "user:<uuid>" for a picked image, "user:pgw_<stem>"
 * for a PocketGPSWorld download's own icon, "user:imp_<name>" for one found in an imported zip (§5, §5.2a).
 */
@Singleton
class IconRepository @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Marker emphasis (DESIGN.md §8.1): ACTIVE the camera being alerted on (1.3×, pulse ring), AHEAD
     * the engine's AHEAD_OK (full colour), FACING_AWAY a camera on our road pointing the other way
     * (35 % alpha, never hidden; owner's rule), OTHER everything else (60 %).
     */
    enum class Emphasis { ACTIVE, AHEAD, OTHER, FACING_AWAY }

    // Rendered bitmaps by "<id>@<px>" and "m:<id>:<speed>:<emphasis>:<px>" (§7.1 "Cache"; 96 rather than the 64 written there).
    private val cache = LruCache<String, Bitmap>(96)
    /** The arrow descriptors, by direction type, emphasis and size: at most 8, made once (§8.1). */
    private val arrows = HashMap<String, BitmapDescriptor>()

    /** `filesDir/icons/`: every "user:" icon lives here as `<id>.png` (§7.1). Private storage, so it goes with an uninstall. */
    private fun dirUser() = File(context.filesDir, "icons").apply { mkdirs() }

    /** The built-in pack's ids, for the icon picker (§13 item 6). */
    fun builtinIds(): List<String> = BUILTIN.keys.toList()
    /** Every "user:" icon on disk (picked, PGW-supplied and zip-supplied alike), sorted; the picker and the backup restore's "does this icon exist" check (§13.4). */
    fun userIds(): List<String> = dirUser().listFiles { f -> f.extension == "png" }?.map { "user:" + it.nameWithoutExtension }?.sorted() ?: emptyList()

    /** A short name for the picker and the set editor. */
    fun label(id: String): String = BUILTIN_LABELS[id] ?: if (isPgwIcon(id)) "PocketGPSWorld icon" else "Custom icon"

    /**
     * Copies a picked image into app storage and returns its id, or null if it can't be decoded (§7.1 "User icons").
     * Scaled to 256 px wide, keeping the aspect ratio. Bitmap formats only: there is no SVG decoding here.
     */
    fun addUserIcon(uri: Uri): String? {
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val scaled = Bitmap.createScaledBitmap(bmp, 256, 256 * bmp.height / bmp.width.coerceAtLeast(1), true)
        val id = UUID.randomUUID().toString()
        File(dirUser(), "$id.png").outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "user:$id"
    }

    /**
     * Saves an icon shipped in a PGW download (BMP) as `user:pgw_<stem>`; null if it can't be decoded (§5.2a
     * "Icons supplied in the download are used"). Overwrites on every sync, so a refreshed icon shows at once.
     */
    fun savePgwIcon(stem: String, bmp: ByteArray): String? = saveIcon("pgw_" + stem.replace(Regex("[^a-z0-9_]"), "_"), bmp)

    /** Saves an icon supplied inside an imported zip (BMP/PNG) as `user:imp_<name>` (§5: "icons from matching .bmp/.png"); null if it can't be decoded. */
    fun saveImportedIcon(name: String, bytes: ByteArray): String? = saveIcon("imp_" + name.lowercase().replace(Regex("[^a-z0-9_]"), "_"), bytes)

    /** A supplied icon's [bytes] decoded and written as `<id>.png`, replacing any older file; null if they don't decode. */
    private fun saveIcon(id: String, bytes: ByteArray): String? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        File(dirUser(), "$id.png").outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The old rendering of this id may be cached; drop everything rather than track which keys hold it.
        cache.evictAll()
        return "user:$id"
    }

    /** Keeps the icon licence next to the icons, as the licence requires (§5.2a: the `_X2` icons are free for non-commercial use with credit). */
    fun savePgwIconLicence(text: ByteArray) {
        File(dirUser(), "LICENCE_pgw_X2_icons.txt").writeBytes(text)
    }

    /** The saved licence text for the set editor and Settings → About "Show icon licence" (§5.2a), or null before any OV2 download. */
    fun pgwIconLicence(): String? = File(dirUser(), "LICENCE_pgw_X2_icons.txt").takeIf { it.exists() }?.readText(Charsets.ISO_8859_1)

    /**
     * Square icon bitmap in pixels, cached per (id, size). A "user:" icon whose file has gone (an uninstall,
     * a set restored before its download) falls back to the pin, as does an unknown "builtin:" id, so a
     * marker is always drawn.
     */
    fun bitmap(id: String, sizePx: Int): Bitmap {
        val key = "$id@$sizePx"
        cache.get(key)?.let { return it }
        val bmp = when {
            id.startsWith("user:") -> File(dirUser(), id.removePrefix("user:") + ".png").takeIf { it.exists() }
                ?.let { BitmapFactory.decodeFile(it.path) }?.let { Bitmap.createScaledBitmap(it, sizePx, sizePx, true) }
            else -> null
        } ?: ContextCompat.getDrawable(context, BUILTIN[id] ?: R.drawable.ic_poi_pin)!!.toBitmap(sizePx, sizePx)
        cache.put(key, bmp)
        return bmp
    }

    /**
     * Map marker: icon, optional speed badge, and emphasis (DESIGN.md §8.1 step 3), cached per
     * (icon, speed, emphasis, size) so a redraw never creates a bitmap. [basePx] is the 48 dp marker in
     * pixels; ACTIVE is drawn 1.3× with a translucent red pulse ring behind the icon. [speed] is already
     * in the display units (the caller converts, §8.4 "Units").
     */
    fun marker(id: String, speed: Int?, emphasis: Emphasis, basePx: Int): Bitmap {
        val key = "m:$id:$speed:$emphasis:$basePx"
        cache.get(key)?.let { return it }
        val size = if (emphasis == Emphasis.ACTIVE) (basePx * 1.3f).toInt() else basePx
        val out = createBitmap(size, size)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = alphaOf(emphasis) }
        if (emphasis == Emphasis.ACTIVE) {
            // The pulse ring (§8.1 ACTIVE): a static translucent disc; the "pulse" is the layer swapping emphasis.
            c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 230, 40, 40) })
        }
        // A camera is drawn with its icon alone, never with a limit badge (DESIGN.md §7, owner). Not larger:
        // the head of the flat direction arrow under it must stay clear on the tilted map (hard rule 1).
        val camera = isCameraIcon(id)
        val inner = (size * 0.8f).toInt()
        c.drawBitmap(bitmap(id, inner), (size - inner) / 2f, (size - inner) / 2f, paint)
        if (speed != null && !camera) {
            // The limit badge for non-camera POIs (§7.1): a white disc, a red ring, the number in bold, bottom right.
            val r = size * 0.22f
            val cx = size - r
            val cy = size - r
            c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawCircle(cx, cy, r * 0.92f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 30, 30); style = Paint.Style.STROKE; strokeWidth = r * 0.22f })
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = r * 0.95f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            c.drawText(speed.toString(), cx, cy - (tp.ascent() + tp.descent()) / 2, tp)
        }
        cache.put(key, out)
        return out
    }

    /**
     * The direction arrow drawn as a second, flat marker under a camera's icon (DESIGN.md §8.1): a
     * chevron pointing up on a transparent square [sizePx] wide (2.2× the icon, so the head sits
     * outside the icon when the marker is rotated to the camera's bearing); two-headed for BOTH;
     * dimmed with the emphasis. Pre-rendered and cached by (direction, emphasis, size): ≤ 8 descriptors.
     */
    fun arrow(directionType: DirectionType, emphasis: Emphasis, sizePx: Int): BitmapDescriptor {
        val key = "$directionType:$emphasis:$sizePx"
        arrows[key]?.let { return it }
        val bmp = createBitmap(sizePx, sizePx)
        val c = Canvas(bmp)
        val w = sizePx.toFloat()
        val stroke = w * 0.055f
        val head = Path().apply {
            moveTo(w * 0.30f, w * 0.25f)
            lineTo(w * 0.50f, w * 0.06f)
            lineTo(w * 0.70f, w * 0.25f)
        }
        val halo = arrowStroke(Color.WHITE, stroke * 2.2f, emphasis)
        val ink = arrowStroke(Color.rgb(200, 30, 30), stroke, emphasis)
        c.drawPath(head, halo)
        c.drawPath(head, ink)
        if (directionType == DirectionType.BOTH) {
            c.save()
            c.rotate(180f, w / 2, w / 2)
            c.drawPath(head, halo)
            c.drawPath(head, ink)
            c.restore()
        }
        val d = BitmapDescriptorFactory.fromBitmap(bmp)
        if (arrows.size >= 8) arrows.clear()
        arrows[key] = d
        return d
    }

    /** A round-capped stroke of [color] and [width] for the arrow's chevron, dimmed with the [emphasis]. */
    private fun arrowStroke(color: Int, width: Float, emphasis: Emphasis) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; alpha = alphaOf(emphasis)
    }

    /** Marker opacity per emphasis (§8.1 step 3): full, 60% for OTHER, 35% for FACING_AWAY (dimmed, never hidden; hard rule 1). */
    private fun alphaOf(emphasis: Emphasis): Int = when (emphasis) {
        Emphasis.ACTIVE, Emphasis.AHEAD -> 255
        Emphasis.OTHER -> 153
        Emphasis.FACING_AWAY -> 89
    }

    companion object {
        /**
         * A camera set's own icon (PocketGPSWorld's or a zip import's, [isSuppliedIcon]) or our built-in camera
         * icons. Decides "icon alone, never with a limit badge" in [marker] (§7.1, owner 2026-10-23).
         */
        fun isCameraIcon(id: String): Boolean = isSuppliedIcon(id) || id.startsWith("builtin:camera_")

        // The built-in pack's ids (§7.1). The drawables are vector assets under res/drawable/ic_poi_*.
        const val CAMERA_FIXED = "builtin:camera_fixed"
        const val CAMERA_MOBILE = "builtin:camera_mobile"
        const val CAMERA_AVERAGE = "builtin:camera_average"
        const val CAMERA_REDLIGHT = "builtin:camera_redlight"
        const val CAMERA_REDLIGHT_SPEED = "builtin:camera_redlight_speed"
        const val CAMERA_PENDING = "builtin:camera_pending"
        const val PIN = "builtin:pin"
        const val HAZARD = "builtin:hazard"
        const val SCHOOL = "builtin:school"
        const val FUEL = "builtin:fuel"
        const val EV = "builtin:ev"
        const val STAR = "builtin:star"

        /** Id → drawable for the built-in pack; also the "is this a built-in" test used when a sync decides whether to refresh a set's icon (§5.2a). */
        val BUILTIN = mapOf(
            CAMERA_FIXED to R.drawable.ic_poi_camera_fixed,
            CAMERA_MOBILE to R.drawable.ic_poi_camera_mobile,
            CAMERA_AVERAGE to R.drawable.ic_poi_camera_average,
            CAMERA_REDLIGHT to R.drawable.ic_poi_camera_redlight,
            CAMERA_REDLIGHT_SPEED to R.drawable.ic_poi_camera_redlight_speed,
            CAMERA_PENDING to R.drawable.ic_poi_camera_pending,
            PIN to R.drawable.ic_poi_pin,
            HAZARD to R.drawable.ic_poi_hazard,
            SCHOOL to R.drawable.ic_poi_school,
            FUEL to R.drawable.ic_poi_fuel,
            EV to R.drawable.ic_poi_ev,
            STAR to R.drawable.ic_poi_star,
        )

        /** Picker labels for the built-in pack. */
        val BUILTIN_LABELS = mapOf(
            CAMERA_FIXED to "Fixed camera", CAMERA_MOBILE to "Mobile camera", CAMERA_AVERAGE to "Average speed",
            CAMERA_REDLIGHT to "Red light", CAMERA_REDLIGHT_SPEED to "Red light + speed", CAMERA_PENDING to "Pending site",
            PIN to "Pin", HAZARD to "Hazard", SCHOOL to "School", FUEL to "Fuel", EV to "EV charger", STAR to "Star",
        )

        /** An icon that came in a PocketGPSWorld download (`user:pgw_<stem>`, §5.2a). */
        fun isPgwIcon(id: String) = id.startsWith("user:pgw_")

        /** Icons that came with a download or imported zip (refreshed on re-import unless the user picked another). */
        fun isSuppliedIcon(id: String) = isPgwIcon(id) || id.startsWith("user:imp_")

        /**
         * The built-in icon for a PGW category when the download brought no icon of its own (the iGO
         * package): by iGO type code (§5.2: 1 fixed, 3 red light, 4 average speed, 5 mobile, 11 red light + speed)
         * or by OV2 type name (§5.2a). Pending mobile sites get the lighter "P" icon whatever the code (§5.2).
         */
        fun pgwIconFor(code: String, source: PoiSourceType): String {
            // TomTom OV2 type codes carry an optional "t" prefix (= temporary).
            val ov2 = code.removePrefix("t")
            return when {
                source == PoiSourceType.PGW_PENDING || code == "pMOBILE" -> CAMERA_PENDING
                ov2 in setOf("GATSO", "MONITRON", "TRUVELO") -> CAMERA_FIXED
                ov2 == "REDSPEED" -> CAMERA_REDLIGHT_SPEED
                ov2 == "REDLIGHT" -> CAMERA_REDLIGHT
                ov2 == "SPECS" -> CAMERA_AVERAGE
                ov2 == "MOBILE" -> CAMERA_MOBILE
                // iGO TXT type codes (§5.2)
                code == "1" -> CAMERA_FIXED
                code == "3" -> CAMERA_REDLIGHT
                code == "4" -> CAMERA_AVERAGE
                code == "5" -> CAMERA_MOBILE
                // 11 is the OV2 download's REDSPEED (§5.2a); §5.2 shows it as "Fixed camera (type 11)".
                code == "11" -> CAMERA_REDLIGHT_SPEED
                // An unknown code becomes a "Type N" category (§5.2) with the fixed-camera icon.
                else -> CAMERA_FIXED
            }
        }
    }
}

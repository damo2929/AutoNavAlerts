// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.R
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Built-in and user icons, rendered to cached bitmaps (DESIGN.md §7.1). */
@Singleton
class IconRepository @Inject constructor(@ApplicationContext private val context: Context) {

    enum class Emphasis { ACTIVE, AHEAD, OTHER }

    private val cache = LruCache<String, Bitmap>(96)

    private fun dirUser() = File(context.filesDir, "icons").apply { mkdirs() }

    fun builtinIds(): List<String> = BUILTIN.keys.toList()
    fun userIds(): List<String> = dirUser().listFiles { f -> f.extension == "png" }?.map { "user:" + it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun label(id: String): String = BUILTIN_LABELS[id] ?: if (isPgwIcon(id)) "PocketGPSWorld icon" else "Custom icon"

    /** Copies a picked image into app storage and returns its id. */
    fun addUserIcon(uri: Uri): String? {
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val scaled = Bitmap.createScaledBitmap(bmp, 256, 256 * bmp.height / bmp.width.coerceAtLeast(1), true)
        val id = UUID.randomUUID().toString()
        File(dirUser(), "$id.png").outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "user:$id"
    }

    /** Saves an icon shipped in a PGW download (BMP) as `user:pgw_<stem>`; null if it can't be decoded. */
    fun savePgwIcon(stem: String, bmp: ByteArray): String? {
        val decoded = BitmapFactory.decodeByteArray(bmp, 0, bmp.size) ?: return null
        val id = "pgw_" + stem.replace(Regex("[^a-z0-9_]"), "_")
        File(dirUser(), "$id.png").outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache.evictAll()
        return "user:$id"
    }

    /** Saves an icon supplied inside an imported zip (BMP/PNG) as `user:imp_<name>`. */
    fun saveImportedIcon(name: String, bytes: ByteArray): String? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val id = "imp_" + name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        File(dirUser(), "$id.png").outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache.evictAll()
        return "user:$id"
    }

    /** Keeps the icon licence next to the icons, as the licence requires. */
    fun savePgwIconLicence(text: ByteArray) {
        File(dirUser(), "LICENCE_pgw_X2_icons.txt").writeBytes(text)
    }

    fun pgwIconLicence(): String? = File(dirUser(), "LICENCE_pgw_X2_icons.txt").takeIf { it.exists() }?.readText(Charsets.ISO_8859_1)

    /** Square icon bitmap in pixels. */
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

    /** Map marker: icon, optional speed badge, and emphasis (DESIGN.md §8.1). */
    fun marker(id: String, speed: Int?, emphasis: Emphasis, basePx: Int): Bitmap {
        val key = "m:$id:$speed:$emphasis:$basePx"
        cache.get(key)?.let { return it }
        val size = if (emphasis == Emphasis.ACTIVE) (basePx * 1.3f).toInt() else basePx
        val out = createBitmap(size, size)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = if (emphasis == Emphasis.OTHER) 153 else 255 }
        if (emphasis == Emphasis.ACTIVE) {
            c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 230, 40, 40) })
        }
        val inner = (size * 0.8f).toInt()
        c.drawBitmap(bitmap(id, inner), (size - inner) / 2f, (size - inner) / 2f, paint)
        if (speed != null) {
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

    companion object {
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

        val BUILTIN_LABELS = mapOf(
            CAMERA_FIXED to "Fixed camera", CAMERA_MOBILE to "Mobile camera", CAMERA_AVERAGE to "Average speed",
            CAMERA_REDLIGHT to "Red light", CAMERA_REDLIGHT_SPEED to "Red light + speed", CAMERA_PENDING to "Pending site",
            PIN to "Pin", HAZARD to "Hazard", SCHOOL to "School", FUEL to "Fuel", EV to "EV charger", STAR to "Star",
        )

        fun isPgwIcon(id: String) = id.startsWith("user:pgw_")

        /** Icons that came with a download or imported zip (refreshed on re-import unless the user picked another). */
        fun isSuppliedIcon(id: String) = isPgwIcon(id) || id.startsWith("user:imp_")

        fun pgwIconFor(code: String, source: PoiSourceType): String = when {
            source == PoiSourceType.PGW_PENDING || code == "pMOBILE" -> CAMERA_PENDING
            // TomTom OV2 type codes (optional "t" prefix = temporary)
            code.removePrefix("t") in setOf("GATSO", "MONITRON", "TRUVELO") -> CAMERA_FIXED
            code.removePrefix("t") == "REDSPEED" -> CAMERA_REDLIGHT_SPEED
            code.removePrefix("t") == "REDLIGHT" -> CAMERA_REDLIGHT
            code.removePrefix("t") == "SPECS" -> CAMERA_AVERAGE
            code.removePrefix("t") == "MOBILE" -> CAMERA_MOBILE
            code == "1" -> CAMERA_FIXED
            code == "3" -> CAMERA_REDLIGHT
            code == "4" -> CAMERA_AVERAGE
            code == "5" -> CAMERA_MOBILE
            code == "11" -> CAMERA_REDLIGHT_SPEED
            else -> CAMERA_FIXED
        }
    }
}

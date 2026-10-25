// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.Grid
import io.autonav.alerts.data.AppDatabase
import io.autonav.alerts.data.PoiEntity
import io.autonav.alerts.data.toCols
import io.autonav.alerts.core.alerts.AlertStage
import io.autonav.alerts.core.alerts.AlertTemplateRenderer
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.PoiSourceType
import io.autonav.alerts.data.SetState
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Made-up data for the data and background tests (DESIGN.md §5.2 "Fixtures", §15.2): files that copy the
 * structure of PocketGPSWorld's downloads (the iGO header, empty fields, Direction=360, the copyright last
 * row; OV2 names with compass, type, id and speed) with invented positions. Hard rule 6 (AGENTS.md §3.1):
 * no real PocketGPSWorld data is in here or anywhere in the tests. Test thread only.
 */
object DataFixtures {
    /** An in-memory `poi.db` with the app's schema; main-thread queries allowed for the test thread. */
    fun memoryDb(context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

    /** A set row built from the repository's own defaults (§7.2 templates, §11.1 stages). */
    fun set(name: String, source: PoiSourceType = PoiSourceType.FILE, format: String = "KML:$name", state: SetState = SetState.ACTIVE, enabled: Boolean = true) =
        PoiSetEntity(
            name = name, source = source, format = format, enabled = enabled, defaultIconId = "builtin:pin",
            firstTemplate = AlertTemplateRenderer.DEFAULT_FIRST, secondTemplate = AlertTemplateRenderer.DEFAULT_SECOND,
            passingTemplate = AlertTemplateRenderer.DEFAULT_PASSING, firstAlert = AlertStage.DEFAULT_FIRST.toCols(),
            secondAlert = AlertStage.DEFAULT_SECOND.toCols(), state = state, lastUpdated = 1L,
        )

    /** A POI at ([lat], [lon]) in set [setId], its grid cell computed as the importer does (§4.3). */
    fun poi(setId: Long, lat: Double, lon: Double, hidden: Boolean = false, expiresAt: Long? = null, sourceId: String? = null) = PoiEntity(
        setId = setId, categoryId = null, lat = lat, lon = lon, name = null, speedLimit = 30, speedUnit = null,
        directionType = DirectionType.ONE_WAY, bearing = 90f, gridCell = Grid.cellOf(lat, lon), hiddenByReport = hidden,
        expiresAt = expiresAt, sourceId = sourceId,
    )

    /** An iGO TXT file as PGW lays it out (§5.2): header, rows, a 360° direction, empty fields, the copyright row. */
    fun igo(vararg rows: String): String =
        (listOf("X,Y,Type,Speed,DirType,Direction") + rows + "0.70001,51.50001,Copyright Example,,").joinToString("\n") + "\n"

    /** One OV2 type-2 record: little-endian length, lon and lat ×100000, the name NUL-terminated (§5.6). */
    fun ov2Record(lat: Double, lon: Double, name: String): ByteArray {
        val text = name.toByteArray(Charsets.ISO_8859_1)
        val len = 1 + 4 + 4 + 4 + text.size + 1
        return ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(2); putInt(len); putInt(Math.round(lon * 100_000).toInt()); putInt(Math.round(lat * 100_000).toInt()); put(text); put(0)
        }.array()
    }

    /** An OV2 file of PGW-style named entries plus the copyright watermark entry the parser skips (§5.2a). */
    fun ov2(vararg entries: Triple<Double, Double, String>): ByteArray =
        (entries.map { (la, lo, n) -> ov2Record(la, lo, n) } + ov2Record(51.5, 0.7, "Copyright Example")).reduce { a, b -> a + b }

    /** A small real PNG (a decodable icon), drawn and compressed by the framework. */
    fun png(color: Int = Color.RED): ByteArray {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Writes a zip of [entries] (name to bytes) to [dest]; [stored] entries are kept uncompressed so a download passes the 50 KB check (§6.1 step 2.4). */
    fun zip(dest: File, entries: List<Pair<String, ByteArray>>, stored: Set<String> = emptySet()): File {
        ZipOutputStream(dest.outputStream()).use { z ->
            for ((name, bytes) in entries) {
                val e = ZipEntry(name)
                if (name in stored) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                z.putNextEntry(e); z.write(bytes); z.closeEntry()
            }
        }
        return dest
    }

    /** Filler for a download that must be over 50 KB: deterministic bytes in a help page the importer ignores. */
    fun padding(size: Int = 64_000): ByteArray = ByteArray(size) { (it * 31 % 251).toByte() }
}

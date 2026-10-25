// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.io.child
import io.autonav.alerts.core.io.childText
import io.autonav.alerts.core.io.elements
import io.autonav.alerts.core.io.secureDocumentBuilderFactory
import io.autonav.alerts.core.io.skipFully
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ImportResult
import io.autonav.alerts.core.poi.ImportRow
import io.autonav.alerts.core.poi.RowError
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.core.poi.isPlausibleCoordinate
import org.w3c.dom.Element
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The file formats the importers read (DESIGN.md §5); the set's `format` column (§4.1). */
enum class PoiFormat { IGO_TXT, KML, GPX, OV2, CSV }

/** Guesses a format from the file name and first bytes (DESIGN.md §5). */
object FormatSniffer {
    /** The format from the name's extension or the first bytes ([head], a few hundred bytes), or null. */
    fun detect(fileName: String, head: ByteArray): PoiFormat? {
        val name = fileName.lowercase()
        val text = String(head, Charsets.ISO_8859_1)
        return when {
            name.endsWith(".ov2") -> PoiFormat.OV2
            name.endsWith(".kml") || text.contains("<kml") -> PoiFormat.KML
            name.endsWith(".gpx") || text.contains("<gpx") -> PoiFormat.GPX
            IgoTxtImporter().sniff(text) -> PoiFormat.IGO_TXT
            name.endsWith(".csv") || name.endsWith(".txt") -> PoiFormat.CSV
            else -> null
        }
    }
}

/**
 * A limit in a POI's name: a standalone 2–3 digit number, never part of a road or junction number
 * ("M25", "J10", "A303", "B1234" are not limits). A number with a unit wins over a bare one.
 */
private val SPEED_IN_TEXT = Regex("""(?<![A-Za-z\d])(\d{2,3})(?!\d)\s*(mph|km/?h|kph)?""", RegexOption.IGNORE_CASE)

/** The limit in a name or description per [SPEED_IN_TEXT], 5–200 only, with its unit when one was written (§5.6). */
internal fun speedFromText(text: String?): Pair<Int, SpeedUnit?>? {
    if (text.isNullOrBlank()) return null
    val all = SPEED_IN_TEXT.findAll(text).filter { it.groupValues[1].toInt() in 5..200 }.toList()
    val m = all.firstOrNull { it.groupValues[2].isNotEmpty() } ?: all.firstOrNull() ?: return null
    return m.groupValues[1].toInt() to when (m.groupValues[2].lowercase()) {
        "mph" -> SpeedUnit.MPH
        "" -> null
        else -> SpeedUnit.KMH
    }
}

/**
 * The one coordinate check of DESIGN.md §5.1 for every importer: [lat] and [lon] when both parsed
 * and [isPlausibleCoordinate] holds, else a "bad coordinates" [RowError] for [line] is added and
 * null comes back (the caller skips the row).
 */
internal fun MutableList<RowError>.checkCoordinate(lat: Double?, lon: Double?, lineNumber: Int, line: String): Pair<Double, Double>? {
    if (lat == null || lon == null || !isPlausibleCoordinate(lat, lon)) { this += RowError(lineNumber, line, "bad coordinates"); return null }
    return lat to lon
}

/** A bearing text as degrees true in [0, 360), or null when it isn't a number. */
internal fun String.toBearingOrNull(): Float? = toFloatOrNull()?.let { ((it % 360f) + 360f) % 360f }

/**
 * The direction type a source's row gets (§4.1, §11 step 3): ANY without a [bearing] (the importers
 * never invent a direction), else BOTH when the source says [both], else ONE_WAY.
 */
internal fun dirTypeOf(bearing: Float?, both: Boolean): DirectionType = when {
    bearing == null -> DirectionType.ANY
    both -> DirectionType.BOTH
    else -> DirectionType.ONE_WAY
}

/** What an importer collects from one file: the good rows, the bad rows with their reasons, and the rows skipped on purpose. */
internal class ImportCollector {
    val rows = ArrayList<ImportRow>()
    val errors = ArrayList<RowError>()
    var silentlySkipped = 0
}

/** Runs an importer's [body] over a fresh [ImportCollector] and returns what it collected as the [ImportResult]. */
internal inline fun collectImport(body: ImportCollector.() -> Unit): ImportResult =
    ImportCollector().apply(body).let { ImportResult(it.rows, it.errors, it.silentlySkipped) }

/**
 * An [ImportRow] from what an importer found: [speed] as (value, unit-or-null) with the unit
 * falling back to [defaultUnit], and the [bearing] kept only when [dirType] isn't ANY, since a
 * direction needs a type to be judged by (§4.1, §11 step 3). The importers never invent a direction.
 */
internal fun importRow(
    lat: Double, lon: Double, name: String?, categoryCode: String?,
    speed: Pair<Int, SpeedUnit?>?, defaultUnit: SpeedUnit,
    dirType: DirectionType, bearing: Float?, extra: Map<String, String> = emptyMap(),
): ImportRow = ImportRow(
    lat = lat, lon = lon, name = name, categoryCode = categoryCode,
    speed = speed?.first, speedUnit = speed?.let { it.second ?: defaultUnit },
    dirType = dirType, bearing = if (dirType == DirectionType.ANY) null else bearing, extra = extra,
)

// XML from a user's file: no DTDs or external entities, so a crafted file can't read the phone.
private fun secureFactory() = secureDocumentBuilderFactory()

/** A child element's text trimmed, or null when it is missing or blank. */
private fun Element.text(tag: String): String? = childText(tag)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * KML Placemarks with Points (DESIGN.md §5.4). Lines and polygons are ignored. The category is
 * the enclosing Folder's name, else the styleUrl; a bearing comes from ExtendedData
 * heading/bearing/direction or IconStyle/heading and makes the POI ONE_WAY (BOTH with dirtype 2
 * or "both" in the description); the speed from ExtendedData speed or the name/description text.
 * Called by `PoiRepository` on Dispatchers.IO. The whole document is parsed with DOM, so this is
 * for hand-picked files, not country-sized ones. Bearings degrees true.
 */
class KmlImporter(private val defaultUnit: SpeedUnit = SpeedUnit.MPH) {
    /** Every Placemark with a Point; a bad coordinate is a row error, not a failure. */
    fun parse(input: InputStream): ImportResult = collectImport {
        val doc = secureFactory().newDocumentBuilder().parse(input)
        for ((i, pm) in doc.getElementsByTagName("Placemark").elements().withIndex()) {
            val coords = pm.child("Point")?.text("coordinates")
            if (coords == null) continue // lines/polygons are ignored
            val parts = coords.split(',').map { it.trim() }
            val (lat, lon) = errors.checkCoordinate(parts.getOrNull(1)?.toDoubleOrNull(), parts.getOrNull(0)?.toDoubleOrNull(), i + 1, coords) ?: continue
            val name = pm.text("name")
            val desc = pm.text("description")
            // ExtendedData by lower-case name; a repeated name keeps the last value.
            val extended = pm.getElementsByTagName("Data").elements().mapNotNull { e ->
                e.getAttribute("name").takeIf { it.isNotBlank() }?.let { it.lowercase() to (e.text("value") ?: "") }
            }.toMap()
            val bearing = (extended["heading"] ?: extended["bearing"] ?: extended["direction"] ?: pm.child("IconStyle")?.text("heading"))?.toBearingOrNull()
            val dirType = dirTypeOf(bearing, both = extended["dirtype"] == "2" || desc?.contains("both", ignoreCase = true) == true)
            val folder = (pm.parentNode as? Element)?.takeIf { it.tagName == "Folder" }?.text("name")
            val speed = extended["speed"]?.toIntOrNull()?.takeIf { it > 0 }?.let { it to null } ?: speedFromText(name) ?: speedFromText(desc)
            rows += importRow(lat, lon, name, folder ?: pm.text("styleUrl")?.removePrefix("#"), speed, defaultUnit, dirType, bearing)
        }
    }
}

/**
 * GPX waypoints (DESIGN.md §5.5): each `<wpt lat lon>` is a POI, named by `<name>`, categorised by
 * `<type>` else `<sym>`; a bearing from `<course>` (GPX 1.0) or an extension named heading or
 * bearing makes it ONE_WAY; the speed is found in the name or `<desc>` text. Called by the app's
 * `PoiRepository` on Dispatchers.IO; DOM-parsed, so for hand-picked files. Bearings degrees true.
 */
class GpxImporter(private val defaultUnit: SpeedUnit = SpeedUnit.MPH) {
    fun parse(input: InputStream): ImportResult = collectImport {
        val doc = secureFactory().newDocumentBuilder().parse(input)
        for ((i, w) in doc.getElementsByTagName("wpt").elements().withIndex()) {
            val (lat, lon) = errors.checkCoordinate(w.getAttribute("lat").toDoubleOrNull(), w.getAttribute("lon").toDoubleOrNull(), i + 1, "wpt") ?: continue
            val name = w.text("name")
            val bearing = (w.text("course") ?: w.text("heading") ?: w.text("bearing"))?.toBearingOrNull()
            val speed = speedFromText(name) ?: speedFromText(w.text("desc"))
            rows += importRow(lat, lon, name, w.text("type") ?: w.text("sym"), speed, defaultUnit, dirTypeOf(bearing, both = false), bearing)
        }
    }
}

/**
 * TomTom OV2 binary (DESIGN.md §5.6). No direction information, so every POI is ANY; the speed
 * is found in the name ("[SPECS] 50", never a road number). Read as a stream, one record at a
 * time, so a big file never sits in memory. Little-endian: a type byte, then an int32 record
 * length that includes those 5 bytes; type 2 (and 3) hold `lon×100000, lat×100000, name\0`, type 1
 * is a skipper block and type 0 a deleted record. [PgwOv2] builds on this for PGW's download.
 * Called by `PoiRepository` on Dispatchers.IO.
 */
class Ov2Importer(private val defaultUnit: SpeedUnit = SpeedUnit.MPH) {
    /** Convenience for small files and tests; the stream form below is what the app uses. */
    fun parse(bytes: ByteArray): ImportResult = parse(bytes.inputStream())

    /** Reads records until the end of the stream; a corrupt length ends the file with one row error. */
    fun parse(input: InputStream): ImportResult = collectImport {
        val inp = java.io.DataInputStream(input.buffered())
        // Null when the stream ends inside the int; any other read error still propagates.
        fun leInt(): Int? = try { ByteBuffer.wrap(ByteArray(4).also(inp::readFully)).order(ByteOrder.LITTLE_ENDIAN).int } catch (_: EOFException) { null }
        // False when the stream ends inside the record; any other read error still propagates.
        fun skip(n: Int): Boolean = try { inp.skipFully(n.toLong()); true } catch (_: EOFException) { false }
        var record = 0
        while (true) {
            val type = inp.read().takeIf { it >= 0 } ?: break
            record++
            val len = leInt() ?: break
            // A length under the 5-byte header, or over 1 MiB, means the stream is not OV2 any more: stop.
            if (len < 5 || len > MAX_RECORD) {
                errors += RowError(record, "type $type", "bad record length $len"); break
            }
            // A POI record holds two ints before its name: anything shorter is corrupt, not a crash.
            if ((type == 2 || type == 3) && len < 13) {
                errors += RowError(record, "type $type", "bad record length $len"); if (!skip(len - 5)) break; continue
            }
            when (type) {
                2, 3 -> {
                    val lon = (leInt() ?: break) / 100_000.0
                    val lat = (leInt() ?: break) / 100_000.0
                    val nameBytes = ByteArray(len - 13)
                    try { inp.readFully(nameBytes) } catch (_: EOFException) {
                        errors += RowError(record, "type $type", "truncated record"); break
                    }
                    // Type 3 is "name\0id\0extra\0": the name is the first field.
                    val name = String(nameBytes, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
                    errors.checkCoordinate(lat, lon, record, name) ?: continue
                    rows += importRow(lat, lon, name, null, speedFromText(name), defaultUnit, DirectionType.ANY, null)
                }
                else -> if (!skip(len - 5)) break // skipper (1), deleted or unknown
            }
        }
    }

    /** 1 MiB: far above any real record, a guard against reading a bad length as a size. */
    private companion object { const val MAX_RECORD = 1 shl 20 }
}

/**
 * A saved CSV column mapping (DESIGN.md §5.7), as the phone wizard builds it and stores it as a
 * `CsvMappingProfile` (§4.1). Column indexes are 0-based; [dirTypeMap] maps the file's own
 * direction values (presets `0/1/2`, `any/one/both`, `A/O/B`, matched lower case) to ours.
 */
data class CsvProfile(
    val delimiter: Char = ',',
    val hasHeader: Boolean = true,
    val latCol: Int,
    val lonCol: Int,
    val nameCol: Int? = null,
    val speedCol: Int? = null,
    val bearingCol: Int? = null,
    val dirTypeCol: Int? = null,
    val categoryCol: Int? = null,
    val dirTypeMap: Map<String, DirectionType> = mapOf(
        "0" to DirectionType.ANY, "1" to DirectionType.ONE_WAY, "2" to DirectionType.BOTH,
        "any" to DirectionType.ANY, "one" to DirectionType.ONE_WAY, "both" to DirectionType.BOTH,
        "a" to DirectionType.ANY, "o" to DirectionType.ONE_WAY, "b" to DirectionType.BOTH,
    ),
    val speedUnit: SpeedUnit = SpeedUnit.MPH,
)

/**
 * Generic delimited text through a [CsvProfile] (DESIGN.md §5.7). With a bearing column and no
 * direction-type column the POI is ONE_WAY; with neither it is ANY; a direction type without a
 * bearing is still ANY (a direction needs a bearing to be judged, §11 step 3). Called by the app's
 * `PoiRepository` on Dispatchers.IO, line by line. Bearings degrees true; speeds in the profile's unit.
 */
class CsvImporter(private val profile: CsvProfile) {
    /** Every data line; the header (if the profile says there is one) and blank lines are skipped. */
    fun parse(reader: BufferedReader): ImportResult = collectImport {
        reader.lineSequence().forEachIndexed { idx, raw ->
            if (idx == 0 && profile.hasHeader) return@forEachIndexed
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEachIndexed
            val f = splitCsv(line, profile.delimiter)
            val (lat, lon) = errors.checkCoordinate(f.getOrNull(profile.latCol)?.trim()?.toDoubleOrNull(), f.getOrNull(profile.lonCol)?.trim()?.toDoubleOrNull(), idx + 1, line)
                ?: return@forEachIndexed
            val bearing = profile.bearingCol?.let { f.getOrNull(it)?.trim()?.toBearingOrNull() }
            // The file's own direction type counts only with a bearing to judge by; without one the row is ANY.
            val mapped = profile.dirTypeCol?.let { f.getOrNull(it)?.trim()?.lowercase() }?.let { profile.dirTypeMap[it] }
            val dirType = mapped?.takeIf { bearing != null } ?: dirTypeOf(bearing, both = false)
            val speed = profile.speedCol?.let { f.getOrNull(it)?.trim()?.toIntOrNull() }?.takeIf { it > 0 }
            rows += importRow(
                lat, lon, profile.nameCol?.let { f.getOrNull(it)?.trim() }, profile.categoryCol?.let { f.getOrNull(it)?.trim() },
                speed?.let { it to null }, profile.speedUnit, dirType, bearing,
            )
        }
    }

    companion object {
        /** The delimiter among `, ; tab |` that appears most in the first 5 lines (wizard step 2). */
        fun detectDelimiter(sample: String): Char =
            listOf(',', ';', '\t', '|').maxBy { d -> sample.lineSequence().take(5).sumOf { l -> l.count { it == d } } }

        /** Minimal RFC-4180 split: quotes, doubled quotes inside quotes. */
        fun splitCsv(line: String, delimiter: Char): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == delimiter && !quoted -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(c)
                }
                i++
            }
            out += sb.toString()
            return out
        }
    }
}

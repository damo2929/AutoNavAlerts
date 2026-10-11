// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ImportResult
import io.autonav.alerts.core.poi.ImportRow
import io.autonav.alerts.core.poi.RowError
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.core.poi.isPlausibleCoordinate
import org.w3c.dom.Element
import java.io.BufferedReader
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

enum class PoiFormat { IGO_TXT, KML, GPX, OV2, CSV }

/** Guesses a format from the file name and first bytes (DESIGN.md §5). */
object FormatSniffer {
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

private val SPEED_IN_TEXT = Regex("""(?<!\d)(\d{2,3})\s*(mph|km/?h|kph)?""", RegexOption.IGNORE_CASE)

private fun speedFromText(text: String?): Pair<Int, SpeedUnit?>? {
    if (text.isNullOrBlank()) return null
    val m = SPEED_IN_TEXT.find(text) ?: return null
    val v = m.groupValues[1].toInt().takeIf { it in 5..200 } ?: return null
    val unit = when (m.groupValues[2].lowercase()) {
        "mph" -> SpeedUnit.MPH
        "" -> null
        else -> SpeedUnit.KMH
    }
    return v to unit
}

private fun secureFactory() = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = false
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    isExpandEntityReferences = false
}

private fun Element.child(tag: String): Element? {
    val nodes = getElementsByTagName(tag)
    return if (nodes.length > 0) nodes.item(0) as Element else null
}

private fun Element.childText(tag: String): String? = child(tag)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

/** KML Placemarks with Points (DESIGN.md §5.4). */
class KmlImporter(private val defaultUnit: SpeedUnit = SpeedUnit.MPH) {
    fun parse(input: InputStream): ImportResult {
        val doc = secureFactory().newDocumentBuilder().parse(input)
        val placemarks = doc.getElementsByTagName("Placemark")
        val rows = ArrayList<ImportRow>()
        val errors = ArrayList<RowError>()
        for (i in 0 until placemarks.length) {
            val pm = placemarks.item(i) as Element
            val coords = pm.child("Point")?.childText("coordinates")
            if (coords == null) continue // lines/polygons are ignored
            val parts = coords.split(',').map { it.trim() }
            val lon = parts.getOrNull(0)?.toDoubleOrNull()
            val lat = parts.getOrNull(1)?.toDoubleOrNull()
            if (lat == null || lon == null || !isPlausibleCoordinate(lat, lon)) {
                errors += RowError(i + 1, coords, "bad coordinates"); continue
            }
            val name = pm.childText("name")
            val desc = pm.childText("description")
            val extended = HashMap<String, String>()
            val data = pm.getElementsByTagName("Data")
            for (j in 0 until data.length) {
                val e = data.item(j) as Element
                e.getAttribute("name").takeIf { it.isNotBlank() }?.let { k -> extended[k.lowercase()] = e.childText("value") ?: "" }
            }
            val bearing = (extended["heading"] ?: extended["bearing"] ?: extended["direction"] ?: pm.child("IconStyle")?.childText("heading"))
                ?.toFloatOrNull()?.let { ((it % 360f) + 360f) % 360f }
            val dirType = when {
                bearing == null -> DirectionType.ANY
                extended["dirtype"] == "2" || desc?.contains("both", ignoreCase = true) == true -> DirectionType.BOTH
                else -> DirectionType.ONE_WAY
            }
            val folder = (pm.parentNode as? Element)?.takeIf { it.tagName == "Folder" }?.childText("name")
            val speed = extended["speed"]?.toIntOrNull()?.let { it to null } ?: speedFromText(name) ?: speedFromText(desc)
            rows += ImportRow(
                lat = lat, lon = lon, name = name,
                categoryCode = folder ?: pm.childText("styleUrl")?.removePrefix("#"),
                speed = speed?.first, speedUnit = speed?.let { it.second ?: defaultUnit },
                dirType = dirType, bearing = if (dirType == DirectionType.ANY) null else bearing,
            )
        }
        return ImportResult(rows, errors, 0)
    }
}

/** GPX waypoints (DESIGN.md §5.5). */
class GpxImporter(private val defaultUnit: SpeedUnit = SpeedUnit.MPH) {
    fun parse(input: InputStream): ImportResult {
        val doc = secureFactory().newDocumentBuilder().parse(input)
        val wpts = doc.getElementsByTagName("wpt")
        val rows = ArrayList<ImportRow>()
        val errors = ArrayList<RowError>()
        for (i in 0 until wpts.length) {
            val w = wpts.item(i) as Element
            val lat = w.getAttribute("lat").toDoubleOrNull()
            val lon = w.getAttribute("lon").toDoubleOrNull()
            if (lat == null || lon == null || !isPlausibleCoordinate(lat, lon)) {
                errors += RowError(i + 1, "wpt", "bad coordinates"); continue
            }
            val name = w.childText("name")
            val bearing = (w.childText("course") ?: w.childText("heading") ?: w.childText("bearing"))
                ?.toFloatOrNull()?.let { ((it % 360f) + 360f) % 360f }
            val speed = speedFromText(name) ?: speedFromText(w.childText("desc"))
            rows += ImportRow(
                lat = lat, lon = lon, name = name,
                categoryCode = w.childText("type") ?: w.childText("sym"),
                speed = speed?.first, speedUnit = speed?.let { it.second ?: defaultUnit },
                dirType = if (bearing == null) DirectionType.ANY else DirectionType.ONE_WAY,
                bearing = bearing,
            )
        }
        return ImportResult(rows, errors, 0)
    }
}

/** TomTom OV2 binary (DESIGN.md §5.6). No direction information. */
class Ov2Importer(private val defaultUnit: SpeedUnit = SpeedUnit.MPH) {
    fun parse(bytes: ByteArray): ImportResult {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rows = ArrayList<ImportRow>()
        val errors = ArrayList<RowError>()
        var record = 0
        while (buf.remaining() >= 1) {
            record++
            val type = buf.get().toInt() and 0xFF
            if (buf.remaining() < 4) break
            val len = buf.int
            if (len < 5 || len - 5 > buf.remaining()) {
                errors += RowError(record, "type $type", "bad record length $len"); break
            }
            when (type) {
                2, 3 -> {
                    val lon = buf.int / 100_000.0
                    val lat = buf.int / 100_000.0
                    val nameBytes = ByteArray(len - 13).also { buf.get(it) }
                    val name = String(nameBytes, Charsets.ISO_8859_1).trimEnd('\u0000').trim()
                    if (!isPlausibleCoordinate(lat, lon)) {
                        errors += RowError(record, name, "bad coordinates"); continue
                    }
                    val speed = speedFromText(name)
                    rows += ImportRow(lat = lat, lon = lon, name = name, speed = speed?.first,
                        speedUnit = speed?.let { it.second ?: defaultUnit })
                }
                1 -> buf.position(buf.position() + 16) // skipper: len covers 21 bytes total
                else -> buf.position(buf.position() + (len - 5)) // deleted or unknown
            }
        }
        return ImportResult(rows, errors, 0)
    }
}

/** A saved CSV column mapping (DESIGN.md §5.7). Column indexes are 0-based. */
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

class CsvImporter(private val profile: CsvProfile) {
    fun parse(reader: BufferedReader): ImportResult {
        val rows = ArrayList<ImportRow>()
        val errors = ArrayList<RowError>()
        reader.lineSequence().forEachIndexed { idx, raw ->
            if (idx == 0 && profile.hasHeader) return@forEachIndexed
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEachIndexed
            val f = splitCsv(line, profile.delimiter)
            val lat = f.getOrNull(profile.latCol)?.trim()?.toDoubleOrNull()
            val lon = f.getOrNull(profile.lonCol)?.trim()?.toDoubleOrNull()
            if (lat == null || lon == null || !isPlausibleCoordinate(lat, lon)) {
                errors += RowError(idx + 1, line, "bad coordinates"); return@forEachIndexed
            }
            val bearing = profile.bearingCol?.let { f.getOrNull(it)?.trim()?.toFloatOrNull() }?.let { ((it % 360f) + 360f) % 360f }
            val dirType = profile.dirTypeCol?.let { f.getOrNull(it)?.trim()?.lowercase() }?.let { profile.dirTypeMap[it] }
                ?: if (bearing != null) DirectionType.ONE_WAY else DirectionType.ANY
            val speed = profile.speedCol?.let { f.getOrNull(it)?.trim()?.toIntOrNull() }?.takeIf { it > 0 }
            rows += ImportRow(
                lat = lat, lon = lon,
                name = profile.nameCol?.let { f.getOrNull(it)?.trim() },
                categoryCode = profile.categoryCol?.let { f.getOrNull(it)?.trim() },
                speed = speed, speedUnit = speed?.let { profile.speedUnit },
                dirType = if (bearing == null) DirectionType.ANY else dirType,
                bearing = if (dirType == DirectionType.ANY) null else bearing,
            )
        }
        return ImportResult(rows, errors, 0)
    }

    companion object {
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

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ImportResult
import io.autonav.alerts.core.poi.ImportRow
import io.autonav.alerts.core.poi.RowError
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.core.poi.isPlausibleCoordinate
import java.io.BufferedReader

/**
 * iGO speed-camera TXT: `X,Y,Type,Speed,DirType,Direction`.
 * Behaviour checked against PocketGPSWorld v24.101 (DESIGN.md §5.2):
 *  - header matched case-insensitively; LF or CRLF
 *  - last row is a copyright/watermark row with a non-numeric Type → skipped silently
 *  - empty DirType/Direction → ANY; Direction 360 → 0; empty or 0 Speed → null
 */
class IgoTxtImporter(private val speedUnit: SpeedUnit = SpeedUnit.MPH) {

    fun sniff(head: String): Boolean =
        head.lineSequence().firstOrNull()?.trim()?.replace(" ", "")
            ?.equals("X,Y,Type,Speed,DirType,Direction", ignoreCase = true) == true

    fun parse(reader: BufferedReader): ImportResult {
        val rows = ArrayList<ImportRow>()
        val errors = ArrayList<RowError>()
        var silent = 0
        var lineNo = 0
        var headerSeen = false

        reader.lineSequence().forEach { raw ->
            lineNo++
            val line = raw.trimEnd('\r').trim()
            if (line.isEmpty() || line.startsWith(";")) return@forEach
            if (!headerSeen && sniff(line)) {
                headerSeen = true
                return@forEach
            }
            val f = line.split(',')
            if (f.size < 3) {
                errors += RowError(lineNo, line, "expected 6 fields, got ${f.size}")
                return@forEach
            }
            val type = f[2].trim()
            if (type.toIntOrNull() == null) {
                // PGW watermark row, e.g. "0.72185,51.51508,Copyright PocketGPSWorld.com,,"
                silent++
                return@forEach
            }
            val lon = f[0].trim().toDoubleOrNull()
            val lat = f[1].trim().toDoubleOrNull()
            if (lat == null || lon == null || !isPlausibleCoordinate(lat, lon)) {
                errors += RowError(lineNo, line, "bad coordinates")
                return@forEach
            }
            val speed = f.getOrNull(3)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val dirTypeCode = f.getOrNull(4)?.trim()?.toIntOrNull()
            val direction = f.getOrNull(5)?.trim()?.toFloatOrNull()?.let { if (it >= 360f) it - 360f else it }
                ?.takeIf { it >= 0f && it < 360f }

            val dirType = when {
                direction == null -> DirectionType.ANY
                dirTypeCode == 1 -> DirectionType.ONE_WAY
                dirTypeCode == 2 -> DirectionType.BOTH
                else -> DirectionType.ANY
            }
            rows += ImportRow(
                lat = lat,
                lon = lon,
                categoryCode = type,
                speed = speed,
                speedUnit = speed?.let { speedUnit },
                dirType = dirType,
                bearing = if (dirType == DirectionType.ANY) null else direction,
            )
        }
        return ImportResult(rows, errors, silent)
    }

    companion object {
        /** PGW iGO type codes found in v24.101 (DESIGN.md §5.2). Code 11 is a best guess, still to confirm. */
        val PGW_CATEGORIES: Map<String, String> = mapOf(
            "1" to "Fixed camera",
            "3" to "Red light camera",
            "4" to "Average speed camera",
            "5" to "Mobile camera site",
            "11" to "Red light + speed camera",
        )

        /** Type codes whose points form average-speed chains without start/end markers. */
        val PGW_AVERAGE_SPEED_CODES = setOf("4")

        fun categoryName(code: String): String = PGW_CATEGORIES[code] ?: "Type $code"
    }
}

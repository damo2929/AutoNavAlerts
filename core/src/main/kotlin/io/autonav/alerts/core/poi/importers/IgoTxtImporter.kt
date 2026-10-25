// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ImportResult
import io.autonav.alerts.core.poi.RowError
import io.autonav.alerts.core.poi.SpeedUnit
import java.io.BufferedReader

/**
 * iGO speed-camera TXT: `X,Y,Type,Speed,DirType,Direction`.
 * Behaviour checked against PocketGPSWorld v24.101 (DESIGN.md §5.2):
 *  - header matched case-insensitively; LF or CRLF
 *  - last row is a copyright/watermark row with a non-numeric Type → skipped silently
 *  - empty DirType/Direction → ANY; Direction 360 → 0; empty or 0 Speed → null
 *
 * Direction is the heading of the traffic the camera enforces (PGW's own form calls it "Direction
 * of Travel"), which is exactly our `bearing` (§4.1): no inversion. A row with a Direction is
 * ONE_WAY or BOTH, never ANY, so the direction rule (§11 step 3) can apply to it. No Android, no
 * threads of its own: `PoiRepository`'s import pipeline calls [parse] on IO, one file
 * per set (hard rule: files are never merged). Speeds are in [speedUnit] (mph for UK packages).
 * The fixtures in the tests are made up; no PGW data is in the repo.
 */
class IgoTxtImporter(private val speedUnit: SpeedUnit = SpeedUnit.MPH) {

    /** Whether the first line is the iGO header (spaces and case ignored). */
    fun sniff(head: String): Boolean =
        head.lineSequence().firstOrNull()?.trim()?.replace(" ", "")
            ?.equals("X,Y,Type,Speed,DirType,Direction", ignoreCase = true) == true

    /** Reads the whole file line by line; never throws on a bad row, which goes to the errors instead. */
    fun parse(reader: BufferedReader): ImportResult = collectImport {
        var lineNo = 0
        var headerSeen = false

        reader.lineSequence().forEach { raw ->
            lineNo++
            val line = raw.trimEnd('\r').trim()
            // Blank lines and ";" comments are not rows.
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
                silentlySkipped++
                return@forEach
            }
            // X is longitude and Y latitude (§5.2 column table).
            val (lat, lon) = errors.checkCoordinate(f[1].trim().toDoubleOrNull(), f[0].trim().toDoubleOrNull(), lineNo, line) ?: return@forEach
            val speed = f.getOrNull(3)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val dirTypeCode = f.getOrNull(4)?.trim()?.toIntOrNull()
            val direction = f.getOrNull(5)?.trim()?.toFloatOrNull()?.let { if (it >= 360f) it - 360f else it }
                ?.takeIf { it >= 0f && it < 360f }

            // DirType 1 → ONE_WAY, 2 → BOTH; empty or 0 → ANY; and no Direction forces ANY whatever DirType says.
            val dirType = if (dirTypeCode == 1 || dirTypeCode == 2) dirTypeOf(direction, both = dirTypeCode == 2) else DirectionType.ANY
            rows += importRow(lat, lon, null, type, speed?.let { it to null }, speedUnit, dirType, direction)
        }
    }

    companion object {
        /**
         * PGW iGO type codes in v24.101 (DESIGN.md §5.2). Code 11 appears only in the fixed-camera
         * (gatso) file of the consolidated-by-type package, so it's a fixed-camera subtype.
         */
        val PGW_CATEGORIES: Map<String, String> = mapOf(
            "1" to "Fixed camera",
            "3" to "Red light camera",
            "4" to "Average speed camera",
            "5" to "Mobile camera site",
            "11" to "Fixed camera (type 11)",
        )

        /** Type codes whose points form average-speed chains without start/end markers. */
        val PGW_AVERAGE_SPEED_CODES = setOf("4")

        fun categoryName(code: String): String = PGW_CATEGORIES[code] ?: "Type $code"
    }
}

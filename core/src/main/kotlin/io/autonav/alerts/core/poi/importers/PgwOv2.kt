// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ImportResult
import io.autonav.alerts.core.poi.ImportRow
import io.autonav.alerts.core.poi.SpeedUnit

/**
 * PocketGPSWorld's TomTom OV2 download (DESIGN.md §5.2a). PGW encodes everything in each entry's
 * name, e.g. `E-R-MONITRON:3209@30`:
 *
 *  - `E`        direction of travel as a compass point (N NE E SE S SW W NW); may be missing
 *  - `R-`       reversible = both directions (matches iGO DirType 2 for 99.9% of cameras)
 *  - `t` prefix temporary camera (roadworks), e.g. `tSPECS`
 *  - `MONITRON` camera type: GATSO MONITRON TRUVELO REDSPEED REDLIGHT SPECS MOBILE pMOBILE
 *  - `3209`     PGW camera id (used as idCam when reporting a known camera)
 *  - `@30`      speed limit in mph; `@0` = variable; missing = to be confirmed
 *
 * Verified against v24.101: the compass point is within 22.5° of iGO's exact heading for 98%.
 */
object PgwOv2 {
    data class Parsed(
        val bearing: Float?,
        val directionType: DirectionType,
        val type: String,
        val temporary: Boolean,
        val pgwId: String,
        /** null when variable (`@0`) or not yet known. */
        val speed: Int?,
        val variable: Boolean,
    )

    private val NAME = Regex("""^(?:(N|NE|E|SE|S|SW|W|NW)-)?(R-)?(t(?=[A-Zp]))?(p?[A-Z]+):(\d+)(?:@(\d+))?$""")
    private val COMPASS = mapOf("N" to 0f, "NE" to 45f, "E" to 90f, "SE" to 135f, "S" to 180f, "SW" to 225f, "W" to 270f, "NW" to 315f)

    fun parseName(name: String): Parsed? {
        val m = NAME.matchEntire(name.trim()) ?: return null
        val compass = m.groupValues[1].takeIf { it.isNotEmpty() }
        val reversible = m.groupValues[2].isNotEmpty()
        val bearing = compass?.let { COMPASS[it] }
        val dirType = when {
            bearing == null -> DirectionType.ANY
            reversible -> DirectionType.BOTH
            else -> DirectionType.ONE_WAY
        }
        val speedRaw = m.groupValues[6].takeIf { it.isNotEmpty() }?.toInt()
        return Parsed(
            bearing = bearing, directionType = dirType, type = m.groupValues[4],
            temporary = m.groupValues[3].isNotEmpty(), pgwId = m.groupValues[5],
            speed = speedRaw?.takeIf { it > 0 }, variable = speedRaw == 0,
        )
    }

    /** Category code stored for a camera: type, with a `t` prefix for temporary ones (e.g. "tSPECS"). */
    fun categoryCode(p: Parsed) = (if (p.temporary) "t" else "") + p.type

    fun categoryName(code: String): String {
        val temp = code.startsWith("t") && code.length > 1 && code[1].isUpperCase()
        val base = when (code.removePrefix(if (temp) "t" else "")) {
            "GATSO" -> "Gatso"
            "MONITRON" -> "Monitron"
            "TRUVELO" -> "Truvelo"
            "REDSPEED" -> "RedSpeed (red light + speed)"
            "REDLIGHT" -> "Red light camera"
            "SPECS" -> "Average speed (SPECS)"
            "MOBILE" -> "Mobile camera site"
            "pMOBILE" -> "Pending mobile site"
            else -> code.removePrefix(if (temp) "t" else "").lowercase().replaceFirstChar { it.uppercase() }
        }
        return if (temp) "Temporary $base (roadworks)" else base
    }

    /** Category codes that are average-speed zone points. */
    fun isAverageSpeed(code: String) = code.removePrefix("t") == "SPECS"

    /** Parses one PGW OV2 file. Entries that don't match the naming scheme (the copyright watermark) are skipped silently. */
    fun parse(bytes: ByteArray): ImportResult {
        val raw = Ov2Importer().parse(bytes)
        var skipped = 0
        val rows = raw.rows.mapNotNull { r ->
            val p = r.name?.let(::parseName)
            if (p == null) { skipped++; return@mapNotNull null }
            ImportRow(
                lat = r.lat, lon = r.lon, name = r.name, categoryCode = categoryCode(p),
                speed = p.speed, speedUnit = p.speed?.let { SpeedUnit.MPH },
                dirType = p.directionType, bearing = if (p.directionType == DirectionType.ANY) null else p.bearing,
                extra = buildMap {
                    put("pgwId", p.pgwId)
                    if (p.variable) put("variableLimit", "true")
                },
            )
        }
        return ImportResult(rows, raw.errors, raw.silentlySkipped + skipped)
    }

    /** Groups PGW OV2 files by family: `pocketgps_uk_gatso_30.ov2` → "gatso"; `pocketgps_uk_redlight.ov2` → "redlight". */
    fun families(entries: Map<String, ByteArray>): LinkedHashMap<String, List<ByteArray>> {
        val rx = Regex("""^pocketgps_[a-z]+_([a-z]+)(?:_[a-z0-9]+)?\.ov2$""", RegexOption.IGNORE_CASE)
        val out = LinkedHashMap<String, MutableList<ByteArray>>()
        for ((name, bytes) in entries) {
            val fam = rx.matchEntire(name)?.groupValues?.get(1)?.lowercase() ?: continue
            out.getOrPut(fam) { ArrayList() } += bytes
        }
        @Suppress("UNCHECKED_CAST")
        return out as LinkedHashMap<String, List<ByteArray>>
    }
}

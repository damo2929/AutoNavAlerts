// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ImportResult
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
 *
 * The compass point is the direction of travel the camera enforces, our `bearing` (§4.1), so the
 * direction rule (§11 step 3) applies to these cameras with a 22.5° quantisation that the rule's
 * 45° error budget allows for. A missing compass point is the only way a camera becomes ANY.
 * Stateless; the app's `PoiRepository` calls it on IO, one file per set (hard rule: never merged),
 * and the phone's Cameras tab uses the labels. Speeds are mph. Only made-up fixtures are in the
 * tests; no PGW data enters the repo.
 */
object PgwOv2 {
    /** What one entry's name says. [bearing] degrees true from the compass point; [type] the camera make or kind. */
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

    // Groups: 1 compass, 2 "R-", 3 "t" (only before a type word), 4 type (a "p" prefix allowed for
    // pMOBILE), 5 PGW id, 6 speed after "@".
    private val NAME = Regex("""^(?:(N|NE|E|SE|S|SW|W|NW)-)?(R-)?(t(?=[A-Zp]))?(p?[A-Z]+):(\d+)(?:@(\d+))?$""")
    private val COMPASS = mapOf("N" to 0f, "NE" to 45f, "E" to 90f, "SE" to 135f, "S" to 180f, "SW" to 225f, "W" to 270f, "NW" to 315f)

    /** The entry's fields, or null when the name isn't in PGW's scheme (the copyright watermark entry). */
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

    /** The category's display name for a [categoryCode]; unknown type words are title-cased. */
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
            importRow(
                r.lat, r.lon, r.name, categoryCode(p), p.speed?.let { it to null }, SpeedUnit.MPH, p.directionType, p.bearing,
                extra = buildMap {
                    put("pgwId", p.pgwId)
                    if (p.variable) put("variableLimit", "true")
                },
            )
        }
        return ImportResult(rows, raw.errors, raw.silentlySkipped + skipped)
    }

    /** One entry per PGW OV2 file, keyed by its stem: `pocketgps_uk_gatso_30.ov2` → "gatso_30"; `…_redlight.ov2` → "redlight". */
    // The map stays lazy (see [LazyBytes]): nothing is inflated until a set is imported, and never all at once.
    fun files(entries: Map<String, ByteArray>): Map<String, ByteArray> {
        val rx = Regex("""^pocketgps_([a-z]+)_([a-z]+(?:_[a-z0-9]+)?)\.ov2$""", RegexOption.IGNORE_CASE)
        val out = LinkedHashMap<String, () -> ByteArray>()
        for (name in entries.keys) {
            val m = rx.matchEntire(name.substringAfterLast('/')) ?: continue
            val stem = m.groupValues[2].lowercase()
            // Another region's file of the same type keeps its region, so the two never merge.
            val key = if (stem in out) "${m.groupValues[1].lowercase()}_$stem" else stem
            out[key] = { entries.getValue(name) }
        }
        return LazyBytes(out)
    }

    /**
     * The icon shipped with a file: `pocketgps_uk_<stem>_X2.bmp` (44 px, © GerryC, non-commercial use
     * with credit, see `licence_X2_icons.txt`) if present, else `pocketgps_uk_<stem>.bmp` (22 px).
     */
    fun iconFor(stem: String, entries: Map<String, ByteArray>): ByteArray? {
        val x2 = Regex("""^pocketgps_[a-z]+_${Regex.escape(stem)}_X2\.bmp$""", RegexOption.IGNORE_CASE)
        val plain = Regex("""^pocketgps_[a-z]+_${Regex.escape(stem)}\.bmp$""", RegexOption.IGNORE_CASE)
        return entries.entries.firstOrNull { x2.matches(it.key) }?.value ?: entries.entries.firstOrNull { plain.matches(it.key) }?.value
    }

    /** The icon licence text saved beside the icons and shown in Settings → About (§5.2a). */
    const val ICON_LICENCE_FILE = "licence_X2_icons.txt"
    const val ICON_CREDIT = "Icon © GerryC, 2007 (from your PocketGPSWorld download, non-commercial use)"

    /** "gatso_30" → ("gatso", "30"); "redlight" → ("redlight", null). */
    fun splitStem(stem: String): Pair<String, String?> =
        stem.substringBefore('_') to stem.substringAfter('_', "").ifEmpty { null }

    /** Human label for a file's speed part: "30 mph", "variable limit", "limit to be confirmed". */
    fun speedLabel(part: String?): String? = when (part) {
        null -> null
        "var" -> "variable limit"
        "tba" -> "limit to be confirmed"
        else -> part.toIntOrNull()?.let { "$it mph" } ?: part
    }
}

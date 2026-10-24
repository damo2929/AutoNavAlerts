// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi

/** Which travel directions a POI applies to. See DESIGN.md §4.1 for the meaning of `bearing`. */
enum class DirectionType { ANY, ONE_WAY, BOTH }

/** The unit a POI's limit is stored in (DESIGN.md §4.1 `speedUnit`): PGW UK data is mph. */
enum class SpeedUnit {
    MPH, KMH;

    /** A limit in this unit as shown in the display units, rounded (DESIGN.md §8.4 "Units"). */
    fun toDisplay(limit: Int, imperial: Boolean): Int = when {
        this == MPH && !imperial -> Math.round(limit * KMH_PER_MPH).toInt()
        this == KMH && imperial -> Math.round(limit / KMH_PER_MPH).toInt()
        else -> limit
    }

    private companion object { const val KMH_PER_MPH = 1.609344 }
}

/** Role of a POI in an average-speed zone (DESIGN.md §11 step 8). */
enum class ZoneRole { NONE, ZONE_START, ZONE_END, ZONE_POINT }

/** One parsed row from any importer, before it is stored. */
data class ImportRow(
    /** Position, degrees; already checked with [isPlausibleCoordinate]. */
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    /** The source's own category key (iGO Type, KML folder, OV2 type word); becomes a `PoiCategory.sourceCode` (§4.1). */
    val categoryCode: String? = null,
    /** The posted limit in [speedUnit], or null when the source has none or says variable. */
    val speed: Int? = null,
    val speedUnit: SpeedUnit? = null,
    /** ANY unless the source gave a bearing; the importers never invent a direction (§4.1, §11 step 3). */
    val dirType: DirectionType = DirectionType.ANY,
    /** Direction of travel the POI applies to, degrees true in [0, 360). */
    val bearing: Float? = null,
    /** Unmapped source fields kept as text (e.g. PGW's camera id), stored as JSON on the POI. */
    val extra: Map<String, String> = emptyMap(),
)

/** Why a row was skipped. Watermark/copyright rows are skipped silently and never reported. */
data class RowError(val lineNumber: Int, val line: String, val reason: String)

/**
 * What one importer produced from one file (DESIGN.md §5.1): the good rows, the bad rows with
 * their reasons (the app reports the first 20), and the rows skipped on purpose (watermarks).
 * `PoiRepository` streams [rows] into a staging set (§4.4).
 */
data class ImportResult(
    val rows: List<ImportRow>,
    val errors: List<RowError>,
    val silentlySkipped: Int,
) {
    /** Rows that were judged: good plus bad, not the silently skipped. */
    val totalConsidered: Int get() = rows.size + errors.size

    /** DESIGN.md §5.1: fail when > 20% of rows are bad, or nothing is valid. */
    val failed: Boolean
        get() = rows.isEmpty() || (totalConsidered > 0 && errors.size.toDouble() / totalConsidered > 0.20)
}

/** Validation shared by all importers (DESIGN.md §5.1). */
// Lat −90..90, lon −180..180, never (0,0) (a blank row), never NaN.
fun isPlausibleCoordinate(lat: Double, lon: Double): Boolean =
    lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0) &&
        !lat.isNaN() && !lon.isNaN()

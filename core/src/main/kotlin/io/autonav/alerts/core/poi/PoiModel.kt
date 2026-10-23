// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi

/** Which travel directions a POI applies to. See DESIGN.md §4.1 for the meaning of `bearing`. */
enum class DirectionType { ANY, ONE_WAY, BOTH }

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
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    val categoryCode: String? = null,
    val speed: Int? = null,
    val speedUnit: SpeedUnit? = null,
    val dirType: DirectionType = DirectionType.ANY,
    /** Direction of travel the POI applies to, degrees true in [0, 360). */
    val bearing: Float? = null,
    val extra: Map<String, String> = emptyMap(),
)

/** Why a row was skipped. Watermark/copyright rows are skipped silently and never reported. */
data class RowError(val lineNumber: Int, val line: String, val reason: String)

data class ImportResult(
    val rows: List<ImportRow>,
    val errors: List<RowError>,
    val silentlySkipped: Int,
) {
    val totalConsidered: Int get() = rows.size + errors.size

    /** DESIGN.md §5.1: fail when > 20% of rows are bad, or nothing is valid. */
    val failed: Boolean
        get() = rows.isEmpty() || (totalConsidered > 0 && errors.size.toDouble() / totalConsidered > 0.20)
}

/** Validation shared by all importers (DESIGN.md §5.1). */
fun isPlausibleCoordinate(lat: Double, lon: Double): Boolean =
    lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0) &&
        !lat.isNaN() && !lon.isNaN()

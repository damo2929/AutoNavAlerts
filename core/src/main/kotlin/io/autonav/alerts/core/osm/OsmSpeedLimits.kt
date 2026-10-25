// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.geo.Geo
import kotlin.math.cos
import kotlin.math.floor

/**
 * OpenStreetMap `maxspeed` reading, tiles and the matching tolerances (DESIGN.md §8.4a). Plain
 * Kotlin, stateless. The tile keys here are the ones the limit file is indexed by ([tileKey],
 * [within]: used by `SpeedLimitRepository` to decide what to load); [MATCH_M] and [MATCH_DEG] are
 * what [LimitIndex] matches a fix with (the tests' reference matcher over segments lives in
 * `OsmTestSupport`); [display] converts for the panel (`NavSession`). Degrees in; mph out unless stated.
 */
object OsmSpeedLimits {
    /** Tile size in degrees, both ways. */
    // 0.1° tiles (§8.4a): about 11 km by 7 km in the UK; one, up to four near a corner, covers the car.
    const val TILE_DEG = 0.1
    /** A segment further than this from the fix isn't our road. */
    // The nearest piece within 20 m running within 30° of the heading, either way (§8.4a "Finding the limit").
    const val MATCH_M = 20.0
    /** A segment more than this off our heading is a side road or crossing. */
    const val MATCH_DEG = 30.0

    // 0.621371, not 1 / Geo.KMH_PER_MPH: the tag reader's results are pinned to this rounded factor.
    private const val KMH_TO_MPH = 0.621371

    /** mph from one `maxspeed` value, or null if it isn't a usable limit. */
    // UK national speed limit tags: single carriageway 60, dual carriageway and motorway 70.
    // Anything outside 5–90 mph isn't a road limit.
    fun mph(value: String?, highway: String? = null): Double? {
        val v = value?.split(';')?.first()?.trim()?.lowercase() ?: return null
        return when {
            v.endsWith("mph") -> v.removeSuffix("mph").trim().toDoubleOrNull()
            v == "gb:nsl_single" -> 60.0
            v == "gb:nsl_dual" || v == "gb:motorway" -> 70.0
            v == "national" && highway?.startsWith("motorway") == true -> 70.0
            else -> v.toDoubleOrNull()?.let { it * KMH_TO_MPH }
        }?.takeIf { it in 5.0..90.0 }
    }

    // --- Tiles ------------------------------------------------------------------------------

    /** The key of the tile containing the point: row in the high 32 bits, column in the low. */
    fun tileKey(lat: Double, lon: Double): Long = tileKey(floor(lat / TILE_DEG).toInt(), floor(lon / TILE_DEG).toInt())

    private fun tileKey(row: Int, col: Int): Long = (row.toLong() shl 32) or (col.toLong() and 0xffffffffL)

    /** The tile at (lat, lon) and its 8 neighbours. */
    fun around(lat: Double, lon: Double): List<Long> {
        val r = floor(lat / TILE_DEG).toInt()
        val c = floor(lon / TILE_DEG).toInt()
        return (-1..1).flatMap { dr -> (-1..1).map { dc -> tileKey(r + dr, c + dc) } }
    }

    /** Every tile whose centre is within [km] of (lat, lon), nearest first. */
    fun within(lat: Double, lon: Double, km: Double): List<Long> {
        val dLat = km / 111.0
        val dLon = km / (111.0 * cos(Math.toRadians(lat)))
        val out = ArrayList<Pair<Double, Long>>()
        var r = floor((lat - dLat) / TILE_DEG).toInt()
        while (r * TILE_DEG <= lat + dLat) {
            var c = floor((lon - dLon) / TILE_DEG).toInt()
            while (c * TILE_DEG <= lon + dLon) {
                val d = Geo.distanceM(lat, lon, (r + 0.5) * TILE_DEG, (c + 0.5) * TILE_DEG)
                if (d <= km * 1000) out += d to tileKey(r, c)
                c++
            }
            r++
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** The limit in the panel's units, whole. */
    // OSM limits are mph in the file; a metric panel shows them converted (§8.4 "Units").
    fun display(mph: Double, imperial: Boolean): Int = Math.round(if (imperial) mph else mph / KMH_TO_MPH).toInt()
}

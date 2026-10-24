// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.geo.Geo
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * One straight piece of a road with a posted limit (DESIGN.md §8.4a). Degrees and mph. The
 * [dir] field exists for tag input with `maxspeed:forward/backward`; the Geofabrik extract has no
 * such split, so everything from the limit file is [BOTH].
 */
data class LimitSegment(
    val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double,
    val mph: Double,
    /** [BOTH], or only along ([FORWARD]) / against ([BACKWARD]) the way's drawing direction. */
    val dir: Int,
) {
    companion object {
        const val BOTH = 0
        const val FORWARD = 1
        const val BACKWARD = 2
    }
}

/**
 * OpenStreetMap `maxspeed` reading, tiles and road matching (DESIGN.md §8.4a). Plain Kotlin,
 * stateless. The tile keys here are the ones the limit file is indexed by ([tileKey], [within]:
 * used by `SpeedLimitRepository` to decide what to load); [limitAt] over [LimitSegment]s is the
 * reference matcher that [LimitIndex] reproduces without allocating, and tests use it;
 * [display] converts for the panel (`NavSession`). Degrees in; mph out unless stated.
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

    /**
     * The limits a way's tags give: (both ways, forward only, backward only). `maxspeed:forward`
     * and `:backward` override `maxspeed` for their direction.
     */
    fun limits(tags: Map<String, String>): Triple<Double?, Double?, Double?> {
        val hw = tags["highway"]
        val both = mph(tags["maxspeed"], hw)
        val fwd = mph(tags["maxspeed:forward"], hw)
        val back = mph(tags["maxspeed:backward"], hw)
        return if (fwd == null && back == null) Triple(both, null, null) else Triple(null, fwd ?: both, back ?: both)
    }

    /** Segments for one way's points (lat, lon pairs in drawing order). */
    fun segments(points: List<Pair<Double, Double>>, tags: Map<String, String>): List<LimitSegment> {
        val (both, fwd, back) = limits(tags)
        val out = ArrayList<LimitSegment>()
        for ((a, b) in points.zipWithNext()) {
            both?.let { out += LimitSegment(a.first, a.second, b.first, b.second, it, LimitSegment.BOTH) }
            fwd?.let { out += LimitSegment(a.first, a.second, b.first, b.second, it, LimitSegment.FORWARD) }
            back?.let { out += LimitSegment(a.first, a.second, b.first, b.second, it, LimitSegment.BACKWARD) }
        }
        return out
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

    // --- Matching ---------------------------------------------------------------------------

    /**
     * The limit of the road at (lat, lon) heading [headingDeg]: the nearest segment within
     * [MATCH_M] that runs within [MATCH_DEG] of the heading (either way for a two-way limit,
     * its own direction for a one-way one). Null if none.
     */
    fun limitAt(segments: Collection<LimitSegment>, lat: Double, lon: Double, headingDeg: Double?): Double? {
        val h = headingDeg ?: return null
        var best: LimitSegment? = null
        var bestD = Double.MAX_VALUE
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 110_540.0
        for (s in segments) {
            val d = distanceToSegmentM(lat, lon, s, kx, ky)
            if (d > MATCH_M || d >= bestD) continue
            val b = Geo.bearingDeg(s.lat1, s.lon1, s.lat2, s.lon2)
            val along = Geo.absAngleDiffDeg(b, h) <= MATCH_DEG
            val against = Geo.absAngleDiffDeg(b + 180.0, h) <= MATCH_DEG
            val ok = when (s.dir) {
                LimitSegment.FORWARD -> along
                LimitSegment.BACKWARD -> against
                else -> along || against
            }
            if (ok) { best = s; bestD = d }
        }
        return best?.mph
    }

    private fun distanceToSegmentM(lat: Double, lon: Double, s: LimitSegment, kx: Double, ky: Double): Double {
        // Flat projection around the fix: fine at road-segment scale.
        val ax = (s.lon1 - lon) * kx
        val ay = (s.lat1 - lat) * ky
        val bx = (s.lon2 - lon) * kx
        val by = (s.lat2 - lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    /** The limit in the panel's units, whole. */
    // OSM limits are mph in the file; a metric panel shows them converted (§8.4 "Units").
    fun display(mph: Double, imperial: Boolean): Int = Math.round(if (imperial) mph else mph / KMH_TO_MPH).toInt()
}

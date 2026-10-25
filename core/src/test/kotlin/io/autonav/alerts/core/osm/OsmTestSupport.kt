// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.geo.Geo

/*
 * Test-only reference matcher over plain segments (DESIGN.md §8.4a, §15.1): what `LimitIndex`
 * reproduces without allocating. Nothing in the app uses these; they exist so the tests can state
 * a road's pieces directly and compare the index against a readable matcher. Degrees and mph.
 */

/**
 * One straight piece of a road with a posted limit. The [dir] field exists for tag input with
 * `maxspeed:forward/backward`; the Geofabrik extract has no such split, so everything from the
 * limit file is [BOTH].
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
 * The limits a way's tags give: (both ways, forward only, backward only). `maxspeed:forward`
 * and `:backward` override `maxspeed` for their direction.
 */
fun OsmSpeedLimits.limits(tags: Map<String, String>): Triple<Double?, Double?, Double?> {
    val hw = tags["highway"]
    val both = mph(tags["maxspeed"], hw)
    val fwd = mph(tags["maxspeed:forward"], hw)
    val back = mph(tags["maxspeed:backward"], hw)
    return if (fwd == null && back == null) Triple(both, null, null) else Triple(null, fwd ?: both, back ?: both)
}

/** Segments for one way's points (lat, lon pairs in drawing order). */
fun OsmSpeedLimits.segments(points: List<Pair<Double, Double>>, tags: Map<String, String>): List<LimitSegment> {
    val (both, fwd, back) = limits(tags)
    val out = ArrayList<LimitSegment>()
    for ((a, b) in points.zipWithNext()) {
        both?.let { out += LimitSegment(a.first, a.second, b.first, b.second, it, LimitSegment.BOTH) }
        fwd?.let { out += LimitSegment(a.first, a.second, b.first, b.second, it, LimitSegment.FORWARD) }
        back?.let { out += LimitSegment(a.first, a.second, b.first, b.second, it, LimitSegment.BACKWARD) }
    }
    return out
}

/**
 * The limit of the road at (lat, lon) heading [headingDeg]: the nearest segment within
 * [OsmSpeedLimits.MATCH_M] that runs within [OsmSpeedLimits.MATCH_DEG] of the heading (either way
 * for a two-way limit, its own direction for a one-way one). Null if none.
 */
fun OsmSpeedLimits.limitAt(segments: Collection<LimitSegment>, lat: Double, lon: Double, headingDeg: Double?): Double? {
    val h = headingDeg ?: return null
    var best: LimitSegment? = null
    var bestD = Double.MAX_VALUE
    val kx = Geo.metresPerDegLon(lat)
    for (s in segments) {
        val d = Geo.flatDistanceToSegmentM(lat, lon, s.lat1, s.lon1, s.lat2, s.lon2, kx)
        if (d > OsmSpeedLimits.MATCH_M || d >= bestD) continue
        val b = Geo.bearingDeg(s.lat1, s.lon1, s.lat2, s.lon2)
        val along = Geo.absAngleDiffDeg(b, h) <= OsmSpeedLimits.MATCH_DEG
        val against = Geo.absAngleDiffDeg(b + 180.0, h) <= OsmSpeedLimits.MATCH_DEG
        val ok = when (s.dir) {
            LimitSegment.FORWARD -> along
            LimitSegment.BACKWARD -> against
            else -> along || against
        }
        if (ok) { best = s; bestD = d }
    }
    return best?.mph
}

/** The road pieces of one tile, as segments (empty if the tile has none). */
fun OsmLimitFile.Reader.segments(tile: Long): List<LimitSegment> {
    val out = ArrayList<LimitSegment>()
    forEachSegment(tile) { a, b, c, d, mph -> out += LimitSegment(a / 1e6, b / 1e6, c / 1e6, d / 1e6, mph.toDouble(), LimitSegment.BOTH) }
    return out
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.alerts.RouteMatcher
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.geo.LatLngLike
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Free drive's "route" (DESIGN.md §11 step 4): the road ahead of the car from
 * [LimitLookahead.roadAhead], first point at the car. A POI's distance is its cross-track
 * distance from that road; a POI further from the car than the road was followed gets null
 * ("no route"), so the engine keeps its straight corridor there.
 */
class RoadAheadMatcher(private val road: List<LatLngLike>) : RouteMatcher {
    private val lengthM: Double = (0 until road.size - 1).sumOf { Geo.distanceM(road[it].lat, road[it].lon, road[it + 1].lat, road[it + 1].lon) }

    override fun distanceToRouteM(lat: Double, lon: Double): Double? {
        if (road.size < 2) return null
        if (Geo.distanceM(road[0].lat, road[0].lon, lat, lon) > lengthM) return null
        var best = Double.MAX_VALUE
        for (i in 0 until road.size - 1) best = minOf(best, segmentDistanceM(lat, lon, road[i], road[i + 1]))
        return best
    }

    private fun segmentDistanceM(lat: Double, lon: Double, a: LatLngLike, b: LatLngLike): Double {
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ax = (a.lon - lon) * kx; val ay = (a.lat - lat) * KY
        val bx = (b.lon - lon) * kx; val by = (b.lat - lat) * KY
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        return sqrt(px * px + py * py)
    }

    private companion object {
        const val KY = 110_540.0
    }
}

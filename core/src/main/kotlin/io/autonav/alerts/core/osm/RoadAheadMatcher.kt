// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.alerts.RouteMatcher
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.geo.LatLngLike

/**
 * Free drive's "route" (DESIGN.md §11 step 4): the road ahead of the car from
 * [LimitLookahead.roadAhead], first point at the car. A POI's distance is its cross-track
 * distance from that road; a POI further from the car than the road was followed gets null
 * ("no route"), so the engine keeps its straight corridor there.
 *
 * Immutable and tiny: the app's `AlertCoordinator` builds a new one on the alert thread each time
 * the road walk is recomputed (once a second, or after a 10° turn) and hands it to the engine as
 * its [RouteMatcher], in free drive and while the car is off the SDK's route. The engine checks the
 * distance against the same side tolerance as a route (50 m, 100 m for mobile sites). The
 * direction rule (§11 step 3) is untouched by this. Degrees in, metres out.
 */
class RoadAheadMatcher(private val road: List<LatLngLike>) : RouteMatcher {
    private val lengthM: Double = Geo.cumulativeM(road).lastOrNull() ?: 0.0

    override fun distanceToRouteM(lat: Double, lon: Double): Double? {
        if (road.size < 2) return null
        // Beyond the walked length the road says nothing about the POI: "no route" there (§11 step 4).
        if (Geo.distanceM(road[0].lat, road[0].lon, lat, lon) > lengthM) return null
        // Flat projection around the POI: point-to-segment distance in metres.
        val kx = Geo.metresPerDegLon(lat)
        return (0 until road.size - 1).minOf { Geo.flatDistanceToSegmentM(lat, lon, road[it].lat, road[it].lon, road[it + 1].lat, road[it + 1].lon, kx) }
    }
}

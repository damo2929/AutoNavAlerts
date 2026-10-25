// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.alerts.RouteMatcher
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.geo.LatLngLike

/**
 * The route's points, for matching cameras to the road ahead (DESIGN.md §11 step 4): the
 * cross-track distance from a point to the segments from the car's segment onward for [aheadM],
 * never to segments behind. [locate] finds the car's segment with a hint (the last one), so a
 * route that passes the same spot twice matches the pass the car is on.
 *
 * No Android, no threads of its own. The app's `GuidanceController` builds one from the SDK's
 * route polyline whenever the route changes and calls [locate] once per fix, from the car's
 * position and heading, before the engine runs (`locateCar`); the engine then measures each POI
 * through [distanceToRouteM] on the alert thread. When the car itself is over 50 m from its
 * segment ([carDistanceM]) the app hands the engine no route at all, so the free-drive corridor
 * applies (§11 step 4 "carOffRoute"). Degrees in, metres out; [aheadM] defaults to the 3 km of §11.
 */
class RoutePolyline(points: List<LatLngLike>, private val aheadM: Double = 3_000.0) : RouteMatcher {
    private val lat = DoubleArray(points.size) { points[it].lat }
    private val lon = DoubleArray(points.size) { points[it].lon }
    /** Distance along the route to each point. */
    private val cum = Geo.cumulativeM(points)

    /** Points and segments in the route. */
    val size: Int get() = lat.size
    val segments: Int get() = maxOf(0, lat.size - 1)

    /** The segment the car was last located on. */
    var carIndex: Int = 0
        private set

    /**
     * Updates [carIndex] from the car's position and returns it. With [headingDeg], only segments
     * running the car's way (bearing within [sameWayDeg] of the heading) count, so a route that
     * doubles back along the same road (the SDK's U-turn route from a parked car, 2026-10-13) can't
     * pin the car to the leg going the other way, which sits a few metres from the one it's on.
     */
    fun locate(carLat: Double, carLon: Double, headingDeg: Double? = null): Int {
        carIndex = nearestSegment(carLat, carLon, carIndex, headingDeg = headingDeg)
        return carIndex
    }

    // Defaults: look 500 m along from the hint (and 200 m back), take a hit within 50 m; a same-way
    // segment further than 150 m is a later leg, not our road, so the heading is then dropped.
    /**
     * The segment nearest the car, searched from up to [backM] before [hintIndex] (so a wrong jump
     * ahead can recover): first within [windowM] along the route (taken if within [snapM]), else
     * over the whole rest of the route. With [headingDeg], segments more than [sameWayDeg] off the
     * heading are skipped; if that leaves nothing, or only a same-way segment further than
     * [farSnapM] from the car (a later leg of the route, not the road the car is on), the search is
     * repeated without the heading.
     */
    fun nearestSegment(
        carLat: Double, carLon: Double, hintIndex: Int,
        windowM: Double = 500.0, snapM: Double = 50.0,
        headingDeg: Double? = null, sameWayDeg: Double = 90.0,
        backM: Double = 200.0, farSnapM: Double = 150.0,
    ): Int {
        if (segments == 0) return 0
        val hint = hintIndex.coerceIn(0, segments - 1)
        var start = hint
        while (start > 0 && (hint - start < 2 || cum[hint] - cum[start - 1] <= backM)) start--
        var best = -1
        var bestD = Double.MAX_VALUE
        fun sameWay(seg: Int) = headingDeg == null || Geo.absAngleDiffDeg(segBearing(seg), headingDeg) <= sameWayDeg
        fun consider(seg: Int) {
            if (!sameWay(seg)) return
            val d = segDistance(seg, carLat, carLon)
            if (d < bestD) { bestD = d; best = seg }
        }
        var i = start
        while (i < segments && cum[i] - cum[hint] <= windowM) consider(i++)
        if (best >= 0 && bestD <= snapM) return best
        while (i < segments) consider(i++)
        // Nothing same-way near enough: try once more ignoring the heading, else stay near the hint.
        if (best >= 0 && (headingDeg == null || bestD <= farSnapM)) return best
        return if (headingDeg != null) nearestSegment(carLat, carLon, hintIndex, windowM, snapM, null, sameWayDeg, backM, farSnapM) else start
    }

    private fun segBearing(i: Int) = Geo.bearingDeg(lat[i], lon[i], lat[i + 1], lon[i + 1])

    /**
     * Cross-track distance from (lat, lon) to the segments from [fromIndex] onward for [aheadM]
     * of route, or [Double.MAX_VALUE] when none is near.
     */
    fun distanceAheadM(pLat: Double, pLon: Double, fromIndex: Int = carIndex): Double {
        if (segments == 0) return Double.MAX_VALUE
        val from = fromIndex.coerceIn(0, segments - 1)
        return (from until segments).asSequence().takeWhile { cum[it] - cum[from] <= aheadM }
            .minOfOrNull { segDistance(it, pLat, pLon) } ?: Double.MAX_VALUE
    }

    /** The engine's view (§11 step 4): the POI's distance to the route ahead of the car's last located segment. */
    override fun distanceToRouteM(lat: Double, lon: Double): Double = distanceAheadM(lat, lon, carIndex)

    /** How far the car is from the segment it was last located on: over ~50 m the car isn't on this route. */
    fun carDistanceM(carLat: Double, carLon: Double): Double =
        if (segments == 0) Double.MAX_VALUE else segDistance(carIndex.coerceIn(0, segments - 1), carLat, carLon)

    private fun segDistance(i: Int, pLat: Double, pLon: Double) =
        Geo.distanceToSegmentM(pLat, pLon, lat[i], lon[i], lat[i + 1], lon[i + 1])
}

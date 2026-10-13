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
 */
class RoutePolyline(points: List<LatLngLike>, private val aheadM: Double = 3_000.0) : RouteMatcher {
    private val lat = DoubleArray(points.size) { points[it].lat }
    private val lon = DoubleArray(points.size) { points[it].lon }
    /** Distance along the route to each point. */
    private val cum = DoubleArray(points.size)

    init {
        for (i in 1 until points.size) cum[i] = cum[i - 1] + Geo.distanceM(lat[i - 1], lon[i - 1], lat[i], lon[i])
    }

    val size: Int get() = lat.size
    val lengthM: Double get() = if (cum.isEmpty()) 0.0 else cum.last()
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

    /**
     * The segment nearest the car, searched from a little before [hintIndex]: first within
     * [windowM] along the route (taken if within [snapM]), else over the whole rest of the route.
     * With [headingDeg], segments more than [sameWayDeg] off the heading are skipped; if that
     * leaves nothing (a short route of turns), the search is repeated without the heading.
     */
    fun nearestSegment(
        carLat: Double, carLon: Double, hintIndex: Int,
        windowM: Double = 500.0, snapM: Double = 50.0,
        headingDeg: Double? = null, sameWayDeg: Double = 90.0,
    ): Int {
        if (segments == 0) return 0
        val start = (hintIndex - 2).coerceIn(0, segments - 1)
        var best = -1
        var bestD = Double.MAX_VALUE
        var i = start
        fun sameWay(seg: Int) = headingDeg == null || Geo.absAngleDiffDeg(segBearing(seg), headingDeg) <= sameWayDeg
        while (i < segments && cum[i] - cum[start] <= windowM) {
            if (sameWay(i)) {
                val d = segDistance(i, carLat, carLon)
                if (d < bestD) { bestD = d; best = i }
            }
            i++
        }
        if (best >= 0 && bestD <= snapM) return best
        while (i < segments) {
            if (sameWay(i)) {
                val d = segDistance(i, carLat, carLon)
                if (d < bestD) { bestD = d; best = i }
            }
            i++
        }
        if (best >= 0) return best
        return if (headingDeg != null) nearestSegment(carLat, carLon, hintIndex, windowM, snapM, null, sameWayDeg) else start
    }

    private fun segBearing(i: Int) = Geo.bearingDeg(lat[i], lon[i], lat[i + 1], lon[i + 1])

    /**
     * Cross-track distance from (lat, lon) to the segments from [fromIndex] onward for [aheadM]
     * of route, or [Double.MAX_VALUE] when none is near.
     */
    fun distanceAheadM(pLat: Double, pLon: Double, fromIndex: Int = carIndex): Double {
        if (segments == 0) return Double.MAX_VALUE
        val from = fromIndex.coerceIn(0, segments - 1)
        var best = Double.MAX_VALUE
        var i = from
        while (i < segments && cum[i] - cum[from] <= aheadM) {
            val d = segDistance(i, pLat, pLon)
            if (d < best) best = d
            i++
        }
        return best
    }

    override fun distanceToRouteM(lat: Double, lon: Double): Double = distanceAheadM(lat, lon, carIndex)

    private fun segDistance(i: Int, pLat: Double, pLon: Double) =
        Geo.distanceToSegmentM(pLat, pLon, lat[i], lon[i], lat[i + 1], lon[i + 1])
}

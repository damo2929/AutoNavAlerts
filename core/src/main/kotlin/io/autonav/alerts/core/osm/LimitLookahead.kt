// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.geo.LatLngLike
import io.autonav.alerts.core.geo.LatLngPoint

/**
 * The next change of speed limit on the road the car is on (DESIGN.md §8.4a, "Limit changes
 * ahead"). The limit file has no road names, so without a route the road is followed by
 * connectivity: from the piece under the car to its end, the piece joined there (within 40 m,
 * heading within 45°, straightest first, [LimitIndex.joinFrom]), and so on up to [Config.maxM] or
 * until the limit differs, stopping at the first fork (owner, 2026-10-23: past a slip or a
 * roundabout exit the branch can't be known without a route). With a route, its polyline ahead is
 * sampled every [Config.sampleM] instead, which is exact and treats a turn onto a 30 road as the
 * limit about to apply.
 *
 * No Android, no threads of its own. The app's `SpeedLimitRepository` wraps it: [nextChange] is
 * evaluated once a second by `NavSession` (never per fix) for the announcement and the map
 * marker; [roadAhead] is asked by `AlertCoordinator` for the free-drive road the engine matches
 * cameras against (once a second, and at once after a 10° turn, at most four times a second, §11
 * step 4). Degrees in; metres and mph out.
 */
class LimitLookahead(private val index: LimitIndex, private val config: Config = Config()) {
    data class Config(
        // 600 m ahead, sampled every 25 m along a route (§8.4a "Limit changes ahead").
        val maxM: Double = 600.0,
        val sampleM: Double = 25.0,
        /** A cap on the connectivity walk, so a data loop can't spin. */
        val maxSteps: Int = 200,
    )

    /** [lat]/[lon]: where the new limit starts (the joining point on the road, or the route sample), for the map marker (§8.4a). */
    /** [newMph] the limit about to apply; [distanceM] along the road or route to where it starts; [viaRoute] which walk found it. */
    data class Change(val newMph: Int, val distanceM: Double, val viaRoute: Boolean, val lat: Double = Double.NaN, val lon: Double = Double.NaN)

    /**
     * The next change ahead, or null: no piece under the car (no data, or a road with no posted
     * limit), no change within [Config.maxM], or a fork before it in free drive.
     *
     * @param routeAhead the route from the car onward (first point at the car), or null in free drive.
     */
    fun nextChange(lat: Double, lon: Double, headingDeg: Double, routeAhead: List<LatLngLike>? = null): Change? =
        if (routeAhead != null && routeAhead.size >= 2) alongRoute(lat, lon, headingDeg, routeAhead)
        else alongRoad(lat, lon, headingDeg)

    private fun alongRoad(lat: Double, lon: Double, headingDeg: Double): Change? {
        var piece = index.pieceAt(lat, lon, headingDeg) ?: return null
        val mph0 = index.mphOf(piece)
        // Pieces have a drawing direction; we walk whichever way agrees with the heading.
        var forward = Geo.absAngleDiffDeg(index.bearingOf(piece), headingDeg) <= 90.0
        var end = index.endOf(piece, forward)
        var out = outBearing(piece, forward)
        var d = Geo.distanceM(lat, lon, end.lat, end.lon)
        var steps = 0
        while (d <= config.maxM && steps++ < config.maxSteps) {
            val join = index.joinFrom(end.lat, end.lon, out, exclude = piece) ?: return null
            // Past a fork we can't know the branch without a route: say nothing yet (§8.4a).
            if (join.fork) return null
            val next = join.piece
            val mph = index.mphOf(next)
            if (mph != mph0) return Change(mph, d, viaRoute = false, lat = end.lat, lon = end.lon)
            val start = index.startOf(next)
            val stop = index.endOf(next, true)
            // The next piece is walked from whichever of its ends touched the join.
            forward = Geo.distanceM(end.lat, end.lon, start.lat, start.lon) <= Geo.distanceM(end.lat, end.lon, stop.lat, stop.lon)
            d += index.lengthOf(next)
            piece = next
            end = index.endOf(piece, forward)
            out = outBearing(piece, forward)
        }
        return null
    }

    /**
     * The road ahead of the car as a line (DESIGN.md §11 step 4, free drive): the car's point, then
     * the end of each piece the connectivity walk follows (as [alongRoad], ignoring limits), until
     * [maxM] or until no piece continues. Null when no piece is under the car.
     */
    fun roadAhead(lat: Double, lon: Double, headingDeg: Double, maxM: Double): List<LatLngLike>? {
        var piece = index.pieceAt(lat, lon, headingDeg) ?: return null
        var forward = Geo.absAngleDiffDeg(index.bearingOf(piece), headingDeg) <= 90.0
        var end = index.endOf(piece, forward)
        var out = outBearing(piece, forward)
        val points = arrayListOf<LatLngLike>(LatLngPoint(lat, lon), end)
        var d = Geo.distanceM(lat, lon, end.lat, end.lon)
        var steps = 0
        while (d < maxM && steps++ < config.maxSteps) {
            val next = index.connectedFrom(end.lat, end.lon, out, exclude = piece) ?: break
            val start = index.startOf(next)
            val stop = index.endOf(next, true)
            forward = Geo.distanceM(end.lat, end.lon, start.lat, start.lon) <= Geo.distanceM(end.lat, end.lon, stop.lat, stop.lon)
            d += index.lengthOf(next)
            piece = next
            end = index.endOf(piece, forward)
            out = outBearing(piece, forward)
            points += end
        }
        return points
    }

    private fun outBearing(piece: Int, forward: Boolean) =
        if (forward) index.bearingOf(piece) else Geo.normalizeDeg(index.bearingOf(piece) + 180.0)

    // With a route the limit under each sample point is looked up with the route's own bearing there,
    // so a side road at a junction doesn't match (§8.4a "With a route").
    private fun alongRoute(lat: Double, lon: Double, headingDeg: Double, route: List<LatLngLike>): Change? {
        val mph0 = index.limitAt(lat, lon, headingDeg) ?: return null
        val n = route.size
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + Geo.distanceM(route[i - 1].lat, route[i - 1].lon, route[i].lat, route[i].lon)
        var seg = 0
        var s = config.sampleM
        while (s <= config.maxM && s <= cum[n - 1]) {
            while (seg < n - 2 && cum[seg + 1] < s) seg++
            val a = route[seg]; val b = route[seg + 1]
            val len = cum[seg + 1] - cum[seg]
            val t = if (len <= 0.0) 0.0 else ((s - cum[seg]) / len).coerceIn(0.0, 1.0)
            val pLat = a.lat + t * (b.lat - a.lat)
            val pLon = a.lon + t * (b.lon - a.lon)
            val bearing = Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon)
            val mph = index.limitAt(pLat, pLon, bearing)
            if (mph != null && mph != mph0) return Change(mph.toInt(), s, viaRoute = true, lat = pLat, lon = pLon)
            s += config.sampleM
        }
        return null
    }
}

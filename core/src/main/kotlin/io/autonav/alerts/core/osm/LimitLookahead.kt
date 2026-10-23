// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.geo.LatLngLike
import io.autonav.alerts.core.geo.LatLngPoint

/**
 * The next change of speed limit on the road the car is on (DESIGN.md §8.4a, "Limit changes
 * ahead"). The limit file has no road names, so without a route the road is followed by
 * connectivity: from the piece under the car to its end, the piece joined there (within 20 m,
 * heading within 45°, straightest first), and so on up to [Config.maxM] or until the limit
 * differs. With a route, its polyline ahead is sampled every [Config.sampleM] instead, which is
 * exact and treats a turn onto a 30 road as the limit about to apply.
 */
class LimitLookahead(private val index: LimitIndex, private val config: Config = Config()) {
    data class Config(
        val maxM: Double = 600.0,
        val sampleM: Double = 25.0,
        /** A cap on the connectivity walk, so a data loop can't spin. */
        val maxSteps: Int = 200,
    )

    /** [lat]/[lon]: where the new limit starts (the joining point on the road, or the route sample), for the map marker (§8.4a). */
    data class Change(val newMph: Int, val distanceM: Double, val viaRoute: Boolean, val lat: Double = Double.NaN, val lon: Double = Double.NaN)

    /**
     * @param routeAhead the route from the car onward (first point at the car), or null in free drive.
     */
    fun nextChange(lat: Double, lon: Double, headingDeg: Double, routeAhead: List<LatLngLike>? = null): Change? =
        if (routeAhead != null && routeAhead.size >= 2) alongRoute(lat, lon, headingDeg, routeAhead)
        else alongRoad(lat, lon, headingDeg)

    private fun alongRoad(lat: Double, lon: Double, headingDeg: Double): Change? {
        var piece = index.pieceAt(lat, lon, headingDeg) ?: return null
        val mph0 = index.mphOf(piece)
        var forward = Geo.absAngleDiffDeg(index.bearingOf(piece), headingDeg) <= 90.0
        var end = index.endOf(piece, forward)
        var out = outBearing(piece, forward)
        var d = Geo.distanceM(lat, lon, end.lat, end.lon)
        var steps = 0
        while (d <= config.maxM && steps++ < config.maxSteps) {
            val next = index.connectedFrom(end.lat, end.lon, out, exclude = piece) ?: return null
            val mph = index.mphOf(next)
            if (mph != mph0) return Change(mph, d, viaRoute = false, lat = end.lat, lon = end.lon)
            val start = index.startOf(next)
            val stop = index.endOf(next, true)
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

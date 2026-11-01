// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.geo.LatLngLike

/**
 * The low bridges on a route, for the route preview's warning (DESIGN.md §6.5 "Warned on the route preview", owner
 * 2026-11-01). Google's routing can't avoid them, so the preview says so before Go. A bridge counts when it lies
 * within [TOLERANCE_M] of any segment of the route. Pure; the route preview calls it off the main thread with the
 * SDK's route points and the bridges that alert for the vehicle.
 */
object RouteBridges {
    /** A bridge point and its signed height in centimetres (null: unknown). */
    data class Bridge(override val lat: Double, override val lon: Double, val heightCm: Int?) : LatLngLike

    /** What the preview shows: how many bridges, and the lowest known height among them (null when all are unknown). */
    data class OnRoute(val count: Int, val lowestCm: Int?)

    /** How close to the route a bridge must be to count: about the road's width and GPS slack (§6.5). */
    const val TOLERANCE_M = 30.0

    /** The [bridges] within [toleranceM] of [route], or null when there are none (or no route). */
    fun onRoute(route: List<LatLngLike>, bridges: List<Bridge>, toleranceM: Double = TOLERANCE_M): OnRoute? {
        if (route.size < 2 || bridges.isEmpty()) return null
        // A box round the route, widened by the tolerance (≈ 0.0003° per 30 m of latitude; longitude scaled
        // generously), skips the far bridges before any segment maths.
        val pad = toleranceM / 100_000.0 * 2
        val minLat = route.minOf { it.lat } - pad; val maxLat = route.maxOf { it.lat } + pad
        val minLon = route.minOf { it.lon } - pad * 2; val maxLon = route.maxOf { it.lon } + pad * 2
        val hits = bridges.filter { b ->
            b.lat in minLat..maxLat && b.lon in minLon..maxLon &&
                route.zipWithNext().any { (a, c) -> Geo.distanceToSegmentM(b.lat, b.lon, a.lat, a.lon, c.lat, c.lon) <= toleranceM }
        }
        if (hits.isEmpty()) return null
        return OnRoute(hits.size, hits.mapNotNull { it.heightCm }.minOrNull())
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.geo.LatLngPoint
import io.autonav.alerts.core.nav.RouteBridges.Bridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The low bridges on a route for the preview's warning (DESIGN.md §6.5). Made-up points on a straight road. */
class RouteBridgesTest {
    // A road due north from 52.0 N, 1.3 W, about 2.2 km long in four segments.
    private val route = (0..4).map { LatLngPoint(52.0 + it * 0.005, -1.3) }
    private val metrePerDegLon = 111_320.0 * Math.cos(Math.toRadians(52.0))
    private fun east(m: Double) = -1.3 + m / metrePerDegLon

    @Test
    fun bridgesOnTheRoadCountAndTheLowestIsGiven() {
        val bridges = listOf(
            Bridge(52.004, east(5.0), 191), // 5 m off the road
            Bridge(52.012, east(-20.0), 175), // 20 m the other side
            Bridge(52.003, east(200.0), 150), // a side street 200 m away
        )
        assertEquals(RouteBridges.OnRoute(2, 175), RouteBridges.onRoute(route, bridges))
    }

    @Test
    fun unknownHeightsCountButHaveNoLowest() {
        assertEquals(RouteBridges.OnRoute(1, null), RouteBridges.onRoute(route, listOf(Bridge(52.01, -1.3, null))))
    }

    @Test
    fun nothingNearTheRouteOrNoRouteGivesNoWarning() {
        assertNull(RouteBridges.onRoute(route, listOf(Bridge(52.01, east(40.0), 150))))
        // Beyond the end of the route.
        assertNull(RouteBridges.onRoute(route, listOf(Bridge(52.03, -1.3, 150))))
        assertNull(RouteBridges.onRoute(emptyList(), listOf(Bridge(52.01, -1.3, 150))))
        assertNull(RouteBridges.onRoute(route, emptyList()))
    }
}

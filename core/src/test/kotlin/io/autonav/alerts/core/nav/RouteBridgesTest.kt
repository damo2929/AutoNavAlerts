// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.geo.Geo
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
        val on = RouteBridges.onRoute(route, bridges)!!
        assertEquals(2, on.count)
        assertEquals(175, on.lowestCm)
    }

    @Test
    fun theFirstBridgeIsMeasuredAlongTheRoute() {
        // Listed out of order: the one at 52.004 (about 445 m up the road) is first, not the one listed first.
        val bridges = listOf(Bridge(52.012, east(-20.0), 175), Bridge(52.004, east(5.0), 191))
        val metresPerDegLat = Geo.distanceM(52.0, -1.3, 53.0, -1.3)
        assertEquals(0.004 * metresPerDegLat, RouteBridges.onRoute(route, bridges)!!.firstAlongM, 2.0)
        // A bridge in the third segment: two whole segments plus the part of the third.
        assertEquals(0.011 * metresPerDegLat, RouteBridges.onRoute(route, listOf(Bridge(52.011, -1.3, 150)))!!.firstAlongM, 2.0)
    }

    @Test
    fun unknownHeightsCountButHaveNoLowest() {
        val on = RouteBridges.onRoute(route, listOf(Bridge(52.01, -1.3, null)))!!
        assertEquals(1, on.count)
        assertNull(on.lowestCm)
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

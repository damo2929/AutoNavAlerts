// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.alerts.RouteMatcher
import io.autonav.alerts.core.geo.LatLngPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * More of the route matching of DESIGN.md §11 step 4: the car's own distance from its segment
 * (over 50 m the app hands the engine no route, "carOffRoute"), a car found far off the route,
 * the window ahead clamped and sized, and the sizes of degenerate routes. Metres north/east of 53°N, 0°E.
 */
class RoutePolylineMoreTest {
    private val metresPerDegLat = 111_195.0
    private fun latAt(northM: Double) = 53.0 + northM / metresPerDegLat
    private fun lonAt(eastM: Double) = eastM / (metresPerDegLat * cos(Math.toRadians(53.0)))
    private fun p(northM: Double, eastM: Double) = LatLngPoint(latAt(northM), lonAt(eastM))
    private fun straight(toM: Double) = (0..(toM / 100).toInt()).map { p(it * 100.0, 0.0) }

    @Test
    fun sizesOfDegenerateRoutes() {
        assertEquals(0, RoutePolyline(emptyList()).segments)
        assertEquals(1, RoutePolyline(listOf(p(0.0, 0.0))).size)
        assertEquals(0, RoutePolyline(listOf(p(0.0, 0.0))).segments)
        assertEquals(4, RoutePolyline(straight(400.0)).segments)
        // Nothing to locate on: segment 0, and the car is nowhere near.
        val empty = RoutePolyline(emptyList())
        assertEquals(0, empty.locate(latAt(0.0), lonAt(0.0)))
        assertEquals(Double.MAX_VALUE, empty.carDistanceM(latAt(0.0), lonAt(0.0)), 0.0)
        assertEquals(Double.MAX_VALUE, RoutePolyline(listOf(p(0.0, 0.0))).carDistanceM(latAt(0.0), lonAt(0.0)), 0.0)
    }

    @Test
    fun carDistanceIsFromTheSegmentTheCarWasLastLocatedOn() {
        val r = RoutePolyline(straight(5_000.0))
        assertEquals(12, r.locate(latAt(1_250.0), lonAt(30.0)))
        assertEquals(30.0, r.carDistanceM(latAt(1_250.0), lonAt(30.0)), 0.5)
        // Without locating again, a position further on is measured to the old segment's end.
        assertEquals(750.0, r.carDistanceM(latAt(2_050.0), lonAt(0.0)), 1.0)
    }

    @Test
    fun aCarFarOffTheRouteIsFoundOnTheNearestSegmentAndReadsAsOffRoute() {
        val r = RoutePolyline(straight(5_000.0))
        val idx = r.locate(latAt(2_050.0), lonAt(1_000.0))
        assertEquals(20, idx)
        assertEquals(1_000.0, r.carDistanceM(latAt(2_050.0), lonAt(1_000.0)), 2.0)
        assertTrue(r.carDistanceM(latAt(2_050.0), lonAt(1_000.0)) > 50.0)
    }

    @Test
    fun theWindowAheadIsClampedToTheRouteAndSizedByAheadM() {
        val r = RoutePolyline(straight(2_000.0), aheadM = 550.0)
        // The window takes segments starting within 550 m: up to the one ending at 600 m.
        assertEquals(0.0, r.distanceAheadM(latAt(450.0), lonAt(0.0), fromIndex = 0), 0.5)
        assertEquals(400.0, r.distanceAheadM(latAt(1_000.0), lonAt(0.0), fromIndex = 0), 1.0)
        // A start index past the end is the last segment; a negative one the first.
        assertEquals(0.0, r.distanceAheadM(latAt(1_950.0), lonAt(0.0), fromIndex = 99), 0.5)
        assertEquals(0.0, r.distanceAheadM(latAt(50.0), lonAt(0.0), fromIndex = -5), 0.5)
        // Behind the window's start doesn't count.
        assertEquals(1_000.0, r.distanceAheadM(latAt(500.0), lonAt(0.0), fromIndex = 15), 1.0)
    }

    @Test
    fun theEngineSeesTheRouteFromTheCarsSegment() {
        val r = RoutePolyline(straight(5_000.0))
        val m: RouteMatcher = r
        assertEquals(1_000.0, m.distanceToRouteM(latAt(-1_000.0), lonAt(0.0))!!, 1.0)
        r.locate(latAt(3_000.0), lonAt(0.0))
        // A point at the start is now behind the car: only the window ahead counts.
        assertTrue(m.distanceToRouteM(latAt(0.0), lonAt(0.0))!! > 2_500.0)
        assertEquals(0.0, m.distanceToRouteM(latAt(4_000.0), lonAt(0.0))!!, 0.5)
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.geo.LatLngPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** Route matching against the segments ahead only (DESIGN.md §11 step 4). Metres north/east of 53°N, 0°E. */
class RoutePolylineTest {
    private val baseLat = 53.0
    private val metresPerDegLat = 111_195.0
    private fun latAt(northM: Double) = baseLat + northM / metresPerDegLat
    private fun lonAt(eastM: Double) = eastM / (metresPerDegLat * cos(Math.toRadians(baseLat)))
    private fun p(northM: Double, eastM: Double) = LatLngPoint(latAt(northM), lonAt(eastM))

    /** Due north from 0 to [toM], a point every 100 m. */
    private fun straight(toM: Double) = (0..(toM / 100).toInt()).map { p(it * 100.0, 0.0) }

    @Test
    fun pointOnTheRouteAheadIsAtZero() {
        val r = RoutePolyline(straight(5_000.0))
        r.locate(latAt(0.0), lonAt(0.0))
        assertEquals(0.0, r.distanceAheadM(latAt(1_050.0), lonAt(0.0)), 0.5)
        assertEquals(60.0, r.distanceAheadM(latAt(1_050.0), lonAt(60.0)), 1.0)
        assertEquals(60.0, r.distanceToRouteM(latAt(1_050.0), lonAt(60.0)), 1.0)
    }

    @Test
    fun earlierLoopIsIgnored() {
        // North 0→1000 on x=0, east to x=200, back south 1000→0 on x=200.
        val pts = (0..10).map { p(it * 100.0, 0.0) } + p(1_000.0, 200.0) + (10 downTo 0).map { p(it * 100.0, 200.0) }
        val r = RoutePolyline(pts)
        // Car heading south on the return leg at (500, 200), reached after the northbound leg.
        r.locate(latAt(950.0), lonAt(0.0))
        r.locate(latAt(1_000.0), lonAt(120.0))
        val idx = r.locate(latAt(500.0), lonAt(200.0))
        assertTrue("car segment $idx", idx >= 11)
        // A camera on the northbound leg at (300, 0) is behind us on the route: 200 m from the leg we're on.
        assertEquals(200.0, r.distanceAheadM(latAt(300.0), lonAt(0.0)), 2.0)
        // One on the return leg ahead at (200, 200) is on the route.
        assertEquals(0.0, r.distanceAheadM(latAt(200.0), lonAt(200.0)), 0.5)
        // Before the loop, with the car at the start, the northbound camera is on the route.
        val fresh = RoutePolyline(pts)
        fresh.locate(latAt(0.0), lonAt(0.0))
        assertEquals(0.0, fresh.distanceAheadM(latAt(300.0), lonAt(0.0)), 0.5)
    }

    @Test
    fun headingPicksTheLegRunningOurWayWhenTheRouteDoublesBack() {
        // The SDK's U-turn route from a parked car (bench, 2026-10-13): north 0→2000 on x=0 (the wrong
        // carriageway), round at the top, then south 2000→-2000 on x=10, i.e. 10 m from the outbound leg.
        val pts = (0..20).map { p(it * 100.0, 0.0) } + p(2_050.0, 5.0) + (20 downTo -20).map { p(it * 100.0, 10.0) }
        val r = RoutePolyline(pts)
        // The car never went north: it's at the start, heading south (180°), on the southbound leg.
        val idx = r.locate(latAt(0.0), lonAt(10.0), headingDeg = 180.0)
        assertTrue("car segment $idx should be on the southbound leg", idx >= 21)
        // A southbound camera 440 m ahead at (-440, 10) is on the route ahead, not kilometres off it.
        assertEquals(0.0, r.distanceToRouteM(latAt(-440.0), lonAt(10.0)), 0.5)
        // Without a heading the nearest segment is the outbound leg, and the 3 km window ahead of it never
        // reaches that camera: the bench's "offRoad lateral=1204 allowed=50" for a camera 440 m away.
        val blind = RoutePolyline(pts)
        val blindIdx = blind.locate(latAt(0.0), lonAt(10.0))
        assertTrue("blind segment $blindIdx", blindIdx < 21)
        assertTrue(blind.distanceToRouteM(latAt(-440.0), lonAt(10.0)) > 50.0)
        // A heading that matches no segment (route of turns at 90° to it) falls back to the plain search.
        val fallback = RoutePolyline(straight(1_000.0))
        assertEquals(0, fallback.locate(latAt(0.0), lonAt(0.0), headingDeg = 90.0))
    }

    @Test
    fun beyondThreeKilometresIsIgnored() {
        val r = RoutePolyline(straight(6_000.0))
        r.locate(latAt(0.0), lonAt(0.0))
        // On the route, but 4 km ahead: only the distance to the end of the 3 km window (far outside 50 m).
        assertTrue(r.distanceAheadM(latAt(4_000.0), lonAt(0.0)) > 800.0)
        assertEquals(0.0, r.distanceAheadM(latAt(2_900.0), lonAt(0.0)), 0.5)
        // Moving the car along brings it into range.
        r.locate(latAt(1_500.0), lonAt(0.0))
        assertEquals(0.0, r.distanceAheadM(latAt(4_000.0), lonAt(0.0)), 0.5)
    }

    @Test
    fun hintAdvancesAndRecoversAJump() {
        val r = RoutePolyline(straight(5_000.0))
        assertEquals(0, r.carIndex)
        assertEquals(3, r.locate(latAt(350.0), lonAt(0.0)))
        assertEquals(3, r.carIndex)
        // A small step back (noise) is allowed; a long jump ahead is found from the hint.
        assertEquals(2, r.locate(latAt(290.0), lonAt(0.0)))
        assertEquals(30, r.locate(latAt(3_050.0), lonAt(0.0)))
        assertEquals(30, r.nearestSegment(latAt(3_050.0), lonAt(0.0), hintIndex = 0))
        // Empty and single-point routes never match.
        assertEquals(Double.MAX_VALUE, RoutePolyline(emptyList()).distanceAheadM(latAt(0.0), 0.0), 0.0)
        assertEquals(Double.MAX_VALUE, RoutePolyline(listOf(p(0.0, 0.0))).distanceAheadM(latAt(0.0), 0.0), 0.0)
    }
}

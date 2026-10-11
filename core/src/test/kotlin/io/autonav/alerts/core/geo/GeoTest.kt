// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test
    fun distanceOfOneHundredthDegreeLatitude() {
        assertEquals(1112.0, Geo.distanceM(53.80, -1.55, 53.81, -1.55), 2.0)
    }

    @Test
    fun cardinalBearings() {
        assertEquals(0.0, Geo.bearingDeg(53.8, -1.55, 53.9, -1.55), 0.01)
        assertEquals(180.0, Geo.bearingDeg(53.9, -1.55, 53.8, -1.55), 0.01)
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 0.1), 0.01)
    }

    @Test
    fun angleDiffWraps() {
        assertEquals(20.0, Geo.angleDiffDeg(10.0, 350.0), 1e-9)
        assertEquals(-20.0, Geo.angleDiffDeg(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.absAngleDiffDeg(0.0, 180.0), 1e-9)
    }

    @Test
    fun distanceToSegment() {
        // Point 100 m east of a north-south segment.
        val dLon = 100.0 / (Geo.EARTH_RADIUS_M * Math.cos(Math.toRadians(53.8)) * Math.PI / 180)
        val d = Geo.distanceToSegmentM(53.805, -1.55 + dLon, 53.80, -1.55, 53.81, -1.55)
        assertEquals(100.0, d, 1.0)
        // Beyond the end: distance to the end point.
        val beyond = Geo.distanceToSegmentM(53.82, -1.55, 53.80, -1.55, 53.81, -1.55)
        assertEquals(1112.0, beyond, 3.0)
    }
}

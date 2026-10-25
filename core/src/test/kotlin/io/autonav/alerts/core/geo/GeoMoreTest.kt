// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.geo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The spherical and flat geometry behind DESIGN.md §11 (distances, bearings, the within-45° tests
 * of steps 2 and 3, the route's measure of step 4) and §8.4a (the flat road matching): known
 * values on the mean-radius sphere, wrap-around at 0/360 and the antimeridian, and the segment
 * distances clamped at both ends.
 */
class GeoMoreTest {
    /** One degree of arc on the mean-radius sphere. */
    private val degM = Geo.EARTH_RADIUS_M * Math.PI / 180

    @Test
    fun distanceKnownValuesAndSymmetry() {
        assertEquals(111_195.08, degM, 0.01)
        assertEquals(degM, Geo.distanceM(0.0, 0.0, 0.0, 1.0), 0.01)
        assertEquals(degM, Geo.distanceM(53.0, -1.0, 54.0, -1.0), 0.01)
        // One degree of longitude at 60° is half as long, give or take the great circle's bow.
        assertEquals(degM / 2, Geo.distanceM(60.0, 0.0, 60.0, 1.0), 10.0)
        // Across the antimeridian it is the short way round.
        assertEquals(degM, Geo.distanceM(0.0, 179.5, 0.0, -179.5), 0.01)
        assertEquals(0.0, Geo.distanceM(53.8, -1.5, 53.8, -1.5), 0.0)
        assertEquals(Geo.distanceM(53.8, -1.5, 51.5, 0.1), Geo.distanceM(51.5, 0.1, 53.8, -1.5), 1e-6)
        // Half the world, pole to pole.
        assertEquals(180 * degM, Geo.distanceM(90.0, 0.0, -90.0, 0.0), 0.01)
    }

    @Test
    fun bearingsAcrossTheAntimeridianAndBackwards() {
        assertEquals(90.0, Geo.bearingDeg(0.0, 179.5, 0.0, -179.5), 1e-9)
        assertEquals(270.0, Geo.bearingDeg(0.0, -179.5, 0.0, 179.5), 1e-9)
        assertEquals(270.0, Geo.bearingDeg(53.8, -1.5, 53.8, -1.6), 0.1)
        assertEquals(45.0, Geo.bearingDeg(0.0, 0.0, 0.001, 0.001), 0.01)
        // The same point: due north by convention.
        assertEquals(0.0, Geo.bearingDeg(53.8, -1.5, 53.8, -1.5), 0.0)
    }

    @Test
    fun normalizeAndDifferencesWrap() {
        assertEquals(270.0, Geo.normalizeDeg(-90.0), 0.0)
        assertEquals(0.0, Geo.normalizeDeg(720.0), 0.0)
        assertEquals(0.0, Geo.normalizeDeg(-360.0), 0.0)
        assertEquals(359.5, Geo.normalizeDeg(-0.5), 1e-12)
        // Exactly opposite is +180 whichever way round: the range is (-180, 180].
        assertEquals(180.0, Geo.angleDiffDeg(180.0, 0.0), 0.0)
        assertEquals(180.0, Geo.angleDiffDeg(0.0, 180.0), 0.0)
        assertEquals(10.0, Geo.absAngleDiffDeg(355.0, 5.0), 1e-12)
        assertEquals(20.0, Geo.absAngleDiffDeg(-10.0, 10.0), 1e-12)
        assertEquals(30.0, Geo.absAngleDiffDeg(750.0, 0.0), 1e-12)
        // The 45° boundary of the direction rule is inclusive arithmetic, not an off-by-wrap.
        assertEquals(45.0, Geo.absAngleDiffDeg(337.5, 22.5), 1e-12)
    }

    @Test
    fun cumulativeDistancesAddUpPointByPoint() {
        assertArrayEquals(DoubleArray(0), Geo.cumulativeM(emptyList()), 0.0)
        assertArrayEquals(doubleArrayOf(0.0), Geo.cumulativeM(listOf(LatLngPoint(53.0, -1.0))), 0.0)
        val pts = listOf(LatLngPoint(0.0, 0.0), LatLngPoint(0.0, 1.0), LatLngPoint(0.0, 1.0), LatLngPoint(1.0, 1.0))
        assertArrayEquals(doubleArrayOf(0.0, degM, degM, 2 * degM), Geo.cumulativeM(pts), 0.01)
    }

    @Test
    fun sphericalSegmentDistanceClampsAtBothEnds() {
        // A zero-length segment is its point.
        assertEquals(Geo.distanceM(53.8, -1.5, 53.81, -1.5), Geo.distanceToSegmentM(53.81, -1.5, 53.8, -1.5, 53.8, -1.5), 1e-9)
        // Before the start: the distance to the start, not to the line.
        val before = Geo.distanceToSegmentM(53.79, -1.5, 53.80, -1.5, 53.81, -1.5)
        assertEquals(Geo.distanceM(53.79, -1.5, 53.80, -1.5), before, 1e-6)
        // On the segment itself.
        assertEquals(0.0, Geo.distanceToSegmentM(53.805, -1.5, 53.80, -1.5, 53.81, -1.5), 0.01)
    }

    @Test
    fun flatProjectionDistances() {
        assertEquals(111_320.0, Geo.metresPerDegLon(0.0), 1e-6)
        assertEquals(55_660.0, Geo.metresPerDegLon(60.0), 1e-6)
        val kx = Geo.metresPerDegLon(53.0)
        assertEquals(110.54, Geo.flatDistanceM(53.0, -1.0, 53.001, -1.0, kx), 1e-9)
        assertEquals(5.0, Geo.flatDistanceM(53.0, -1.0, 53.0 + 4.0 / Geo.METRES_PER_DEG_LAT, -1.0 + 3.0 / kx, kx), 1e-9)
        // Perpendicular to an east-west segment, beyond its east end, and a zero-length one.
        val north20 = 53.0 + 20.0 / Geo.METRES_PER_DEG_LAT
        assertEquals(20.0, Geo.flatDistanceToSegmentM(north20, -1.0, 53.0, -1.001, 53.0, -0.999, kx), 1e-9)
        assertEquals(30.0, Geo.flatDistanceToSegmentM(53.0, -0.999 + 30.0 / kx, 53.0, -1.001, 53.0, -0.999, kx), 1e-9)
        assertEquals(20.0, Geo.flatDistanceToSegmentM(north20, -1.0, 53.0, -1.0, 53.0, -1.0, kx), 1e-9)
    }
}

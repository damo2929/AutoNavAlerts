// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import io.autonav.alerts.core.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN.md §8.4a "Limit changes ahead" edges beyond `LimitAnnouncerTest`: the lead boundary (distance ≤
 * max(300 m, speed × 12 s)), what counts as the same change (same limit within 200 m), [LimitAnnouncer.reset],
 * the projection and the spoken words for every usual UK limit.
 */
class LimitAnnouncerEdgeTest {
    private val lat = 53.38
    private val lon = -1.47

    @Test fun theLeadIsInclusive() {
        assertTrue(LimitAnnouncer().shouldAnnounce(30, 300.0, lat, lon, 10.0, 0.0))
        assertFalse(LimitAnnouncer().shouldAnnounce(30, 300.5, lat, lon, 10.0, 0.0))
        // 30 m/s × 12 s = 360 m.
        assertTrue(LimitAnnouncer().shouldAnnounce(70, 360.0, lat, lon, 30.0, 0.0))
        assertFalse(LimitAnnouncer().shouldAnnounce(70, 361.0, lat, lon, 30.0, 0.0))
        // Standing still: the 300 m floor.
        assertTrue(LimitAnnouncer().shouldAnnounce(20, 250.0, lat, lon, 0.0, 0.0))
    }

    @Test fun aRefusedChangeIsNotRemembered() {
        val a = LimitAnnouncer()
        assertFalse(a.shouldAnnounce(30, 500.0, lat, lon, 10.0, 0.0))
        // Closer now: still the first time it's said.
        assertTrue(a.shouldAnnounce(30, 290.0, lat, lon, 10.0, 0.0))
    }

    @Test fun aDifferentLimitAtTheSamePlaceIsAnnounced() {
        val a = LimitAnnouncer()
        val at = LimitAnnouncer.project(lat, lon, 0.0, 200.0)
        assertTrue(a.shouldAnnounce(30, 200.0, lat, lon, 10.0, 0.0, at.first, at.second))
        assertTrue(a.shouldAnnounce(40, 200.0, lat, lon, 10.0, 0.0, at.first, at.second))
        // Only the last announcement is kept: back to 30 at that place is said again.
        assertTrue(a.shouldAnnounce(30, 200.0, lat, lon, 10.0, 0.0, at.first, at.second))
    }

    @Test fun sameLimitIsTheSameChangeUnder200mApart() {
        val a = LimitAnnouncer()
        val first = LimitAnnouncer.project(lat, lon, 0.0, 250.0)
        assertTrue(a.shouldAnnounce(50, 250.0, lat, lon, 10.0, 0.0, first.first, first.second))
        val near = LimitAnnouncer.project(first.first, first.second, 90.0, 190.0)
        assertFalse(a.shouldAnnounce(50, 250.0, lat, lon, 10.0, 0.0, near.first, near.second))
        val far = LimitAnnouncer.project(first.first, first.second, 90.0, 210.0)
        assertTrue(a.shouldAnnounce(50, 250.0, lat, lon, 10.0, 0.0, far.first, far.second))
    }

    @Test fun resetForgetsTheLastAnnouncement() {
        val a = LimitAnnouncer()
        assertTrue(a.shouldAnnounce(30, 200.0, lat, lon, 10.0, 0.0))
        assertFalse(a.shouldAnnounce(30, 200.0, lat, lon, 10.0, 0.0))
        a.reset()
        assertTrue(a.shouldAnnounce(30, 200.0, lat, lon, 10.0, 0.0))
    }

    @Test fun aNaNChangePositionFallsBackToTheProjection() {
        val a = LimitAnnouncer()
        // Only one of the two given: the projection is used, so the same change projected again matches it.
        assertTrue(a.shouldAnnounce(60, 200.0, lat, lon, 10.0, 0.0, changeLat = 10.0))
        assertFalse(a.shouldAnnounce(60, 200.0, lat, lon, 10.0, 0.0))
    }

    @Test fun theThresholdsAreConstructorParameters() {
        val a = LimitAnnouncer(minM = 100.0, leadS = 5.0, sameChangeM = 50.0)
        assertFalse(a.shouldAnnounce(30, 150.0, lat, lon, 10.0, 0.0))
        assertTrue(a.shouldAnnounce(30, 100.0, lat, lon, 10.0, 0.0))
        // 60 m further on is a new change with a 50 m window.
        val on = LimitAnnouncer.project(lat, lon, 0.0, 60.0)
        assertTrue(a.shouldAnnounce(30, 100.0, on.first, on.second, 10.0, 0.0))
    }

    @Test fun projectionAlongEachBearing() {
        val north = LimitAnnouncer.project(lat, lon, 0.0, 1113.2)
        assertEquals(lat + 0.01, north.first, 1e-9)
        assertEquals(lon, north.second, 1e-9)
        val south = LimitAnnouncer.project(lat, lon, 180.0, 1113.2)
        assertEquals(lat - 0.01, south.first, 1e-9)
        // East at 60°N: a degree of longitude is half as long.
        val east = LimitAnnouncer.project(60.0, 0.0, 90.0, 1113.2)
        assertEquals(60.0, east.first, 1e-9)
        assertEquals(0.02, east.second, 1e-9)
        // Flat earth is close to the great-circle distance over a few hundred metres.
        val ne = LimitAnnouncer.project(lat, lon, 45.0, 400.0)
        assertEquals(400.0, Geo.distanceM(lat, lon, ne.first, ne.second), 2.0)
    }

    @Test fun everyUsualLimitIsSpokenAsAWord() {
        val words = mapOf(10 to "ten", 15 to "fifteen", 20 to "twenty", 30 to "thirty", 40 to "forty", 50 to "fifty", 60 to "sixty", 70 to "seventy")
        for ((mph, w) in words) assertEquals("Speed limit changes to $w ahead", LimitAnnouncer.text(mph))
        assertEquals("Speed limit changes to 5 ahead", LimitAnnouncer.text(5))
        assertEquals("Speed limit changes to 80 ahead", LimitAnnouncer.text(80))
    }
}

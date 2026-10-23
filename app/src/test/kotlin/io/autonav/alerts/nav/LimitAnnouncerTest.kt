// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN.md §8.4a "Limit changes ahead": once per change, within max(300 m, speed × 12 s). */
class LimitAnnouncerTest {
    private val lat = 53.38
    private val lon = -1.47

    @Test
    fun tooFarIsNotAnnounced() {
        val a = LimitAnnouncer()
        // 13 m/s × 12 s = 156 m < 300 m floor; 412 m is beyond it.
        assertFalse(a.shouldAnnounce(30, 412.0, lat, lon, 13.0, 0.0))
    }

    @Test
    fun announcedOnceThenNotAgainOnApproach() {
        val a = LimitAnnouncer()
        assertTrue(a.shouldAnnounce(30, 290.0, lat, lon, 13.0, 0.0))
        // Car has moved 100 m north; the change is now 190 m ahead: the same change, not repeated.
        val (lat2, lon2) = LimitAnnouncer.project(lat, lon, 0.0, 100.0)
        assertFalse(a.shouldAnnounce(30, 190.0, lat2, lon2, 13.0, 0.0))
        assertFalse(a.shouldAnnounce(30, 20.0, LimitAnnouncer.project(lat, lon, 0.0, 270.0).first, lon2, 13.0, 0.0))
    }

    @Test
    fun aLaterChangeToTheSameLimitIsAnnouncedAgain() {
        val a = LimitAnnouncer()
        assertTrue(a.shouldAnnounce(30, 200.0, lat, lon, 13.0, 0.0))
        // 2 km on, another 30 begins 200 m ahead: a different change.
        val (lat2, lon2) = LimitAnnouncer.project(lat, lon, 0.0, 2_000.0)
        assertTrue(a.shouldAnnounce(30, 200.0, lat2, lon2, 13.0, 0.0))
    }

    @Test
    fun leadGrowsWithSpeed() {
        val a = LimitAnnouncer()
        // 30 m/s (67 mph) × 12 s = 360 m, so 350 m is inside the lead.
        assertTrue(a.shouldAnnounce(50, 350.0, lat, lon, 30.0, 90.0))
    }

    @Test
    fun noHeadingNoAnnouncement() {
        assertFalse(LimitAnnouncer().shouldAnnounce(30, 50.0, lat, lon, 10.0, null))
    }

    @Test
    fun spokenText() {
        assertEquals("Speed limit changes to thirty ahead", LimitAnnouncer.text(30))
        assertEquals("Speed limit changes to 25 ahead", LimitAnnouncer.text(25))
    }

    @Test
    fun sameChangeAfterATurnIsNotSaidAgain() {
        val a = LimitAnnouncer()
        val change = LimitAnnouncer.project(lat, lon, 0.0, 300.0)
        assertTrue(a.shouldAnnounce(50, 300.0, lat, lon, 16.0, 0.0, change.first, change.second))
        // 3 s later the car has turned 90°: projecting along the heading would land 350 m away.
        val car = LimitAnnouncer.project(lat, lon, 0.0, 50.0)
        assertFalse(a.shouldAnnounce(50, 250.0, car.first, car.second, 16.0, 90.0, change.first, change.second))
    }
}

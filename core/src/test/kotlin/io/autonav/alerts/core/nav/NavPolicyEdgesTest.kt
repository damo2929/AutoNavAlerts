// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edges of the EV re-ask policy (DESIGN.md §8.3a), the follow watchdog (§8 "Follow watchdog")
 * and the guidance state (§9.3) not covered by their own tests: the order the re-ask reasons are
 * checked in, the watchdog's fix-age boundary and which skips reset its count, and what a new
 * preview or Go keeps.
 */
class NavPolicyEdgesTest {
    @Test
    fun reaskReasonsAreCheckedInOrder() {
        val p = EcoReaskPolicy()
        // Started but not efficient beats the grace; the grace beats the reroute check.
        assertEquals(EcoReaskPolicy.Decision.Skip("fastest selected"), p.decide(0, 0, null, true, false, false))
        assertEquals(EcoReaskPolicy.Decision.Skip("guidance not started"), p.decide(100_000, null, null, true, true, true))
        assertEquals(EcoReaskPolicy.Decision.Skip("grace (10 s since start)"), p.decide(10_000, 0, null, true, true, false))
        // The once-a-minute gap is checked before the token.
        assertEquals(EcoReaskPolicy.Decision.Skip("asked 0 s ago"), p.decide(100_000, 0, 100_000, true, true, true, "t", "t"))
    }

    @Test
    fun reaskTimesAreConfigurable() {
        val p = EcoReaskPolicy(graceMs = 5_000, minGapMs = 10_000)
        assertEquals(EcoReaskPolicy.Decision.Ask, p.decide(5_000, 0, null, true, true, true))
        assertEquals(EcoReaskPolicy.Decision.Skip("asked 9 s ago"), p.decide(14_999, 0, 5_000, true, true, true))
        assertEquals(EcoReaskPolicy.Decision.Ask, p.decide(15_000, 0, 5_000, true, true, true))
    }

    @Test
    fun aFixTenSecondsOldStillCountsAndOneOlderDoesNot() {
        val w = FollowWatchdog()
        assertEquals(FollowWatchdog.Decision.Refollow(900f), w.check(0, true, false, false, 10_000, 900f))
        assertEquals(FollowWatchdog.Decision.None("SDK fix 10 s old"), w.check(10_000, true, false, false, 10_001, 900f))
    }

    @Test
    fun aStaleOrMissingFixKeepsTheMissCountButPanningOrThePreviewClearsIt() {
        val w = FollowWatchdog()
        w.check(0, true, false, false, 100, 900f)
        w.check(5_000, true, false, false, 100, 900f)
        assertEquals(2, w.misses)
        w.check(6_000, true, false, false, 60_000, 900f)
        w.check(7_000, true, false, false, null, null)
        assertEquals(2, w.misses)
        // Guiding without a frame says nothing either way.
        w.check(8_000, true, true, false, 100, 900f)
        assertEquals(2, w.misses)
        // The third miss rebuilds.
        assertEquals(FollowWatchdog.Decision.Rebuild(900f), w.check(10_000, true, false, false, 100, 900f))
        w.check(15_000, true, false, false, 100, 900f)
        assertEquals(1, w.misses)
        w.check(16_000, false, false, false, 100, 900f)
        assertEquals(0, w.misses)
        w.check(21_000, true, false, false, 100, 900f)
        w.check(22_000, true, false, true, 100, 900f)
        assertEquals(0, w.misses)
    }

    @Test
    fun exactlyTheThresholdIsWithinAndTheRebuildCountIsConfigurable() {
        assertEquals(FollowWatchdog.Decision.None("within 500 m"), FollowWatchdog().check(0, true, false, false, 100, 500f))
        val w = FollowWatchdog(thresholdM = 100.0, missesBeforeRebuild = 1)
        assertEquals(FollowWatchdog.Decision.None("within 100 m"), w.check(0, true, false, false, 100, 99f))
        assertEquals(FollowWatchdog.Decision.Rebuild(101f), w.check(1_000, true, false, false, 100, 101f))
        assertEquals(0, w.misses)
    }

    @Test
    fun aNewPreviewWhileGuidingKeepsTheRouteRunning() {
        val guiding = GuidanceState().previewing(selectedEfficient = true).started(1_000).ecoAsked(70_000).tokenApplied("t1")
        val p = guiding.previewing(choices = 2, selectedIndex = 1, selectedEfficient = false, routeNote = "no EV route")
        assertTrue(p.guiding && p.previewShowing && p.hasDestination)
        assertEquals(1_000L, p.guidanceStartedMs)
        assertEquals(70_000L, p.lastEcoReaskMs)
        assertEquals("no EV route", p.routeNote)
        assertFalse(p.selectedEfficient)
    }

    @Test
    fun goAgainRestartsTheReaskClockButKeepsTheAppliedToken() {
        val s = GuidanceState().previewing().started(1_000).ecoAsked(70_000).tokenApplied("t1").rerouting().routeChanged().arrived()
        assertTrue(s.arrived && s.sdkRerouteCompleted)
        val again = s.previewing().started(90_000)
        assertFalse(again.arrived)
        assertEquals(90_000L, again.guidanceStartedMs)
        assertEquals(null, again.lastEcoReaskMs)
        assertFalse(again.sdkRerouteCompleted)
        assertEquals("t1", again.lastAppliedToken)
    }
}

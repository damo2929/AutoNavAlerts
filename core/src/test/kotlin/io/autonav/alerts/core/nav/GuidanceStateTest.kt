// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a stop and an abandoned preview reset (DESIGN.md §9.3, §8). */
class GuidanceStateTest {
    private val running = GuidanceState()
        .previewing(choices = 2, selectedIndex = 1, selectedEfficient = true, routeNote = "heavy +12 min")
        .started(10_000)
        .rerouting().routeChanged()
        .ecoAsked(80_000)
        .tokenApplied("tok1")
        .arrived()

    @Test
    fun stopResetsEverything() {
        assertTrue(running.guiding && running.arrived && running.hasDestination)
        assertEquals(GuidanceState(), running.stopped())
        val s = running.stopped()
        assertFalse(s.guiding); assertFalse(s.arrived); assertFalse(s.hasDestination)
        assertEquals(0, s.choices); assertEquals(null, s.selectedIndex); assertFalse(s.selectedEfficient)
        assertEquals(null, s.routeNote); assertEquals(null, s.guidanceStartedMs)
        assertEquals(null, s.lastEcoReaskMs); assertEquals(null, s.lastAppliedToken)
        assertFalse(s.sdkRerouteCompleted)
    }

    @Test
    fun abandoningThePreviewWhileGuidingIsANoOp() {
        val guidingWithPreviewUp = running.copy(previewShowing = true)
        val after = guidingWithPreviewUp.previewAbandoned()
        assertEquals(running.copy(previewShowing = false), after)
        assertTrue(after.guiding && after.hasDestination)
        assertEquals("tok1", after.lastAppliedToken)
    }

    @Test
    fun abandoningThePreviewBeforeGoClearsTheDestination() {
        val previewing = GuidanceState().previewing(choices = 2, selectedIndex = 0)
        assertTrue(previewing.previewShowing && previewing.hasDestination)
        assertEquals(GuidanceState(), previewing.previewAbandoned())
    }

    @Test
    fun startingResetsTheReaskClockAndRerouteFlag() {
        val s = GuidanceState().previewing(selectedEfficient = true).started(5_000)
        assertTrue(s.guiding)
        assertFalse(s.previewShowing)
        assertEquals(5_000L, s.guidanceStartedMs)
        assertFalse(s.sdkRerouteCompleted)
        assertFalse(s.rerouting().sdkRerouteCompleted)
        assertTrue(s.rerouting().routeChanged().sdkRerouteCompleted)
        // A route change while not guiding (the preview picking a route) doesn't count as a completed reroute.
        assertFalse(GuidanceState().previewing().routeChanged().sdkRerouteCompleted)
    }
}

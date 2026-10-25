// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.nav.GuidancePrompter.Destination
import io.autonav.alerts.core.nav.GuidancePrompter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The words and the distances of the directions spoken in the alerts' voice (DESIGN.md §12.3
 * "Directions in our voice"): instruction clean-up, long-step and rounded-down distances in both
 * units, the destination wording, the clamps of the prepare and now distances, the prepare skipped
 * just before the now, and the staleness each prompt carries for the speech queue.
 */
class GuidancePrompterTextTest {
    @Test
    fun roadNamesJoinedBySlashesReadAsPausesAndSpacesAreTidied() {
        assertEquals("Main Rd, A61", GuidancePrompter.clean("Main Rd/A61"))
        assertEquals("Turn right onto A, B, C", GuidancePrompter.clean("  Turn  right\tonto A / B/ C "))
        assertEquals("Keep left", GuidancePrompter.clean("Keep left"))
    }

    @Test
    fun longDistancesRoundToTheNearestWholeOneAndNeverZero() {
        assertEquals("3 miles", GuidancePrompter.longDistance(3 * 1_609.344, true))
        assertEquals("1 mile", GuidancePrompter.longDistance(1_609.344, true))
        assertEquals("1 mile", GuidancePrompter.longDistance(500.0, true))
        assertEquals("1 kilometre", GuidancePrompter.longDistance(1_000.0, false))
        assertEquals("3 kilometres", GuidancePrompter.longDistance(2_500.0, false))
        assertEquals("5 kilometres", GuidancePrompter.longDistance(5_400.0, false))
    }

    @Test
    fun spokenDistancesStartAtAHundredAndStopAtTwo() {
        assertEquals("100 yards", GuidancePrompter.spokenDistance(91.44, true)!!.second)
        assertNull(GuidancePrompter.spokenDistance(91.43, true))
        assertEquals("half a mile", GuidancePrompter.spokenDistance(1_000.0, true)!!.second)
        assertEquals("2 miles", GuidancePrompter.spokenDistance(10_000.0, true)!!.second)
        assertNull(GuidancePrompter.spokenDistance(99.9, false))
        assertEquals(100.0 to "100 metres", GuidancePrompter.spokenDistance(100.0, false))
        assertEquals(500.0 to "500 metres", GuidancePrompter.spokenDistance(999.0, false))
        assertEquals("2 kilometres", GuidancePrompter.spokenDistance(5_000.0, false)!!.second)
    }

    @Test
    fun destinationWordingNeedsNoInstruction() {
        val ahead = GuidancePrompter().onUpdate(9, null, 30.0, 10.0, false, destination = Destination.AHEAD)!!
        assertEquals("Your destination is ahead", ahead.text)
        assertEquals(Kind.NOW, ahead.kind)
        val right = GuidancePrompter().onUpdate(9, "", 30.0, 10.0, false, destination = Destination.RIGHT)!!
        assertEquals("Your destination is on the right", right.text)
    }

    @Test
    fun nothingWithoutAnInstructionOrWithANegativeDistance() {
        assertNull(GuidancePrompter().onUpdate(1, null, 50.0, 10.0, true))
        assertNull(GuidancePrompter().onUpdate(1, "   ", 50.0, 10.0, true))
        assertNull(GuidancePrompter().onUpdate(1, "Turn left", -1.0, 10.0, true))
    }

    @Test
    fun standingStillUsesTheMinimumDistances() {
        // At 0 m/s: prepare at 150 m, now at 40 m.
        val p = GuidancePrompter()
        assertNull(p.onUpdate(2, "Turn left", 151.0, 0.0, true))
        val prepare = p.onUpdate(2, "Turn left", 150.0, 0.0, true)!!
        assertEquals("In 100 yards, turn left", prepare.text)
        assertEquals(40.0, prepare.staleBelowM!!, 0.0)
        assertNull(p.onUpdate(2, "Turn left", 41.0, 0.0, true))
        val now = p.onUpdate(2, "Turn left", 40.0, 0.0, true)!!
        assertEquals(Kind.NOW, now.kind)
        assertNull(now.staleBelowM)
    }

    @Test
    fun aPrepareJustBeforeTheNowIsSkipped() {
        // 30 mph: now at 67 m; a step that starts at 90 m (under 1.5 × 67) gets the "now" only.
        val p = GuidancePrompter()
        assertNull(p.onUpdate(4, "Turn left", 90.0, 13.4, true))
        assertNull(p.onUpdate(4, "Turn left", 80.0, 13.4, true))
        assertEquals(Kind.NOW, p.onUpdate(4, "Turn left", 60.0, 13.4, true)!!.kind)
        // Under 100 yards but outside 1.5 × now: nothing worth saying, so no prepare either.
        val q = GuidancePrompter()
        assertNull(q.onUpdate(5, "Turn left", 80.0, 0.0, true))
        assertEquals(Kind.NOW, q.onUpdate(5, "Turn left", 40.0, 0.0, true)!!.kind)
    }

    @Test
    fun theDistancesAreClampedAtHighSpeed() {
        // 100 m/s: prepare capped at 2 km, now at 300 m.
        val p = GuidancePrompter()
        assertNull(p.onUpdate(1, "Exit", 2_001.0, 100.0, false))
        assertEquals("In 2 kilometres, exit", p.onUpdate(1, "Exit", 2_000.0, 100.0, false)!!.text)
        assertNull(p.onUpdate(1, "Exit", 301.0, 100.0, false))
        assertEquals(300.0, p.onUpdate(1, "Exit", 300.0, 100.0, false)!!.distanceM, 0.0)
    }

    @Test
    fun continueIsOnlyForAStepFirstSeenFarOutAndCarriesThePrepareDistance() {
        val p = GuidancePrompter()
        val c = p.onUpdate(1, "Turn right", 8_000.0, 31.3, false)!!
        assertEquals("Continue for 8 kilometres", c.text)
        assertEquals(31.3 * 30.0, c.staleBelowM!!, 1e-9)
        // First seen under 3 km: no "continue", however long the wait to the prepare.
        assertNull(GuidancePrompter().onUpdate(2, "Turn right", 2_500.0, 31.3, false))
    }

    @Test
    fun aChangedInstructionOrAResetStartsTheStepAgain() {
        val p = GuidancePrompter()
        assertEquals(Kind.NOW, p.onUpdate(3, "Turn left", 30.0, 13.4, true)!!.kind)
        assertNull(p.onUpdate(3, "Turn left", 20.0, 13.4, true))
        // The SDK re-words the same step: a new step as far as speech goes.
        assertEquals("Turn left onto High St", p.onUpdate(3, "Turn left onto High St", 20.0, 13.4, true)!!.text)
        p.reset()
        assertEquals(Kind.NOW, p.onUpdate(3, "Turn left onto High St", 10.0, 13.4, true)!!.kind)
        assertEquals(4, p.onUpdate(4, "Keep right", 10.0, 13.4, true)!!.step)
    }

    @Test
    fun onlyAnOrdinaryFirstWordIsLowerCasedAfterTheDistance() {
        assertEquals("In 300 metres, keep left", firstPrepare("Keep left"))
        assertEquals("In 300 metres, SLIGHT left", firstPrepare("SLIGHT left"))
    }

    private fun firstPrepare(instruction: String): String =
        GuidancePrompter().let { p -> generateSequence(1_000.0) { (it - 10).takeIf { d -> d >= 0 } }.firstNotNullOf { p.onUpdate(1, instruction, it, 13.4, false) } }.text
}

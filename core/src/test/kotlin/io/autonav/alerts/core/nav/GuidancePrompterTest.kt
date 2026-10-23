// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.nav.GuidancePrompter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidancePrompterTest {
    private val turn = "Turn right onto Main Rd/A61"

    /** Drive towards the turn at [mps], one update every [stepM], and collect what is said. */
    private fun approach(p: GuidancePrompter, fromM: Double, mps: Double, imperial: Boolean = true, step: Int = 3, stepM: Double = 10.0) =
        generateSequence(fromM) { (it - stepM).takeIf { d -> d >= 0 } }
            .mapNotNull { d -> p.onUpdate(step, turn, d, mps, imperial) }.toList()

    @Test
    fun townApproachSaysPrepareThenNow() {
        // 30 mph (13.4 m/s): prepare at 402 m → "300 yards", now at 67 m.
        val said = approach(GuidancePrompter(), 1_000.0, 13.4)
        assertEquals(listOf(Kind.PREPARE, Kind.NOW), said.map { it.kind })
        assertEquals("In 300 yards, turn right onto Main Rd, A61", said[0].text)
        assertEquals("Turn right onto Main Rd, A61", said[1].text)
        assertEquals(67.0, said[1].distanceM, 10.0)
    }

    @Test
    fun motorwaySpeedPreparesFurtherOut() {
        // 70 mph (31.3 m/s): prepare at 939 m → "half a mile"; now at 156 m.
        val said = approach(GuidancePrompter(), 1_500.0, 31.3)
        assertEquals("In half a mile, turn right onto Main Rd, A61", said[0].text)
        assertEquals(Kind.NOW, said[1].kind)
    }

    @Test
    fun metricDistances() {
        val said = approach(GuidancePrompter(), 1_000.0, 13.4, imperial = false)
        assertEquals("In 300 metres, turn right onto Main Rd, A61", said[0].text)
    }

    @Test
    fun eachPromptOncePerStepAndNewStepStartsAgain() {
        val p = GuidancePrompter()
        assertEquals(2, approach(p, 1_000.0, 13.4).size)
        // Same step again (GPS jitter, repeated updates): nothing more.
        assertNull(p.onUpdate(3, turn, 30.0, 13.4, true))
        // Next step.
        val next = generateSequence(800.0) { (it - 10).takeIf { d -> d >= 0 } }.mapNotNull { p.onUpdate(4, "Turn left onto High St", it, 13.4, true) }.toList()
        assertEquals(listOf(Kind.PREPARE, Kind.NOW), next.map { it.kind })
    }

    @Test
    fun shortStepSkipsPrepare() {
        // The step starts 60 m from the turn: only "now".
        val said = approach(GuidancePrompter(), 60.0, 13.4)
        assertEquals(listOf(Kind.NOW), said.map { it.kind })
    }

    @Test
    fun longStepSaysContinueFirst() {
        val p = GuidancePrompter()
        val c = p.onUpdate(1, turn, 8_000.0, 31.3, true)!!
        assertEquals(Kind.CONTINUE, c.kind)
        assertEquals("Continue for 5 miles", c.text)
        assertNull(p.onUpdate(1, turn, 7_990.0, 31.3, true))
    }

    @Test
    fun nothingWhileRerouting() {
        assertNull(GuidancePrompter().onUpdate(3, turn, 50.0, 13.4, true, rerouting = true))
    }

    @Test
    fun spokenDistanceRoundsDown() {
        assertEquals("200 yards", GuidancePrompter.spokenDistance(250.0, true)!!.second)
        assertEquals("a quarter of a mile", GuidancePrompter.spokenDistance(500.0, true)!!.second)
        assertNull(GuidancePrompter.spokenDistance(80.0, true))
        assertEquals("1 kilometre", GuidancePrompter.spokenDistance(1_500.0, false)!!.second)
    }

    @Test
    fun lastStepIsWordedAsTheDestination() {
        val p = GuidancePrompter()
        val said = generateSequence(800.0) { (it - 10).takeIf { d -> d >= 0 } }
            .mapNotNull { p.onUpdate(5, "Destination", it, 13.4, true, destination = GuidancePrompter.Destination.LEFT) }.toList()
        assertEquals(listOf("In 300 yards, your destination is on the left", "Your destination is on the left"), said.map { it.text })
    }
}

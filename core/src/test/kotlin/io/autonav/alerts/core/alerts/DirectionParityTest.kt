// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The direction rule (DESIGN.md §11 step 3, hard rule 1) is one function: the trace's
 * [AlertTraceLog.mismatchDeg] and the engine's test agree over every bearing, heading and
 * direction type, with and without the bearing-to-camera leg. [reference] is the engine's rule as
 * it was written before the two were unified (AlertEngine.directionMismatch, 2026-10-25), kept
 * here verbatim so a change to the shared function is caught against the original.
 */
class DirectionParityTest {
    private fun reference(bearing: Double, dirType: DirectionType, heading: Double, toPoi: Double, betaAllowed: Boolean): Double {
        fun miss(x: Double): Double {
            val h = Geo.absAngleDiffDeg(x, heading)
            return if (betaAllowed) minOf(h, Geo.absAngleDiffDeg(x, toPoi)) else h
        }
        return when (dirType) {
            DirectionType.ANY -> 0.0
            DirectionType.ONE_WAY -> miss(bearing)
            DirectionType.BOTH -> minOf(miss(bearing), miss(bearing + 180.0))
        }
    }

    private val grid = (0 until 360 step 15).map { it.toDouble() }

    @Test fun traceMismatchMatchesTheEngineRuleWithoutTheBearingToCameraLeg() {
        for (dir in DirectionType.entries) for (b in grid) for (h in grid) {
            val expected = reference(b, dir, h, toPoi = h + 90.0, betaAllowed = false)
            assertEquals("bearing=$b dir=$dir heading=$h", expected, AlertTraceLog.mismatchDeg(b.toFloat(), dir, h)!!, 0.0)
        }
    }

    @Test fun sharedRuleMatchesTheEngineRuleWithTheBearingToCameraLeg() {
        for (dir in DirectionType.entries) for (b in grid) for (h in grid) for (toPoi in grid) {
            val expected = reference(b, dir, h, toPoi, betaAllowed = true)
            assertEquals("bearing=$b dir=$dir heading=$h toPoi=$toPoi", expected, directionMismatchDeg(b, dir, h, toPoi), 0.0)
            val alone = reference(b, dir, h, toPoi, betaAllowed = false)
            assertEquals("bearing=$b dir=$dir heading=$h", alone, directionMismatchDeg(b, dir, h, null), 0.0)
        }
    }

    @Test fun unknownsGiveNull() {
        assertEquals(null, AlertTraceLog.mismatchDeg(null, DirectionType.ONE_WAY, 90.0))
        assertEquals(null, AlertTraceLog.mismatchDeg(90f, DirectionType.ONE_WAY, null))
    }
}

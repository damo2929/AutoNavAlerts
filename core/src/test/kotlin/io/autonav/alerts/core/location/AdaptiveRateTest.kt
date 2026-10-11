// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveRateTest {
    private val curve = RateCurve()

    @Test
    fun belowTenMphIsAlwaysOneHz() {
        assertEquals(1.0, curve.targetHz(0.0), 0.0)
        assertEquals(1.0, curve.targetHz(9.9), 0.0)
        assertEquals(1.0, curve.targetHz(10.0), 0.0)
        assertEquals(1, curve.stepHz(5.0))
    }

    @Test
    fun linearBetweenTenAndFifty() {
        assertEquals(3.25, curve.targetHz(20.0), 1e-9)
        assertEquals(5.5, curve.targetHz(30.0), 1e-9)
        assertEquals(7.75, curve.targetHz(40.0), 1e-9)
        assertEquals(6, curve.stepHz(30.0)) // 5.5 rounds half up
    }

    @Test
    fun cappedAtTenHzFromFiftyMph() {
        assertEquals(10.0, curve.targetHz(50.0), 0.0)
        assertEquals(10, curve.stepHz(80.0))
        assertEquals(100L, RateCurve.intervalMs(10))
        assertEquals(1000L, RateCurve.intervalMs(1))
    }

    @Test
    fun newStepMustHoldForTwoSeconds() {
        val c = AdaptiveRateController(curve)
        assertNull(c.onSpeed(60.0, 0).newHz)
        assertNull(c.onSpeed(60.0, 1_999).newHz)
        assertEquals(10, c.onSpeed(60.0, 2_000).newHz)
        assertEquals(10, c.requestedHz)
        assertNull(c.onSpeed(60.0, 3_000).newHz)
    }

    @Test
    fun jitterAroundAStepBoundaryDoesNotReRequest() {
        val c = AdaptiveRateController(curve)
        // Settle at 30 mph → 6 Hz
        c.onSpeed(30.0, 0); c.onSpeed(30.0, 2_000)
        assertEquals(6, c.requestedHz)
        // 5.5 Hz boundary sits at 30 mph; jitter ±1 mph flips between steps 5 and 6.
        var t = 2_000L
        repeat(10) {
            t += 500
            assertNull(c.onSpeed(if (it % 2 == 0) 29.0 else 31.0, t).newHz)
        }
        assertEquals(6, c.requestedHz)
    }

    @Test
    fun steadyChangeProducesExactlyOneRequest() {
        val c = AdaptiveRateController(curve)
        var count = 0
        var t = 0L
        repeat(20) {
            if (c.onSpeed(45.0, t).newHz != null) count++
            t += 250
        }
        assertEquals(1, count)
    }

    @Test
    fun chipThatCannotKeepUpGetsCapped() {
        val c = AdaptiveRateController(curve)
        c.onSpeed(70.0, 0); c.onSpeed(70.0, 2_000)
        assertEquals(10, c.requestedHz)
        assertNull(c.onMeasuredRate(1.0, 3_000).newHz)
        assertEquals(1, c.onMeasuredRate(1.0, 63_000).newHz)
        assertEquals(1, c.chipCapHz)
        // Still fast: stays capped. Slowing below the cap clears it, so a later speed-up probes again.
        assertNull(c.onSpeed(70.0, 70_000).newHz)
        c.onSpeed(5.0, 80_000)
        assertNull(c.chipCapHz)
        c.onSpeed(70.0, 90_000)
        assertEquals(10, c.onSpeed(70.0, 92_000).newHz)
    }

    @Test
    fun smootherUsesMedianOfThree() {
        val s = SpeedSmoother()
        assertEquals(10.0, s.add(10.0), 0.0)
        assertEquals(30.0, s.add(30.0), 0.0) // two samples: the upper one
        assertEquals(20.0, s.add(20.0), 0.0)
        assertEquals(30.0, s.add(100.0), 0.0) // [30, 20, 100] → 30
    }
}

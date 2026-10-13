// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.location

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedSmootherTest {
    @Test
    fun smootherUsesMedianOfThree() {
        val s = SpeedSmoother()
        assertEquals(10.0, s.add(10.0), 0.0)
        assertEquals(30.0, s.add(30.0), 0.0) // two samples: the upper one
        assertEquals(20.0, s.add(20.0), 0.0)
        assertEquals(30.0, s.add(100.0), 0.0) // [30, 20, 100] → 30
    }
}

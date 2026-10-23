// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedUnitTest {
    @Test
    fun sameUnitIsUnchanged() {
        assertEquals(30, SpeedUnit.MPH.toDisplay(30, imperial = true))
        assertEquals(50, SpeedUnit.KMH.toDisplay(50, imperial = false))
    }

    @Test
    fun mphCameraOnMetricDisplay() {
        assertEquals(48, SpeedUnit.MPH.toDisplay(30, imperial = false))
        assertEquals(113, SpeedUnit.MPH.toDisplay(70, imperial = false))
    }

    @Test
    fun kmhCameraOnImperialDisplay() {
        assertEquals(31, SpeedUnit.KMH.toDisplay(50, imperial = true))
        assertEquals(62, SpeedUnit.KMH.toDisplay(100, imperial = true))
    }
}

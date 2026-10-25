// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import org.junit.Assert.assertEquals
import org.junit.Test

class LimitSnapTest {
    @Test
    fun mphSnapsToUkLimits() {
        assertEquals(40, LimitSnap.snap(43.9, imperial = true))
        assertEquals(40, LimitSnap.snap(45.0, imperial = true))
        assertEquals(30, LimitSnap.snap(35.0, imperial = true))
        assertEquals(70, LimitSnap.snap(78.0, imperial = true))
        assertEquals(20, LimitSnap.snap(12.0, imperial = true))
    }

    @Test
    fun kmhSnapsToTens() {
        assertEquals(50, LimitSnap.snap(52.0, imperial = false))
        assertEquals(110, LimitSnap.snap(113.0, imperial = false))
        assertEquals(130, LimitSnap.snap(150.0, imperial = false))
    }

    @Test
    fun tiesGoToTheLowerLimit() {
        assertEquals(20, LimitSnap.snap(25.0, imperial = true))
        assertEquals(60, LimitSnap.snap(65.0, imperial = true))
        assertEquals(40, LimitSnap.snap(45.0, imperial = false))
        assertEquals(120, LimitSnap.snap(125.0, imperial = false))
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.nav.TapTargets.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A tap anywhere on a button, or just off it, counts (DESIGN.md §8 "Map buttons", 2026-11-01). */
class TapTargetsTest {
    // The DHU's right-hand column at 160 dpi: satellite pill, zoom pill (10 px gap), recenter (10 px gap).
    private val satellite = Box(1, 1650f, 104f, 1800f, 156f)
    private val zoom = Box(2, 1744f, 166f, 1800f, 278f)
    private val recenter = Box(3, 1744f, 288f, 1800f, 344f)
    private val column = listOf(satellite, zoom, recenter)
    private val slop = 16f

    @Test
    fun anyPixelInTheBoxCountsNotOnlyTheCentre() {
        assertEquals(2, TapTargets.pick(1744f, 166f, column, slop))
        assertEquals(2, TapTargets.pick(1800f, 278f, column, slop))
        assertEquals(3, TapTargets.pick(1745f, 343f, column, slop))
        assertEquals(1, TapTargets.pick(1651f, 155f, column, slop))
    }

    @Test
    fun justOffTheEdgeStillCounts() {
        assertEquals(3, TapTargets.pick(1730f, 316f, column, slop))
        assertEquals(3, TapTargets.pick(1772f, 358f, column, slop))
        assertEquals(1, TapTargets.pick(1640f, 130f, column, slop))
    }

    @Test
    fun theGapGoesToTheNearestButton() {
        // 3 px above the recenter's top, 7 px below the pill's bottom.
        assertEquals(3, TapTargets.pick(1772f, 285f, column, slop))
        // 2 px below the pill's bottom.
        assertEquals(2, TapTargets.pick(1772f, 280f, column, slop))
    }

    @Test
    fun theMapItselfIsNotAButton() {
        assertNull(TapTargets.pick(1700f, 300f, column, slop))
        assertNull(TapTargets.pick(900f, 500f, column, slop))
        assertNull(TapTargets.pick(1772f, 380f, column, slop))
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmSpeedLimitsTest {
    @Test
    fun readsMaxspeedValues() {
        assertEquals(30.0, OsmSpeedLimits.mph("30 mph")!!, 0.01)
        assertEquals(20.0, OsmSpeedLimits.mph("20mph")!!, 0.01)
        assertEquals(31.07, OsmSpeedLimits.mph("50")!!, 0.01)
        assertEquals(60.0, OsmSpeedLimits.mph("GB:nsl_single")!!, 0.01)
        assertEquals(70.0, OsmSpeedLimits.mph("GB:nsl_dual")!!, 0.01)
        assertEquals(70.0, OsmSpeedLimits.mph("national", "motorway")!!, 0.01)
        assertNull(OsmSpeedLimits.mph("national", "primary"))
        assertNull(OsmSpeedLimits.mph("signals"))
        assertNull(OsmSpeedLimits.mph("none"))
        assertEquals(40.0, OsmSpeedLimits.mph("40 mph;50 mph")!!, 0.01)
    }

    @Test
    fun forwardAndBackwardOverrideMaxspeed() {
        assertEquals(Triple(30.0, null, null), OsmSpeedLimits.limits(mapOf("maxspeed" to "30 mph")))
        val (both, fwd, back) = OsmSpeedLimits.limits(mapOf("maxspeed" to "40 mph", "maxspeed:forward" to "30 mph"))
        assertNull(both)
        assertEquals(30.0, fwd!!, 0.01)
        assertEquals(40.0, back!!, 0.01)
    }

    @Test
    fun tilesRoundTripAndCoverARadius() {
        val k = OsmSpeedLimits.tileKey(53.3785, -1.4634)
        val box = OsmSpeedLimits.tileBox(k)
        assertTrue(box[0] <= 53.3785 && 53.3785 < box[2] && box[1] <= -1.4634 && -1.4634 < box[3])
        assertEquals(9, OsmSpeedLimits.around(53.3785, -1.4634).toSet().size)
        val near = OsmSpeedLimits.within(53.3785, -1.4634, 25.0)
        assertEquals(k, near.first())
        assertTrue(near.size in 20..60)
    }

    // A north-south road at lon 0 with a 30 limit, and an east-west side road at lat 53.0 with 20.
    private val main = OsmSpeedLimits.segments(listOf(52.99 to 0.0, 53.01 to 0.0), mapOf("maxspeed" to "30 mph"))
    private val side = OsmSpeedLimits.segments(listOf(53.0 to -0.01, 53.0 to 0.01), mapOf("maxspeed" to "20 mph"))

    @Test
    fun matchesTheRoadAlongTheHeadingNotTheSideRoad() {
        val all = main + side
        assertEquals(30.0, OsmSpeedLimits.limitAt(all, 53.0, 0.00005, 0.0)!!, 0.01)
        assertEquals(30.0, OsmSpeedLimits.limitAt(all, 53.0, 0.00005, 180.0)!!, 0.01)
        assertEquals(20.0, OsmSpeedLimits.limitAt(all, 53.0, 0.00005, 90.0)!!, 0.01)
        assertNull(OsmSpeedLimits.limitAt(all, 53.0, 0.00005, 45.0))
        assertNull(OsmSpeedLimits.limitAt(all, 53.0, 0.001, 0.0)) // ~67 m away
        assertNull(OsmSpeedLimits.limitAt(all, 53.0, 0.0, null))
    }

    @Test
    fun oneWayLimitsOnlyMatchTheirDirection() {
        val segs = OsmSpeedLimits.segments(listOf(52.99 to 0.0, 53.01 to 0.0), mapOf("maxspeed" to "40 mph", "maxspeed:forward" to "30 mph"))
        assertEquals(30.0, OsmSpeedLimits.limitAt(segs, 53.0, 0.0, 0.0)!!, 0.01)
        assertEquals(40.0, OsmSpeedLimits.limitAt(segs, 53.0, 0.0, 180.0)!!, 0.01)
    }
}

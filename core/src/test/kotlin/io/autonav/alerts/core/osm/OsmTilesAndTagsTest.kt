// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0.1° tiles the limit file is indexed by and the tag reading of DESIGN.md §8.4a: keys either
 * side of the equator and the prime meridian stay distinct, [OsmSpeedLimits.around] is the 3×3
 * block in row order, [OsmSpeedLimits.within] is nearest first, the 5–90 mph window, and the
 * panel's unit conversion (§8.4 "Units").
 */
class OsmTilesAndTagsTest {
    @Test
    fun keysPackTheRowHighAndTheColumnLowEvenWhenNegative() {
        val k = OsmSpeedLimits.tileKey(53.35, -1.45)
        assertEquals(533, (k shr 32).toInt())
        assertEquals(-15, k.toInt())
        // The four tiles round (0, 0) are four different keys: a negative column never bleeds into the row.
        val quad = listOf(0.05 to 0.05, 0.05 to -0.05, -0.05 to 0.05, -0.05 to -0.05).map { (a, b) -> OsmSpeedLimits.tileKey(a, b) }
        assertEquals(listOf(0L, 0xffffffffL, -0x100000000L, -1L), quad)
        // Floor, not truncation: just below zero is the tile below.
        assertEquals(0L, OsmSpeedLimits.tileKey(0.0, 0.0))
        assertEquals(-1, (OsmSpeedLimits.tileKey(-1e-9, 0.0) shr 32).toInt())
    }

    @Test
    fun aroundIsTheThreeByThreeBlockRowByRowWithTheCarInTheMiddle() {
        val a = OsmSpeedLimits.around(53.35, -1.45)
        assertEquals(OsmSpeedLimits.tileKey(53.35, -1.45), a[4])
        assertEquals(OsmSpeedLimits.tileKey(53.25, -1.55), a[0])
        assertEquals(OsmSpeedLimits.tileKey(53.25, -1.35), a[2])
        assertEquals(OsmSpeedLimits.tileKey(53.45, -1.35), a[8])
        // Across the prime meridian too.
        assertTrue(OsmSpeedLimits.tileKey(0.05, 0.05) in OsmSpeedLimits.around(0.05, -0.05))
    }

    @Test
    fun withinIsSortedNearestFirstAndCanBeEmpty() {
        val lat = 53.3785; val lon = -1.4634
        val near = OsmSpeedLimits.within(lat, lon, 25.0)
        val d = near.map { k ->
            val r = (k shr 32).toInt(); val c = k.toInt()
            Geo.distanceM(lat, lon, (r + 0.5) * OsmSpeedLimits.TILE_DEG, (c + 0.5) * OsmSpeedLimits.TILE_DEG)
        }
        assertEquals(d.sorted(), d)
        assertTrue(d.all { it <= 25_000.0 })
        assertEquals(near.toSet().size, near.size)
        // At a tile's centre a small radius is that tile alone; at a corner it reaches no centre.
        assertEquals(listOf(OsmSpeedLimits.tileKey(53.35, -1.45)), OsmSpeedLimits.within(53.35, -1.45, 0.5))
        assertEquals(emptyList<Long>(), OsmSpeedLimits.within(53.30001, -1.40001, 1.0))
    }

    @Test
    fun tagValuesOutsideTheRoadWindowAreNotLimits() {
        assertEquals(5.0, OsmSpeedLimits.mph("5 mph")!!, 0.0)
        assertNull(OsmSpeedLimits.mph("4 mph"))
        assertEquals(90.0, OsmSpeedLimits.mph("90 mph")!!, 0.0)
        assertNull(OsmSpeedLimits.mph("95 mph"))
        assertEquals(89.48, OsmSpeedLimits.mph("144")!!, 0.01)
        assertNull(OsmSpeedLimits.mph("145"))
        assertNull(OsmSpeedLimits.mph(null))
        assertNull(OsmSpeedLimits.mph(""))
    }

    @Test
    fun ukNationalLimitTags() {
        assertEquals(70.0, OsmSpeedLimits.mph("GB:motorway")!!, 0.0)
        assertEquals(70.0, OsmSpeedLimits.mph("national", "motorway_link")!!, 0.0)
        assertNull(OsmSpeedLimits.mph("national"))
        assertEquals(60.0, OsmSpeedLimits.mph("  gb:NSL_SINGLE ; 30 mph")!!, 0.0)
    }

    @Test
    fun thePanelShowsOsmLimitsInItsOwnUnits() {
        assertEquals(30, OsmSpeedLimits.display(30.0, imperial = true))
        assertEquals(48, OsmSpeedLimits.display(30.0, imperial = false))
        assertEquals(113, OsmSpeedLimits.display(70.0, imperial = false))
        // A km/h tag read to mph shows its own number again on a metric panel.
        assertEquals(50, OsmSpeedLimits.display(OsmSpeedLimits.mph("50")!!, imperial = false))
        assertEquals(31, OsmSpeedLimits.display(OsmSpeedLimits.mph("50")!!, imperial = true))
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping Northern Ireland's roads out of Geofabrik's Ireland file (DESIGN.md §8.4a "Northern
 * Ireland from the Ireland file"): the bounding-box reject, places near the border on each side,
 * the road judged by its middle point, and the UK 5 mph multiple.
 */
class NorthernIrelandEdgeTest {
    @Test
    fun outsideTheBoxIsRejectedBeforeTheRing() {
        assertFalse(NorthernIreland.contains(53.86, -6.5))   // just south of the box
        assertFalse(NorthernIreland.contains(55.46, -6.5))   // just north
        assertFalse(NorthernIreland.contains(54.6, -8.19))   // just west
        assertFalse(NorthernIreland.contains(54.6, -5.06))   // just east
        assertFalse(NorthernIreland.contains(0.0, 0.0))
    }

    @Test
    fun placesEitherSideOfTheBorder() {
        assertTrue(NorthernIreland.contains(54.350, -6.654))  // Armagh
        assertTrue(NorthernIreland.contains(54.600, -7.300))  // Omagh
        assertTrue(NorthernIreland.contains(55.204, -6.241))  // Ballycastle
        assertTrue(NorthernIreland.contains(54.105, -6.260))  // Warrenpoint-side of Carlingford Lough
        assertFalse(NorthernIreland.contains(54.249, -6.968)) // Monaghan
        assertFalse(NorthernIreland.contains(53.977, -6.718)) // Carrickmacross
        assertFalse(NorthernIreland.contains(55.133, -7.453)) // Buncrana, Inishowen
        assertFalse(NorthernIreland.contains(54.300, -8.000)) // Leitrim, inside the box
    }

    @Test
    fun aRoadIsJudgedByItsMiddlePoint() {
        val dublin = doubleArrayOf(-6.260, 53.350)
        val belfast = doubleArrayOf(-5.930, 54.597)
        // Ends in the Republic, middle in Belfast: kept. The other way round: not.
        assertTrue(NorthernIreland.keeps(30, dublin + belfast + dublin))
        assertFalse(NorthernIreland.keeps(30, belfast + dublin + belfast))
        // Two points: the second is the middle one.
        assertTrue(NorthernIreland.keeps(40, dublin + belfast))
        assertFalse(NorthernIreland.keeps(40, belfast + dublin))
    }

    @Test
    fun onlyWholeMultiplesOfFiveMphAreUkLimits() {
        val belfast = doubleArrayOf(-5.931, 54.596, -5.930, 54.597)
        for (mph in listOf(20, 30, 40, 45, 60, 70)) assertTrue("$mph", NorthernIreland.keeps(mph, belfast))
        // 80, 100 and 120 km/h read as 50, 62 and 75 mph: 50 and 75 slip through by value alone, 62 doesn't.
        assertFalse(NorthernIreland.keeps(62, belfast))
        assertFalse(NorthernIreland.keeps(48, belfast))
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NorthernIrelandTest {
    @Test fun townsInNorthernIreland() {
        assertTrue(NorthernIreland.contains(54.597, -5.930)) // Belfast
        assertTrue(NorthernIreland.contains(54.997, -7.309)) // Derry/Londonderry
        assertTrue(NorthernIreland.contains(54.827, -7.463)) // Strabane
        assertTrue(NorthernIreland.contains(54.346, -7.632)) // Enniskillen
        assertTrue(NorthernIreland.contains(54.176, -6.338)) // Newry
    }

    @Test fun townsOutside() {
        assertFalse(NorthernIreland.contains(53.350, -6.260)) // Dublin
        assertFalse(NorthernIreland.contains(54.950, -7.734)) // Letterkenny, Donegal
        assertFalse(NorthernIreland.contains(54.834, -7.482)) // Lifford, across the Foyle from Strabane
        assertFalse(NorthernIreland.contains(54.004, -6.404)) // Dundalk
        assertFalse(NorthernIreland.contains(55.950, -3.190)) // Edinburgh
    }

    @Test fun keepsOnlyUkLimitsInside() {
        val belfast = doubleArrayOf(-5.931, 54.596, -5.930, 54.597, -5.929, 54.598)
        val dublin = doubleArrayOf(-6.261, 53.349, -6.260, 53.350, -6.259, 53.351)
        assertTrue(NorthernIreland.keeps(30, belfast))
        assertFalse(NorthernIreland.keeps(31, belfast)) // 50 km/h: a Republic limit
        assertFalse(NorthernIreland.keeps(30, dublin))
    }
}

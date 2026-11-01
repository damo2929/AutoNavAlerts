// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Low bridges: the height from the set name and the vehicle-height rule (DESIGN.md §6.5). Names are made up in the site's style. */
class BridgeHeightTest {
    @Test
    fun heightComesFromTheSetName() {
        assertEquals(411, BridgeHeight.parseCm("Bridge - 13ft 6in"))
        assertEquals(396, BridgeHeight.parseCm("Bridge - 13ft"))
        assertEquals(168, BridgeHeight.parseCm("Bridge - 5ft 6in"))
        assertEquals(511, BridgeHeight.parseCm("Bridge - 16ft 9in.ov2"))
        assertNull(BridgeHeight.parseCm("Unknown Height"))
        assertNull(BridgeHeight.parseCm("PGW fixed cameras · 30 mph"))
    }

    @Test
    fun onlyBridgesLowerThanTheVehicleAlert() {
        // A 13ft (3.96 m) vehicle: up to 13ft 3in is within the 3 in margin, 13ft 6in isn't (§6.5 worked example).
        val vehicle = 396
        assertTrue(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 12ft 9in"), vehicle))
        assertTrue(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 13ft"), vehicle))
        assertTrue(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 13ft 3in"), vehicle))
        assertFalse(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 13ft 6in"), vehicle))
        assertFalse(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 16ft 9in"), vehicle))
        // 3.95 m (12ft 11.5in) + 8 cm is 4.03 m: 13ft (396) alerts, 13ft 3in (404) doesn't.
        assertTrue(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 13ft"), 395))
        assertFalse(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 13ft 3in"), 395))
    }

    @Test
    fun theSafetyHeightSliderWidensWhatAlerts() {
        // A 13ft vehicle: no margin stops at 13ft; 30 cm reaches 13ft 9in (419 ≤ 396 + 30); 1 m reaches 16ft 3in.
        assertTrue(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 13ft"), 396, marginCm = 0))
        assertFalse(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 13ft 3in"), 396, marginCm = 0))
        assertTrue(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 13ft 9in"), 396, marginCm = 30))
        assertFalse(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 14ft"), 396, marginCm = 30))
        assertTrue(BridgeHeight.alerts(BridgeHeight.parseCm("Bridge - 16ft 3in"), 396, marginCm = 100))
        assertEquals(0..100, BridgeHeight.MARGIN_RANGE_CM)
    }

    @Test
    fun anUnknownHeightAlertsOnlyWithAVehicleHeightAndNothingAlertsWithoutOne() {
        assertTrue(BridgeHeight.alerts(null, 300))
        assertFalse(BridgeHeight.alerts(null, null))
        assertFalse(BridgeHeight.alerts(168, null))
    }
}

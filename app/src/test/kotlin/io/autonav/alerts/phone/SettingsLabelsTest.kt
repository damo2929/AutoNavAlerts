// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.phone

import io.autonav.alerts.core.alerts.Units
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the Alerts card's slider labels (DESIGN.md §11 step 9, §11.1; docs/SETTINGS.md §1.2). */
class SettingsLabelsTest {
    @Test fun overLimitPercentLabels() {
        assertEquals("−0.1%", pctLabel(-0.001))
        assertEquals("0%", pctLabel(0.0))
        assertEquals("5%", pctLabel(0.05))
        assertEquals("−5%", pctLabel(-0.05))
        assertEquals("12.5%", pctLabel(0.125))
    }

    @Test fun halfLeadTimeLabels() {
        assertEquals("7.5 s", halfSecondsLabel(15))
        assertEquals("8 s", halfSecondsLabel(16))
    }

    @Test fun firstAlertDistanceAtEachSpeed() {
        // Counted out from the passing alert (§11.1, 2026-11-01): 30 mph, 45.7 m + 201 m = 247 m, just over the
        // 242 m floor; 70 mph, 93.9 m + 469 m = 563 m; 20 mph, 45.7 m + 134 m = 180 m, lifted to the floor.
        assertEquals("270 yd", leadDistanceText(30, 15, Units.IMPERIAL))
        assertEquals("247 m", leadDistanceText(30, 15, Units.METRIC))
        assertEquals("616 yd", leadDistanceText(70, 15, Units.IMPERIAL))
        assertEquals("563 m", leadDistanceText(70, 15, Units.METRIC))
        assertEquals("1.13 mi", leadDistanceText(70, 55, Units.IMPERIAL))
        assertEquals("265 yd (minimum)", leadDistanceText(20, 15, Units.IMPERIAL))
        assertEquals("242 m (minimum)", leadDistanceText(20, 15, Units.METRIC))
    }
}

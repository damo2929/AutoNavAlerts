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
        // 30 mph × 15 s = 201 m, under the 242 m floor; 70 mph × 15 s = 469 m.
        assertEquals("265 yd (minimum)", leadDistanceText(30, 15, Units.IMPERIAL))
        assertEquals("242 m (minimum)", leadDistanceText(30, 15, Units.METRIC))
        assertEquals("513 yd", leadDistanceText(70, 15, Units.IMPERIAL))
        assertEquals("469 m", leadDistanceText(70, 15, Units.METRIC))
        assertEquals("1.07 mi", leadDistanceText(70, 55, Units.IMPERIAL))
    }
}

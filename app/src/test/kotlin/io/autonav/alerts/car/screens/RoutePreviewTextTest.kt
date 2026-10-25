// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import io.autonav.alerts.nav.EcoRoutes.Traffic
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the route preview's traffic line for the fastest / energy-efficient rows (DESIGN.md §8.3a "Traffic on each route"). */
class RoutePreviewTextTest {
    @Test fun trafficLineReadsAsBefore() {
        assertEquals(" · light traffic", RoutePreviewScreen.trafficLine(Traffic.LIGHT, 0))
        assertEquals(" · some traffic +3 min", RoutePreviewScreen.trafficLine(Traffic.SOME, 3))
        assertEquals(" · heavy traffic +12 min", RoutePreviewScreen.trafficLine(Traffic.HEAVY, 12))
        assertEquals("", RoutePreviewScreen.trafficLine(Traffic.UNKNOWN, 12))
    }
}

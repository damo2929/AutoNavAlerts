// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import io.autonav.alerts.core.alerts.AlertStage
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.data.PoiEntity
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.PoiSourceType
import io.autonav.alerts.data.StageCols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The global lead-time slider applied when a set's stages are resolved (DESIGN.md §11.1). */
class LeadSliderResolveTest {
    private val first = AlertStage(true, DistanceMode.TIME, metres = 242, seconds = 30.0)
    private val second = AlertStage(true, DistanceMode.TIME, metres = 142, seconds = 7.0)

    @Test fun fifteenSecondsGivesFifteenAndSevenAndAHalf() {
        val (f, s) = AlertCoordinator.leadStages(first, second, 15)
        assertEquals(15.0, f.seconds!!, 0.0)
        assertEquals(7.5, s.seconds!!, 0.0)
    }

    @Test fun sixtySecondsGivesSixtyAndThirty() {
        val (f, s) = AlertCoordinator.leadStages(first, second, 60)
        assertEquals(60.0, f.seconds!!, 0.0)
        assertEquals(30.0, s.seconds!!, 0.0)
        // Worked numbers: 70 mph (31.3 m/s) → 1877 m and 938 m.
        assertEquals(1878.0, f.triggerMetres(31.3), 1.0)
        assertEquals(939.0, s.triggerMetres(31.3), 1.0)
    }

    @Test fun fixedStagesAreLeftAlone() {
        val fixedFirst = AlertStage(true, DistanceMode.FIXED, metres = 500, seconds = null as Double?)
        val fixedSecond = AlertStage(true, DistanceMode.FIXED, metres = 274, seconds = null as Double?)
        val (f, s) = AlertCoordinator.leadStages(fixedFirst, fixedSecond, 60)
        assertEquals(fixedFirst, f)
        assertEquals(fixedSecond, s)
        assertNull(f.seconds)
        assertEquals(500.0, f.triggerMetres(31.3), 0.0)
        // A FIXED 1st with a TIME 2nd: only the 2nd follows the slider.
        val (f2, s2) = AlertCoordinator.leadStages(fixedFirst, second, 40)
        assertEquals(fixedFirst, f2)
        assertEquals(20.0, s2.seconds!!, 0.0)
    }

    @Test fun floorsAndEnabledFlagsAreKept() {
        val off = second.copy(enabled = false, metres = 160)
        val (f, s) = AlertCoordinator.leadStages(first, off, 20)
        assertEquals(242, f.metres)
        assertEquals(160, s.metres)
        assertEquals(false, s.enabled)
        assertEquals(10.0, s.seconds!!, 0.0)
        // At 30 mph (13.4 m/s) 20 s is 268 m: over the floor; the floor still applies at a crawl.
        assertEquals(268.0, f.triggerMetres(13.4), 0.5)
        assertEquals(242.0, f.triggerMetres(2.0), 0.0)
    }

    @Test fun nullLeavesTheSetsOwnSeconds() {
        val (f, s) = AlertCoordinator.leadStages(first, second, null)
        assertEquals(30.0, f.seconds!!, 0.0)
        assertEquals(7.0, s.seconds!!, 0.0)
    }

    @Test fun resolveAppliesTheSliderToTheEnginePoi() {
        val set = PoiSetEntity(
            id = 3, name = "Gatso 30", source = PoiSourceType.FILE, format = "igo:gatso30",
            defaultIconId = "builtin:camera_fixed", firstTemplate = "{category}", secondTemplate = "{category}", passingTemplate = "{category}",
            firstAlert = StageCols(true, DistanceMode.TIME, 242, 30), secondAlert = StageCols(true, DistanceMode.TIME, 142, 7),
            lastUpdated = 0L,
        )
        val poi = PoiEntity(
            id = 7, setId = 3, categoryId = null, lat = 51.0, lon = -1.0, name = null, speedLimit = 30, speedUnit = SpeedUnit.MPH,
            directionType = DirectionType.ONE_WAY, bearing = 90f, gridCell = 0L,
        )
        val r = AlertCoordinator.resolve(poi, set, null, leadSeconds = 45)
        assertEquals(45.0, r.alert.first.seconds!!, 0.0)
        assertEquals(22.5, r.alert.second.seconds!!, 0.0)
        assertEquals(242, r.alert.first.metres)
        assertEquals(142, r.alert.second.metres)
        assertEquals(true, r.alert.soundOn)
        // The default (no slider) keeps the stored seconds, as before.
        assertEquals(30.0, AlertCoordinator.resolve(poi, set, null).alert.first.seconds!!, 0.0)
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.core.poi.ZoneRole
import io.autonav.alerts.data.PoiCategoryEntity
import io.autonav.alerts.data.PoiEntity
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.PoiSourceType
import io.autonav.alerts.data.StageCols
import io.autonav.alerts.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The [AlertCoordinator] rules that need no engine: the `{direction}` word (DESIGN.md §7.2), the mute rule
 * (§12.3: the timed mute or Voice off silence speech only), which sets are mobile van sites (§11 step 4) and the
 * §4.2 resolution of a POI's effective settings (the category over the set; limits to m/s once).
 */
class AlertWordsTest {
    private val lat = 53.0
    private val lon = -1.0

    private fun poiAt(bearingFromCarDeg: Double, metres: Double = 200.0, speed: Int? = 30, unit: SpeedUnit = SpeedUnit.MPH): PoiEntity {
        val b = Math.toRadians(bearingFromCarDeg)
        val pLat = lat + metres * Math.cos(b) / 111_320.0
        val pLon = lon + metres * Math.sin(b) / (111_320.0 * Math.cos(Math.toRadians(lat)))
        return PoiEntity(
            id = 1, setId = 1, categoryId = null, lat = pLat, lon = pLon, name = null, speedLimit = speed, speedUnit = unit,
            directionType = DirectionType.ONE_WAY, bearing = 0f, gridCell = 0L,
        )
    }

    private fun fix(heading: Double?) = Fix(lat = lat, lon = lon, speedMps = 20.0, heading = heading, timeMs = 0L)

    private fun set(source: PoiSourceType = PoiSourceType.PGW, format: String = "IGO:gatso") = PoiSetEntity(
        id = 4, name = "PGW fixed cameras", source = source, format = format, defaultIconId = "builtin:camera_fixed",
        firstTemplate = "{category}", secondTemplate = "{category}", passingTemplate = "{category}",
        firstAlert = StageCols(true, DistanceMode.TIME, 242, 15), secondAlert = StageCols(true, DistanceMode.TIME, 142, 7),
        lastUpdated = 0L,
    )

    private fun cat(code: String = "1", name: String = "Fixed camera") = PoiCategoryEntity(setId = 4, sourceCode = code, name = name)

    @Test fun directionIsAheadWithin25DegreesEitherSide() {
        assertEquals("ahead", AlertCoordinator.directionWord(fix(0.0), poiAt(0.0)))
        assertEquals("ahead", AlertCoordinator.directionWord(fix(0.0), poiAt(20.0)))
        assertEquals("ahead", AlertCoordinator.directionWord(fix(0.0), poiAt(-20.0)))
        assertEquals("on your right", AlertCoordinator.directionWord(fix(0.0), poiAt(30.0)))
        assertEquals("on your left", AlertCoordinator.directionWord(fix(0.0), poiAt(-30.0)))
        // Behind on either side is still left or right; there is no "behind".
        assertEquals("on your right", AlertCoordinator.directionWord(fix(0.0), poiAt(150.0)))
        assertEquals("on your left", AlertCoordinator.directionWord(fix(0.0), poiAt(210.0)))
    }

    @Test fun directionWrapsAcrossNorth() {
        // Heading 350°, camera at 10°: 20° to the right, so ahead; at 30°, 40° to the right.
        assertEquals("ahead", AlertCoordinator.directionWord(fix(350.0), poiAt(10.0)))
        assertEquals("on your right", AlertCoordinator.directionWord(fix(350.0), poiAt(30.0)))
        assertEquals("on your left", AlertCoordinator.directionWord(fix(10.0), poiAt(330.0)))
        assertEquals("on your left", AlertCoordinator.directionWord(fix(90.0), poiAt(0.0)))
    }

    @Test fun noHeadingIsAlwaysAhead() {
        for (b in listOf(0.0, 90.0, 180.0, 270.0)) assertEquals("ahead", AlertCoordinator.directionWord(fix(null), poiAt(b)))
    }

    @Test fun mutedByTheTimedMuteOrVoiceOff() = with(AlertCoordinator) {
        val now = 1_000_000L
        assertFalse(AppSettings().muted(now))
        assertTrue(AppSettings(mutedUntilMs = now + 1).muted(now))
        // The mute ends at its time exactly.
        assertFalse(AppSettings(mutedUntilMs = now).muted(now))
        assertFalse(AppSettings(mutedUntilMs = now - 60_000).muted(now))
        // Voice off silences speech whatever the timer says.
        assertTrue(AppSettings(voiceOn = false).muted(now))
        assertTrue(AppSettings(voiceOn = false, mutedUntilMs = now + 1).muted(now))
        // Sound (chime) has nothing to do with it.
        assertFalse(AppSettings(chimeOn = false).muted(now))
    }

    @Test fun mobileSitesByCodeStemNameOrPendingSource() {
        assertFalse(AlertCoordinator.isMobileSite(set(), cat()))
        assertFalse(AlertCoordinator.isMobileSite(set(), null))
        // By PGW category code, any case.
        assertTrue(AlertCoordinator.isMobileSite(set(), cat(code = "MOBILE", name = "x")))
        assertTrue(AlertCoordinator.isMobileSite(set(), cat(code = "pMobile", name = "x")))
        // By the file stem after "FORMAT:", including the OV2 per-speed files.
        assertTrue(AlertCoordinator.isMobileSite(set(format = "IGO:mobile"), null))
        assertTrue(AlertCoordinator.isMobileSite(set(format = "OV2:mobile_30"), null))
        assertTrue(AlertCoordinator.isMobileSite(set(format = "OV2:pmobile_var"), null))
        // A format with no stem, or a stem that only contains "mobile" later, isn't enough.
        assertFalse(AlertCoordinator.isMobileSite(set(format = "mobile"), null))
        assertFalse(AlertCoordinator.isMobileSite(set(format = "OV2:gatso_mobile"), null))
        // By the category name (reports, hand imports).
        assertTrue(AlertCoordinator.isMobileSite(set(format = "REPORT"), cat(code = "2", name = "Mobile camera (reported)")))
        // The pending-mobile source on its own.
        assertTrue(AlertCoordinator.isMobileSite(set(source = PoiSourceType.PGW_PENDING, format = "IGO:x"), null))
    }

    @Test fun resolveUsesTheSetWhenThereIsNoCategory() {
        val r = AlertCoordinator.resolve(poiAt(0.0), set().copy(soundEnabled = false), null)
        assertEquals("builtin:camera_fixed", r.iconId)
        assertFalse(r.alert.soundOn)
        assertTrue(r.alertsOn)
        assertEquals(ZoneRole.NONE, r.alert.zoneRole)
        assertNull(r.alert.sideToleranceM)
        assertEquals("PGW fixed cameras", r.categoryName)
        assertEquals(30 * Geo.MPS_PER_MPH, r.alert.speedLimitMps!!, 1e-9)
        assertEquals(DirectionType.ONE_WAY, r.alert.directionType)
        assertEquals(0f, r.alert.bearing)
    }

    @Test fun theCategoryOverridesSoundIconZoneAndTemplates() {
        val c = cat(name = "Average speed camera").copy(
            iconId = "builtin:camera_average", soundEnabled = true, zoneRole = ZoneRole.ZONE_POINT,
            firstTemplate = "{category} zone", passingTemplate = null,
        )
        val r = AlertCoordinator.resolve(poiAt(0.0), set().copy(soundEnabled = false), c)
        assertEquals("builtin:camera_average", r.iconId)
        assertTrue(r.alert.soundOn)
        assertEquals(ZoneRole.ZONE_POINT, r.alert.zoneRole)
        assertEquals("Average speed camera", r.categoryName)
        assertEquals("{category} zone", r.firstTemplate)
        // A template the category doesn't set falls back to the set's.
        assertEquals("{category}", r.passingTemplate)
        // A category with sound unset (null) follows the set.
        assertFalse(AlertCoordinator.resolve(poiAt(0.0), set().copy(soundEnabled = false), cat()).alert.soundOn)
    }

    @Test fun alertsNeedTheSetOnItsAlertsOnAndTheCategoryOn() {
        assertTrue(AlertCoordinator.resolve(poiAt(0.0), set(), cat()).alertsOn)
        assertFalse(AlertCoordinator.resolve(poiAt(0.0), set().copy(enabled = false), cat()).alertsOn)
        assertFalse(AlertCoordinator.resolve(poiAt(0.0), set().copy(alertsEnabled = false), cat()).alertsOn)
        assertFalse(AlertCoordinator.resolve(poiAt(0.0), set(), cat().copy(alertsEnabled = false)).alertsOn)
    }

    @Test fun limitsAreConvertedToMetresPerSecondOnce() {
        assertEquals(50 / 3.6, AlertCoordinator.resolve(poiAt(0.0, speed = 50, unit = SpeedUnit.KMH), set(), null).alert.speedLimitMps!!, 1e-9)
        assertEquals(70 * Geo.MPS_PER_MPH, AlertCoordinator.resolve(poiAt(0.0, speed = 70), set(), null).alert.speedLimitMps!!, 1e-9)
        assertNull(AlertCoordinator.resolve(poiAt(0.0, speed = null), set(), null).alert.speedLimitMps)
    }

    @Test fun mobileSitesGetTheWiderSideTolerance() {
        // §11 step 4: vans park up to 100 m beside the road.
        assertEquals(100.0, AlertCoordinator.resolve(poiAt(0.0), set(format = "IGO:mobile"), null).alert.sideToleranceM!!, 0.0)
        assertEquals(100.0, AlertCoordinator.resolve(poiAt(0.0), set(), cat(code = "mobile")).alert.sideToleranceM!!, 0.0)
        assertNull(AlertCoordinator.resolve(poiAt(0.0), set(), cat()).alert.sideToleranceM)
    }
}

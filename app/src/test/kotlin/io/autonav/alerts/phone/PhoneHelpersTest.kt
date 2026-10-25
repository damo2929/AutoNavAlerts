// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.phone

import android.Manifest
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.PoiSourceType
import io.autonav.alerts.data.StageCols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The phone app's pure helpers beyond `SettingsLabelsTest` (DESIGN.md §13; docs/SETTINGS.md §2, §4.1): the
 * Cameras tab's grouping of camera sets by type and speed, the set editor writing the lead-time slider's
 * seconds into seconds-ahead stages (§11.1), the report card's compass word (§8.5), file sizes on the
 * Diagnostics card, and the Permissions card's order and what Allow asks for.
 */
class PhoneHelpersTest {
    private fun set(name: String, source: PoiSourceType = PoiSourceType.PGW, count: Int = 1, enabled: Boolean = true) = PoiSetEntity(
        name = name, source = source, format = "OV2:x", enabled = enabled, defaultIconId = "builtin:camera_fixed",
        firstTemplate = "", secondTemplate = "", passingTemplate = "",
        firstAlert = StageCols(true, DistanceMode.TIME, 242, 15), secondAlert = StageCols(true, DistanceMode.FIXED, 142, null),
        poiCount = count, lastUpdated = 0,
    )

    @Test fun cameraSetsGroupByTypeWithSpeedsInNumericOrder() {
        val groups = cameraGroups(listOf(
            set("PGW fixed cameras · 100 mph"), set("PGW fixed cameras · 20 mph"), set("PGW fixed cameras · variable limit"),
            set("PGW fixed cameras · 30 mph"), set("PGW average speed cameras (SPECS) · 50 mph"),
        ))
        assertEquals(listOf("PGW average speed cameras (SPECS)", "PGW fixed cameras"), groups.map { it.name })
        assertEquals(
            listOf("PGW fixed cameras · 20 mph", "PGW fixed cameras · 30 mph", "PGW fixed cameras · 100 mph", "PGW fixed cameras · variable limit"),
            groups[1].sets.map { it.name },
        )
    }

    @Test fun pendingSitesComeAfterPgwAndMyReportsLastAndHandImportsAreLeftOut() {
        val groups = cameraGroups(listOf(
            set("My reports", PoiSourceType.REPORT), set("Speed bumps", PoiSourceType.FILE),
            set("PGW pending mobile sites (unverified)", PoiSourceType.PGW_PENDING), set("PGW red light cameras"), set("PGW mobile camera sites"),
        ))
        assertEquals(
            listOf("PGW mobile camera sites", "PGW red light cameras", "PGW pending mobile sites (unverified)", "My reports"),
            groups.map { it.name },
        )
        assertTrue(cameraGroups(emptyList()).isEmpty())
    }

    @Test fun aGroupTotalsItsCamerasAndIsOnWhileAnySetIsOn() {
        val g = cameraGroups(listOf(set("PGW fixed cameras · 30 mph", count = 120, enabled = false), set("PGW fixed cameras · 40 mph", count = 30))).single()
        assertEquals(150, g.cameras)
        assertTrue(g.anyOn)
        assertFalse(g.copy(sets = g.sets.map { it.copy(enabled = false) }).anyOn)
    }

    @Test fun speedOrderReadsTheNumberAfterTheDot() {
        assertEquals(20, speedOrder("PGW fixed cameras · 20 mph"))
        assertEquals(Int.MAX_VALUE, speedOrder("PGW fixed cameras · variable limit"))
        assertEquals(Int.MAX_VALUE, speedOrder("Fixed 30 mph"))
    }

    @Test fun theSliderWritesItsSecondsIntoSecondsAheadStagesOnly() {
        val s = set("x")
        val r = withSliderSeconds(s, 15)
        assertEquals(StageCols(true, DistanceMode.TIME, 242, 15), r.firstAlert)
        // The 2nd here is a distance stage: left as it was.
        assertEquals(s.secondAlert, r.secondAlert)
        // Half is rounded down: 15 s → 7 s, 60 s → 30 s.
        val both = s.copy(secondAlert = StageCols(true, DistanceMode.TIME, 142, 99))
        assertEquals(7, withSliderSeconds(both, 15).secondAlert.seconds)
        assertEquals(30, withSliderSeconds(both, 60).secondAlert.seconds)
        assertEquals(60, withSliderSeconds(both, 60).firstAlert.seconds)
        // Nothing else about the set changes.
        assertEquals(both.copy(firstAlert = both.firstAlert.copy(seconds = 40), secondAlert = both.secondAlert.copy(seconds = 20)), withSliderSeconds(both, 40))
    }

    @Test fun compassWordsCover45DegreesEach() {
        val expected = mapOf(
            0 to "N", 22 to "N", 23 to "NE", 67 to "NE", 68 to "E", 90 to "E", 135 to "SE", 180 to "S", 225 to "SW",
            270 to "W", 292 to "W", 293 to "NW", 337 to "NW", 338 to "N", 359 to "N",
        )
        for ((deg, word) in expected) assertEquals("$deg°", word, compass(deg))
    }

    @Test fun fileSizes() {
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.UK)
            assertEquals("0 B", sizeText(0))
            assertEquals("1023 B", sizeText(1023))
            assertEquals("1 KB", sizeText(1024))
            assertEquals("37 KB", sizeText(37 * 1024 + 900))
            assertEquals("1023 KB", sizeText(1024L * 1024 - 1))
            assertEquals("1.0 MB", sizeText(1024L * 1024))
            assertEquals("1.2 MB", sizeText(1_258_291))
        } finally {
            Locale.setDefault(locale)
        }
    }

    @Test fun permissionLinesInCardOrder() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.RECORD_AUDIO, PermState.CAR_SPEED, Manifest.permission.READ_CONTACTS,
            ),
            PERMS.map { it.permission },
        )
        // Background location from Android 10, notifications from 13; the rest everywhere.
        assertEquals(listOf(1, 29, 33, 1, 1, 1), PERMS.map { it.minSdk })
        assertEquals("Location (needed)", PERMS.first().label)
    }

    @Test fun allowAsksForTheMissingForegroundOnesInOneRequest() {
        val none = PermState(PERMS.associateWith { false })
        // Fine location brings coarse; background location and contacts are asked for on their own.
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.RECORD_AUDIO, PermState.CAR_SPEED),
            none.missingForeground(),
        )
        assertFalse(none.background)
        assertFalse(none.contacts)
        val all = PermState(PERMS.associateWith { true })
        assertTrue(all.missingForeground().isEmpty())
        assertTrue(all.background)
        // Only what's still missing is asked for.
        val mostly = PermState(PERMS.associateWith { it.permission != Manifest.permission.RECORD_AUDIO })
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), mostly.missingForeground())
        assertTrue(mostly.granted(PERMS.first()))
    }
}

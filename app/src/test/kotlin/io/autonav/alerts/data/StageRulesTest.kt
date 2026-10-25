// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import io.autonav.alerts.core.alerts.AlertStage
import io.autonav.alerts.core.alerts.AlertTemplateRenderer
import io.autonav.alerts.core.alerts.DistanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DESIGN.md §11.1 "Validation" as the set editor and the backup restore apply it ([StageRules]): the 1st is
 * never off, the 2nd at least 142 m, the 1st at least 100 m before an enabled 2nd, checked in that order;
 * the `StageCols` ↔ core `AlertStage` conversions (§4.1); and the defaults a new set starts with (§7.2, §11.1).
 */
class StageRulesTest {
    private fun stage(metres: Int, enabled: Boolean = true, mode: DistanceMode = DistanceMode.FIXED, seconds: Int? = null) =
        StageCols(enabled, mode, metres, seconds)

    private val firstOff = "The 1st alert can't be turned off."
    private val secondTooClose = "The 2nd alert must be at least 155 yd (142 m), well before the 20 yd passing alert."
    private val gapTooSmall = "The 1st alert must be at least 110 yd (100 m) before the 2nd."

    @Test fun theDefaultsPass() {
        assertNull(StageRules.problem(AlertStage.DEFAULT_FIRST.toCols(), AlertStage.DEFAULT_SECOND.toCols()))
        assertNull(StageRules.problem(stage(242), stage(142)))
    }

    @Test fun theFirstAlertCanNeverBeOff() {
        assertEquals(firstOff, StageRules.problem(stage(500, enabled = false), stage(200)))
        // Checked before anything else, even when the 2nd also breaks a rule.
        assertEquals(firstOff, StageRules.problem(stage(10, enabled = false), stage(5)))
    }

    @Test fun theSecondHasA142MetreFloorInclusive() {
        assertEquals(secondTooClose, StageRules.problem(stage(400), stage(141)))
        assertNull(StageRules.problem(stage(400), stage(StageRules.SECOND_MIN_M)))
        // The floor applies in seconds-ahead mode too: there it is the stage's minimum distance.
        assertEquals(secondTooClose, StageRules.problem(stage(400, mode = DistanceMode.TIME, seconds = 30), stage(100, mode = DistanceMode.TIME, seconds = 15)))
        // The floor is reported before the gap when both fail.
        assertEquals(secondTooClose, StageRules.problem(stage(150), stage(100)))
    }

    @Test fun theFirstMustBeAHundredMetresBeforeTheSecond() {
        assertEquals(gapTooSmall, StageRules.problem(stage(241), stage(142)))
        assertNull(StageRules.problem(stage(242), stage(142)))
        assertEquals(gapTooSmall, StageRules.problem(stage(300), stage(300)))
        // A 1st nearer than the 2nd is the same mistake.
        assertEquals(gapTooSmall, StageRules.problem(stage(200), stage(400)))
    }

    @Test fun aSecondTurnedOffIsNotCheckedAtAll() {
        assertNull(StageRules.problem(stage(150), stage(20, enabled = false)))
        assertNull(StageRules.problem(stage(150), stage(900, enabled = false)))
        // Only the 1st's own rule remains.
        assertEquals(firstOff, StageRules.problem(stage(150, enabled = false), stage(20, enabled = false)))
    }

    @Test fun stageColumnsRoundTripThroughTheEngineStage() {
        val cols = listOf(
            stage(242, mode = DistanceMode.TIME, seconds = 15),
            stage(500, mode = DistanceMode.FIXED, seconds = null),
            stage(142, enabled = false, mode = DistanceMode.TIME, seconds = 7),
        )
        for (c in cols) assertEquals(c, c.toStage().toCols())
        val s = stage(300, mode = DistanceMode.TIME, seconds = 20).toStage()
        assertEquals(AlertStage(true, DistanceMode.TIME, 300, 20.0), s)
        assertNull(stage(300).toStage().seconds)
    }

    @Test fun fractionalSecondsAreRoundedHalfToEvenWhenStored() {
        // kotlin.math.round: 7.5 → 8, 6.5 → 6, 7.4 → 7. The DB's seconds are ignored at run time (the slider wins, §11.1).
        assertEquals(8, AlertStage(true, DistanceMode.TIME, 142, 7.5).toCols().seconds)
        assertEquals(6, AlertStage(true, DistanceMode.TIME, 142, 6.5).toCols().seconds)
        assertEquals(7, AlertStage(true, DistanceMode.TIME, 142, 7.4).toCols().seconds)
        assertNull(AlertStage(true, DistanceMode.FIXED, 142, null as Double?).toCols().seconds)
    }

    @Test fun aNewSetStartsWithTheDesignDefaults() {
        val file = PoiRepository.defaultSet("Speed bumps", PoiSourceType.FILE, "GPX", alerts = false, now = 42L)
        assertEquals("Speed bumps", file.name)
        assertEquals(PoiSourceType.FILE, file.source)
        assertEquals("GPX", file.format)
        assertEquals(false, file.alertsEnabled)
        // Everything else is on; a hand import gets the pin, every other source a fixed camera (§7.1).
        assertEquals(true, file.enabled)
        assertEquals(true, file.showOnMap)
        assertEquals(true, file.soundEnabled)
        assertEquals(IconRepository.PIN, file.defaultIconId)
        assertEquals(AlertTemplateRenderer.DEFAULT_FIRST, file.firstTemplate)
        assertEquals(AlertTemplateRenderer.DEFAULT_SECOND, file.secondTemplate)
        assertEquals(AlertTemplateRenderer.DEFAULT_PASSING, file.passingTemplate)
        assertEquals(StageCols(true, DistanceMode.TIME, 242, 15), file.firstAlert)
        assertEquals(StageCols(true, DistanceMode.TIME, 142, 8), file.secondAlert)
        assertEquals(11f, file.minZoom, 0f)
        assertEquals(SetState.ACTIVE, file.state)
        assertEquals(42L, file.lastUpdated)
        assertNull(StageRules.problem(file.firstAlert, file.secondAlert))
        for (src in listOf(PoiSourceType.PGW, PoiSourceType.PGW_PENDING, PoiSourceType.REPORT)) {
            assertEquals(src.name, IconRepository.CAMERA_FIXED, PoiRepository.defaultSet("x", src, "f", true, 0).defaultIconId)
        }
    }
}

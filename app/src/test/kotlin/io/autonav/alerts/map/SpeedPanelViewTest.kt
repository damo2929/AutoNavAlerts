// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.app.Application
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.alerts.ActiveAlert
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.map.SpeedPanelView.Severity
import io.autonav.alerts.map.SpeedPanelView.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The speed panel (DESIGN.md §8.4): the 112 × 56 dp pill, the speed with its unit, the limit ring that is
 * always there ("–" when no limit is known), the source dot (none for Google, blue for a camera or zone, grey
 * for OpenStreetMap), the amber/red speed number from the SDK's severity, the display-unit conversion of a
 * camera's limit, and a redraw only when a shown value changed. Runs on the Robolectric main thread at xhdpi
 * (2 px per dp). Hard rules 2 (the one speed/limit display, nothing drawn before a fix) and 3 (no redraw with
 * unchanged content); the bottom-right placement (rule 4) is `MapSurface`'s and not tested here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class, qualifiers = "xhdpi")
class SpeedPanelViewTest {
    private val panel = SpeedPanelView(ApplicationProvider.getApplicationContext())
    private val ink = Color.rgb(32, 33, 36)
    private val amber = Color.rgb(227, 116, 0)
    private val red = Color.rgb(217, 48, 37)
    private val greyRing = Color.rgb(158, 158, 158)
    private val cameraBlue = Color.rgb(21, 101, 192)
    private val osmGrey = Color.rgb(117, 117, 117)

    private fun frame(): RecordingCanvas = RecordingCanvas.frameOf(panel)

    /** The source dot: a filled circle (the ring is stroked). */
    private fun RecordingCanvas.dots() = circles.filter { it.style == Paint.Style.FILL }

    @Test fun measuresAsA112By56DpPill() {
        frame()
        assertEquals(224, panel.measuredWidth)
        assertEquals(112, panel.measuredHeight)
        assertEquals(112, SpeedPanelView.WIDTH_DP)
        assertEquals(56, SpeedPanelView.HEIGHT_DP)
    }

    @Test fun drawsNothingBeforeTheFirstFix() {
        assertTrue(frame().ops.isEmpty())
        panel.update(null, 30, Source.GOOGLE, Severity.NONE, "mph")
        assertTrue("no speed, no pill: an empty pill would be a dead element", frame().ops.isEmpty())
    }

    @Test fun showsTheLimitTheSpeedAndTheUnitInThatOrder() {
        panel.update(47, 50, Source.GOOGLE, Severity.NONE, "mph")
        val f = frame()
        assertEquals(listOf("50", "47", "mph"), f.texts.map { it.text })
        assertEquals(ink, f.texts[1].color)
        // One white pill with its light-grey edge.
        assertEquals(listOf(Color.WHITE, Color.rgb(218, 220, 224)), f.roundRects.map { it.color })
        // The red ring, and no source dot for Google's limit.
        val ring = f.circles.single()
        assertEquals(Paint.Style.STROKE, ring.style)
        assertEquals(red, ring.color)
    }

    @Test fun speedTextIsLargerThanTheUnit() {
        panel.update(47, 50, Source.GOOGLE, Severity.NONE, "km/h")
        val t = frame().texts
        assertEquals("km/h", t[2].text)
        assertEquals(48f, t[1].size, 0.01f)
        assertEquals(22f, t[2].size, 0.01f)
        assertEquals(40f, t[0].size, 0.01f)
    }

    @Test fun minorSeverityTurnsTheSpeedAmber() {
        panel.update(33, 30, Source.GOOGLE, Severity.MINOR, "mph")
        val t = frame().texts
        assertEquals(amber, t[1].color)
        assertEquals("the limit number stays dark", ink, t[0].color)
    }

    @Test fun majorSeverityTurnsTheSpeedRed() {
        panel.update(36, 30, Source.GOOGLE, Severity.MAJOR, "mph")
        assertEquals(red, frame().texts[1].color)
    }

    @Test fun overACameraLimitWithoutTheSdkSeverityStaysNormal() {
        // §8.4 "Colour": being over a camera or OSM limit doesn't colour the panel; only the SDK's severity does.
        panel.update(45, 30, Source.CAMERA, Severity.NONE, "mph")
        assertEquals(ink, frame().texts[1].color)
    }

    @Test fun aCameraOrZoneLimitHasABlueDot() {
        panel.update(28, 30, Source.CAMERA, Severity.NONE, "mph")
        val dot = frame().dots().single()
        assertEquals(cameraBlue, dot.color)
        assertEquals(10f, dot.size, 0.01f)
    }

    @Test fun anOpenStreetMapLimitHasAGreyDot() {
        panel.update(28, 30, Source.OSM, Severity.NONE, "mph")
        assertEquals(osmGrey, frame().dots().single().color)
    }

    @Test fun noLimitShowsADashInAGreyRingWithNoDot() {
        // The limit sign is always there (owner, 2026-10-12): "–" in a grey ring when nothing is known.
        panel.update(52, null, Source.CAMERA, Severity.NONE, "mph")
        val f = frame()
        assertEquals(listOf("–", "52", "mph"), f.texts.map { it.text })
        assertEquals(osmGrey, f.texts[0].color)
        assertEquals(greyRing, f.circles.single().color)
        assertTrue("the source dot belongs to a limit, not to the dash", f.dots().isEmpty())
    }

    @Test fun unchangedValuesDoNotRedraw() {
        panel.update(47, 50, Source.GOOGLE, Severity.NONE, "mph")
        assertTrue(shadowOf(panel).wasInvalidated())
        shadowOf(panel).clearWasInvalidated()
        repeat(10) { panel.update(47, 50, Source.GOOGLE, Severity.NONE, "mph") }
        assertFalse("ten identical fixes, no redraw (hard rule 3)", shadowOf(panel).wasInvalidated())
    }

    @Test fun eachShownValueRedrawsWhenItChanges() {
        panel.update(47, 50, Source.GOOGLE, Severity.NONE, "mph")
        val changes = listOf<() -> Unit>(
            { panel.update(48, 50, Source.GOOGLE, Severity.NONE, "mph") },
            { panel.update(48, 40, Source.GOOGLE, Severity.NONE, "mph") },
            { panel.update(48, 40, Source.CAMERA, Severity.NONE, "mph") },
            { panel.update(48, 40, Source.CAMERA, Severity.MINOR, "mph") },
            { panel.update(48, 40, Source.CAMERA, Severity.MINOR, "km/h") },
            { panel.update(48, null, Source.CAMERA, Severity.MINOR, "km/h") },
            { panel.update(null, null, Source.CAMERA, Severity.MINOR, "km/h") },
        )
        for ((i, change) in changes.withIndex()) {
            shadowOf(panel).clearWasInvalidated()
            change()
            assertTrue("change $i redraws", shadowOf(panel).wasInvalidated())
        }
    }

    @Test fun aCameraLimitIsShownInTheDisplayUnits() {
        // §8.4 "Units": a 50 km/h camera reads 31 with imperial units, a 30 mph one 48 with metric.
        fun alert(limit: Int, unit: SpeedUnit) = ActiveAlert(1, AlertKind.FIRST, "", "", "builtin:camera_fixed", 300.0, limit, 1, 0L, unit)
        assertEquals(31, alert(50, SpeedUnit.KMH).limitShown(imperial = true))
        assertEquals(48, alert(30, SpeedUnit.MPH).limitShown(imperial = false))
        assertEquals(30, alert(30, SpeedUnit.MPH).limitShown(imperial = true))
        assertEquals(50, alert(50, SpeedUnit.KMH).limitShown(imperial = false))
        assertEquals(null, ActiveAlert(1, AlertKind.FIRST, "", "", "x", 0.0, null, 1, 0L).limitShown(true))
    }
}

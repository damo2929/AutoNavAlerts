// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.poi.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertTemplateRendererTest {
    private val r = AlertTemplateRenderer(Units.IMPERIAL)

    @Test
    fun fullTemplate() {
        val out = r.render(AlertTemplateRenderer.DEFAULT_FIRST, TemplateValues(category = "Fixed camera", speed = 30, speedUnit = SpeedUnit.MPH))
        assertEquals("Fixed camera ahead, 30 mph limit", out.display)
        assertEquals("Fixed camera ahead, 30 limit", out.spoken)
    }

    @Test
    fun missingSpeedDropsItsClause() {
        val out = r.render(AlertTemplateRenderer.DEFAULT_FIRST, TemplateValues(category = "Mobile camera site"))
        assertEquals("Mobile camera site ahead", out.display)
    }

    @Test
    fun distanceSpokenAndShort() {
        val out = r.render(AlertTemplateRenderer.DEFAULT_SECOND, TemplateValues(category = "Fixed camera", distanceM = 274.0))
        assertEquals("Fixed camera, 300 yd", out.display)
        assertEquals("Fixed camera, 300 yards", out.spoken)
    }

    @Test
    fun spokenMilesAndFractions() {
        assertEquals("a quarter of a mile", r.spokenDistance(420.0))
        assertEquals("half a mile", r.spokenDistance(800.0))
        assertEquals("1 mile", r.spokenDistance(1_609.0))
        assertEquals("2 miles", r.spokenDistance(3_300.0))
    }

    @Test
    fun metricConvertsMph() {
        val m = AlertTemplateRenderer(Units.METRIC)
        val out = m.render("{category}, {speed} {units}", TemplateValues(category = "Camera", speed = 30, speedUnit = SpeedUnit.MPH))
        assertEquals("Camera, 48 km/h", out.display)
        assertEquals("Camera, 48 kilometres per hour", out.spoken)
    }

    @Test
    fun categoryFallsBackToSetName() {
        val out = r.render("{category} now", TemplateValues(set = "My POIs"))
        assertEquals("My POIs now", out.display)
    }
}

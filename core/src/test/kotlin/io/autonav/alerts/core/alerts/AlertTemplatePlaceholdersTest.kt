// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.poi.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every placeholder of DESIGN.md §7.2 and the rendering rules around them: {direction} and {units}
 * on their own, an unknown placeholder left as typed, the on-screen unit added to a bare {speed},
 * the speed converted from the POI's unit, the tidying of commas and spaces, the distance words at
 * their boundaries, and the zone texts. AlertTemplateRendererTest has the common cases.
 */
class AlertTemplatePlaceholdersTest {
    private val imperial = AlertTemplateRenderer(Units.IMPERIAL)
    private val metric = AlertTemplateRenderer(Units.METRIC)
    private val cam = TemplateValues(category = "Fixed camera", speed = 30, speedUnit = SpeedUnit.MPH)

    @Test
    fun directionAndUnitsPlaceholders() {
        val left = imperial.render("{category} {direction}", cam.copy(direction = "on your left"))
        assertEquals("Fixed camera on your left", left.display)
        assertEquals("Fixed camera on your left", left.spoken)
        // No direction: the clause that needs it goes, here the whole text.
        assertEquals(RenderedAlert("", ""), imperial.render("{category} {direction}", cam))
        val units = imperial.render("{units}", cam)
        assertEquals("mph", units.display)
        assertEquals("miles per hour", units.spoken)
        val metricUnits = metric.render("{units}", cam)
        assertEquals("km/h", metricUnits.display)
        assertEquals("kilometres per hour", metricUnits.spoken)
        // {units} depends on a speed: without one it is missing too.
        assertEquals(RenderedAlert("", ""), imperial.render("{units}", TemplateValues(category = "Cam")))
    }

    @Test
    fun unknownPlaceholderIsLeftAsTyped() {
        val out = imperial.render("{category} {foo}, {speed} {bar}", cam)
        assertEquals("Fixed camera {foo}, 30 mph {bar}", out.display)
        assertEquals("Fixed camera {foo}, 30 {bar}", out.spoken)
    }

    @Test
    fun bareSpeedGetsItsUnitOnScreenOnly() {
        val bare = imperial.render("{speed} limit", cam)
        assertEquals("30 mph limit", bare.display)
        assertEquals("30 limit", bare.spoken)
        // With {units} in the clause nothing is added; a number glued to a unit isn't touched either.
        assertEquals("30 mph", imperial.render("{speed} {units}", cam).display)
        assertEquals("30mph", imperial.render("{speed}mph", cam).display)
        assertEquals("48 km/h", metric.render("{speed} {units}", cam).display)
        // As built, the unit is added to every standalone occurrence of the number in that clause, so a
        // category that happens to contain it gets one too.
        assertEquals("Road 30 mph 30 mph", imperial.render("{category} {speed}", cam.copy(category = "Road 30")).display)
    }

    @Test
    fun speedIsConvertedFromThePoiUnitToTheDisplayUnit() {
        val kmh = TemplateValues(category = "Cam", speed = 50, speedUnit = SpeedUnit.KMH)
        assertEquals("Cam, 31 mph", imperial.render("{category}, {speed} {units}", kmh).display)
        assertEquals("Cam, 50 km/h", metric.render("{category}, {speed} {units}", kmh).display)
        // No unit given: mph is assumed.
        assertEquals("Cam, 48 km/h", metric.render("{category}, {speed} {units}", kmh.copy(speedUnit = null, speed = 30)).display)
    }

    @Test
    fun emptyClausesAndSpacesAreTidied() {
        assertEquals("Fixed camera", imperial.render("{category},, {distance}", cam).display)
        assertEquals("Fixed camera", imperial.render(",{category},", cam).display)
        assertEquals("Fixed camera ahead", imperial.render("{category}   ahead", cam).display)
        assertEquals("", imperial.render("", cam).display)
        assertEquals("", imperial.render("{category}", TemplateValues()).display)
    }

    @Test
    fun shortDistanceBoundaries() {
        assertEquals("0 yd", imperial.shortDistance(0.0))
        assertEquals("140 yd", imperial.shortDistance(128.0))
        assertEquals("1000 yd", imperial.shortDistance(913.5)) // 999 yd rounds up, still yards
        assertEquals("0.6 mi", imperial.shortDistance(914.4)) // 1000 yd: miles from here
        assertEquals("1.2 mi", imperial.shortDistance(2_000.0))
        assertEquals("0 m", metric.shortDistance(4.0))
        assertEquals("1000 m", metric.shortDistance(999.0))
        assertEquals("1.0 km", metric.shortDistance(1_000.0))
        assertEquals("1.5 km", metric.shortDistance(1_500.0))
    }

    @Test
    fun spokenDistanceBoundaries() {
        val mile = 1_609.344
        assertEquals("1 mile", imperial.spokenDistance(0.9 * mile))
        assertEquals("three quarters of a mile", imperial.spokenDistance(0.9 * mile - 1))
        assertEquals("three quarters of a mile", imperial.spokenDistance(0.65 * mile))
        assertEquals("half a mile", imperial.spokenDistance(0.65 * mile - 1))
        assertEquals("half a mile", imperial.spokenDistance(0.4 * mile))
        assertEquals("a quarter of a mile", imperial.spokenDistance(0.4 * mile - 1))
        assertEquals("a quarter of a mile", imperial.spokenDistance(0.22 * mile))
        assertEquals("400 yards", imperial.spokenDistance(0.22 * mile - 1))
        assertEquals("50 yards", imperial.spokenDistance(10.0))
        assertEquals("2 miles", imperial.spokenDistance(1.5 * mile))
        assertEquals("1 mile", imperial.spokenDistance(1.49 * mile))
        assertEquals("1000 metres", metric.spokenDistance(999.0))
        assertEquals("1.0 kilometres", metric.spokenDistance(1_000.0))
        assertEquals("1.3 kilometres", metric.spokenDistance(1_250.0))
        assertEquals("50 metres", metric.spokenDistance(20.0))
        assertEquals("250 metres", metric.spokenDistance(274.0))
    }

    @Test
    fun metricSecondStageAndZoneTexts() {
        val second = metric.render(AlertTemplateRenderer.DEFAULT_SECOND, TemplateValues(category = "Cam", distanceM = 274.0))
        assertEquals("Cam, 270 m", second.display)
        assertEquals("Cam, 250 metres", second.spoken)
        assertEquals("Cam now", imperial.render(AlertTemplateRenderer.DEFAULT_PASSING, TemplateValues(category = "Cam")).display)
        val over = imperial.render(AlertTemplateRenderer.ZONE_OVERSPEED, TemplateValues(speed = 50, speedUnit = SpeedUnit.MPH))
        assertEquals("Average speed zone, 50 mph limit, you are over the limit", over.display)
        assertEquals("Average speed zone, 50 limit, you are over the limit", over.spoken)
        assertEquals("Entering average speed zone", imperial.render(AlertTemplateRenderer.ZONE_ENTER, TemplateValues()).display)
        assertEquals("Leaving average speed zone", imperial.render(AlertTemplateRenderer.ZONE_EXIT, TemplateValues()).spoken)
        assertEquals("you are over the limit", AlertTemplateRenderer.OVERSPEED_SUFFIX)
    }
}

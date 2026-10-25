// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KML (DESIGN.md §5.4) and GPX (§5.5): where a bearing may come from and which wins, "both" and
 * dirtype 2, the category from the folder, the styleUrl, `<type>` or `<sym>`, speeds from data or
 * text in the right unit, bad points as row errors, and no DTDs from a user's file. Made-up places.
 */
class KmlGpxImporterTest {
    private fun kml(body: String) = KmlImporter().parse("<kml><Document>$body</Document></kml>".byteInputStream())
    private fun pm(inner: String, at: String = "-1.5,53.8") = "<Placemark>$inner<Point><coordinates>$at</coordinates></Point></Placemark>"
    private fun data(name: String, value: String) = "<ExtendedData><Data name=\"$name\"><value>$value</value></Data></ExtendedData>"
    private fun gpx(body: String, unit: SpeedUnit = SpeedUnit.MPH) = GpxImporter(unit).parse("<gpx>$body</gpx>".byteInputStream())

    @Test
    fun kmlBearingFromAnyOfItsSources() {
        val r = kml(
            pm(data("Bearing", "45")) +
                pm(data("direction", "-90")) +
                pm("<Style><IconStyle><heading>360</heading></IconStyle></Style>") +
                // ExtendedData wins over the icon's heading.
                pm(data("heading", "10") + "<Style><IconStyle><heading>200</heading></IconStyle></Style>"),
        )
        assertEquals(listOf(45f, 270f, 0f, 10f), r.rows.map { it.bearing })
        assertTrue(r.rows.all { it.dirType == DirectionType.ONE_WAY })
    }

    @Test
    fun kmlBothWaysFromDirtypeOrTheDescriptionButOnlyWithABearing() {
        val r = kml(
            pm("<ExtendedData><Data name=\"heading\"><value>90</value></Data><Data name=\"dirtype\"><value>2</value></Data></ExtendedData>") +
                pm("<description>Covers BOTH carriageways</description>" + data("heading", "90")) +
                pm("<description>both ways</description>") +
                pm(data("dirtype", "2")),
        )
        assertEquals(listOf(DirectionType.BOTH, DirectionType.BOTH, DirectionType.ANY, DirectionType.ANY), r.rows.map { it.dirType })
        assertEquals(listOf(90f, 90f, null, null), r.rows.map { it.bearing })
    }

    @Test
    fun kmlCategoryIsTheFolderElseTheStyleUrl() {
        val r = kml(
            "<Folder><name>Red lights</name>" + pm("<name>One</name>") + "</Folder>" +
                pm("<name>Two</name><styleUrl>#speedcam</styleUrl>") +
                pm("<name>Three</name>"),
        )
        assertEquals(listOf("Red lights", "speedcam", null), r.rows.map { it.categoryCode })
        assertEquals(listOf("One", "Two", "Three"), r.rows.map { it.name })
    }

    @Test
    fun kmlSpeedFromDataThenNameThenDescriptionInTheRightUnit() {
        val r = KmlImporter(SpeedUnit.KMH).parse(
            ("<kml>" + pm("<name>Cam 30</name>" + data("speed", "50")) +
                pm("<name>Cam 40 mph</name>") +
                pm("<name>Cam</name><description>Limit 60</description>") +
                pm("<name>M25 J10</name>") + "</kml>").byteInputStream(),
        )
        assertEquals(listOf(50, 40, 60, null), r.rows.map { it.speed })
        assertEquals(listOf(SpeedUnit.KMH, SpeedUnit.MPH, SpeedUnit.KMH, null), r.rows.map { it.speedUnit })
    }

    @Test
    fun kmlLinesAreIgnoredAndBadPointsAreRowErrors() {
        val r = kml(
            "<Placemark><name>Road</name><LineString><coordinates>-1.5,53.8 -1.6,53.9</coordinates></LineString></Placemark>" +
                pm("<name>Bad</name>", at = "-1.5") +
                pm("<name>Zero</name>", at = "0,0,0") +
                pm("<name>Spaced</name>", at = "\n   -1.55 ,  53.85 , 12 \n"),
        )
        assertEquals(1, r.rows.size)
        assertEquals(53.85, r.rows[0].lat, 0.0)
        assertEquals(-1.55, r.rows[0].lon, 0.0)
        // Placemarks are numbered in document order, lines included.
        assertEquals(listOf(2, 3), r.errors.map { it.lineNumber })
        assertTrue(r.failed)
    }

    @Test
    fun kmlWithADoctypeIsRefused() {
        val evil = """<?xml version="1.0"?><!DOCTYPE kml [<!ENTITY x SYSTEM "file:///etc/hostname">]><kml>${pm("<name>&x;</name>")}</kml>"""
        assertTrue(runCatching { KmlImporter().parse(evil.byteInputStream()) }.isFailure)
    }

    @Test
    fun gpxCategoryIsTypeElseSym() {
        val r = gpx(
            """<wpt lat="53.8" lon="-1.5"><type>speedcam</type><sym>Flag</sym></wpt>
               <wpt lat="53.9" lon="-1.6"><sym>Camera</sym></wpt>
               <wpt lat="54.0" lon="-1.7"></wpt>""",
        )
        assertEquals(listOf("speedcam", "Camera", null), r.rows.map { it.categoryCode })
        assertEquals(listOf(null, null, null), r.rows.map { it.name })
    }

    @Test
    fun gpxBearingFromCourseOrAnExtensionNeverBoth() {
        val r = gpx(
            """<wpt lat="53.8" lon="-1.5"><course>10</course><extensions><heading>200</heading></extensions></wpt>
               <wpt lat="53.8" lon="-1.5"><extensions><heading>200</heading></extensions></wpt>
               <wpt lat="53.8" lon="-1.5"><extensions><bearing>370</bearing></extensions><desc>both ways</desc></wpt>
               <wpt lat="53.8" lon="-1.5"><course>north</course></wpt>""",
        )
        assertEquals(listOf(10f, 200f, 10f, null), r.rows.map { it.bearing })
        assertEquals(listOf(DirectionType.ONE_WAY, DirectionType.ONE_WAY, DirectionType.ONE_WAY, DirectionType.ANY), r.rows.map { it.dirType })
    }

    @Test
    fun gpxSpeedFromNameOrDescInTheDefaultUnit() {
        val r = gpx(
            """<wpt lat="53.8" lon="-1.5"><name>SPECS 50</name></wpt>
               <wpt lat="53.8" lon="-1.5"><name>Cam</name><desc>Limit 80 km/h</desc></wpt>""",
            SpeedUnit.KMH,
        )
        assertEquals(listOf(50, 80), r.rows.map { it.speed })
        assertEquals(listOf(SpeedUnit.KMH, SpeedUnit.KMH), r.rows.map { it.speedUnit })
    }

    @Test
    fun gpxBadWaypointsAreRowErrorsAndTheRestStill() {
        val r = gpx(
            """<wpt lat="abc" lon="-1.5"/><wpt lat="0" lon="0"/><wpt lon="-1.5"/>
               <wpt lat="53.8" lon="-1.5"/><wpt lat="53.9" lon="-1.6"/><wpt lat="54.0" lon="-1.7"/>""",
        )
        assertEquals(3, r.rows.size)
        assertEquals(listOf(1, 2, 3), r.errors.map { it.lineNumber })
        assertTrue(r.errors.all { it.reason == "bad coordinates" && it.line == "wpt" })
        assertNull(r.rows[0].bearing)
    }
}

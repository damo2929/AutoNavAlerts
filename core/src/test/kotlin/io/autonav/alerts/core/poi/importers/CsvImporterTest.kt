// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generic CSV wizard's importer (DESIGN.md §5.7) and the shared rules of §5.1: quoting,
 * delimiter detection, the direction-type presets, a direction only with a bearing, header and
 * blank lines, row errors by line, and the 20% failure rule. Made-up rows only.
 */
class CsvImporterTest {
    private fun parse(text: String, profile: CsvProfile) = CsvImporter(profile).parse(text.reader().buffered())
    private val basic = CsvProfile(latCol = 0, lonCol = 1, nameCol = 2, speedCol = 3, bearingCol = 4, dirTypeCol = 5, categoryCol = 6)

    @Test
    fun splitHandlesQuotesDoubledQuotesAndEmptyFields() {
        assertEquals(listOf("a", "b,c", "d"), CsvImporter.splitCsv("a,\"b,c\",d", ','))
        assertEquals(listOf("he said \"hi\"", "x"), CsvImporter.splitCsv("\"he said \"\"hi\"\"\",x", ','))
        assertEquals(listOf("a", "", ""), CsvImporter.splitCsv("a,,", ','))
        assertEquals(listOf(""), CsvImporter.splitCsv("", ','))
        assertEquals(listOf("a,b", "c"), CsvImporter.splitCsv("a,b\tc", '\t'))
        // A quote opening mid-field still protects the delimiter until it closes.
        assertEquals(listOf("abc,de"), CsvImporter.splitCsv("ab\"c,d\"e", ','))
        // An empty quoted field.
        assertEquals(listOf("", "x"), CsvImporter.splitCsv("\"\",x", ','))
    }

    @Test
    fun delimiterIsTheCommonestOfTheFourInTheFirstFiveLines() {
        assertEquals('\t', CsvImporter.detectDelimiter("lat\tlon\tname\n53.8\t-1.5\tA, B\n"))
        assertEquals('|', CsvImporter.detectDelimiter("lat|lon|name\n53.8|-1.5|x\n"))
        // Nothing to go on: a comma.
        assertEquals(',', CsvImporter.detectDelimiter("just one column\n"))
        // Lines after the fifth don't count.
        val sample = (1..5).joinToString("\n") { "a;b" } + "\n" + (1..20).joinToString("\n") { "1,2,3,4,5" }
        assertEquals(';', CsvImporter.detectDelimiter(sample))
    }

    @Test
    fun directionPresetsMapInAnyCase() {
        val rows = parse(
            "lat,lon,name,speed,bearing,dir,cat\n" +
                "53.80,-1.50,a,30,90,1,c\n53.81,-1.51,b,30,90,2,c\n53.82,-1.52,c,30,90,ONE,c\n" +
                "53.83,-1.53,d,30,90,Both,c\n53.84,-1.54,e,30,90,O,c\n53.85,-1.55,f,30,90,B,c\n",
            basic,
        ).rows
        assertEquals(
            listOf(DirectionType.ONE_WAY, DirectionType.BOTH, DirectionType.ONE_WAY, DirectionType.BOTH, DirectionType.ONE_WAY, DirectionType.BOTH),
            rows.map { it.dirType },
        )
        assertTrue(rows.all { it.bearing == 90f })
    }

    @Test
    fun aDirectionNeedsABearingAndAnyDropsTheBearing() {
        val rows = parse(
            "lat,lon,name,speed,bearing,dir,cat\n" +
                "53.80,-1.50,a,30,,2,c\n" +     // BOTH without a bearing: ANY
                "53.81,-1.51,b,30,180,any,c\n" + // ANY with a bearing: the bearing goes
                "53.82,-1.52,c,30,180,?,c\n" +   // an unknown value with a bearing: ONE_WAY
                "53.83,-1.53,d,30,abc,1,c\n",    // a bearing that isn't a number: ANY
            basic,
        ).rows
        assertEquals(listOf(DirectionType.ANY, DirectionType.ANY, DirectionType.ONE_WAY, DirectionType.ANY), rows.map { it.dirType })
        assertEquals(listOf(null, null, 180f, null), rows.map { it.bearing })
    }

    @Test
    fun bearingsAreBroughtIntoZeroTo360() {
        val p = CsvProfile(hasHeader = false, latCol = 0, lonCol = 1, bearingCol = 2)
        val rows = parse("53.8,-1.5,450\n53.8,-1.5,-90\n53.8,-1.5,360\n", p).rows
        assertEquals(listOf(90f, 270f, 0f), rows.map { it.bearing })
        assertTrue(rows.all { it.dirType == DirectionType.ONE_WAY })
    }

    @Test
    fun aCustomMapReplacesThePresets() {
        val p = basic.copy(dirTypeMap = mapOf("fwd" to DirectionType.ONE_WAY, "2way" to DirectionType.BOTH))
        val rows = parse("h\n53.8,-1.5,a,,10,2WAY,c\n53.8,-1.5,a,,10,2,c\n", p).rows
        assertEquals(DirectionType.BOTH, rows[0].dirType)
        // "2" isn't in this map: back to the bearing-only rule.
        assertEquals(DirectionType.ONE_WAY, rows[1].dirType)
    }

    @Test
    fun headerBlankLinesCrlfAndTrimmedFields() {
        val text = "lat,lon,name,speed,bearing,dir,cat\r\n\r\n 53.80 , -1.50 , Cam A ,40, , , Fixed \r\n   \r\n"
        val r = parse(text, basic)
        assertEquals(1, r.rows.size)
        assertEquals(0, r.errors.size)
        val row = r.rows[0]
        assertEquals(53.8, row.lat, 0.0); assertEquals(-1.5, row.lon, 0.0)
        assertEquals("Cam A", row.name); assertEquals("Fixed", row.categoryCode)
        assertEquals(40, row.speed); assertEquals(SpeedUnit.MPH, row.speedUnit)
        // Without a header the first line is data.
        assertEquals(2, parse("53.8,-1.5\n53.9,-1.6\n", CsvProfile(hasHeader = false, latCol = 0, lonCol = 1)).rows.size)
        // With one, the first line is skipped whatever it holds.
        assertEquals(1, parse("53.8,-1.5\n53.9,-1.6\n", CsvProfile(latCol = 0, lonCol = 1)).rows.size)
    }

    @Test
    fun speedsTakeTheProfilesUnitAndZeroOrTextIsNoLimit() {
        val p = CsvProfile(hasHeader = false, latCol = 0, lonCol = 1, speedCol = 2, speedUnit = SpeedUnit.KMH)
        val rows = parse("53.8,-1.5,50\n53.8,-1.5,0\n53.8,-1.5,var\n53.8,-1.5\n", p).rows
        assertEquals(listOf(50, null, null, null), rows.map { it.speed })
        assertEquals(listOf(SpeedUnit.KMH, null, null, null), rows.map { it.speedUnit })
    }

    @Test
    fun badRowsAreReportedByTheirLineNumber() {
        val r = parse("lat,lon\n53.8,-1.5\n91,-1.5\n0,0\n53.8\nx,y\n53.9,-1.6\n", CsvProfile(latCol = 0, lonCol = 1))
        assertEquals(2, r.rows.size)
        assertEquals(listOf(3, 4, 5, 6), r.errors.map { it.lineNumber })
        assertTrue(r.errors.all { it.reason == "bad coordinates" })
        assertEquals("91,-1.5", r.errors[0].line)
    }

    @Test
    fun moreThanAFifthBadFailsTheWholeImportAndExactlyAFifthDoesNot() {
        val p = CsvProfile(hasHeader = false, latCol = 0, lonCol = 1)
        val good = "53.8,-1.5\n"
        val bad = "x,y\n"
        val fourOne = parse(good.repeat(4) + bad, p)
        assertEquals(5, fourOne.totalConsidered)
        assertFalse(fourOne.failed)
        assertTrue(parse(good.repeat(3) + bad, p).failed)
        assertTrue(parse(bad.repeat(3), p).failed)
        // Nothing at all is a failure too: no good rows.
        assertTrue(parse("", p).failed)
        assertNull(parse("", p).rows.firstOrNull())
    }
}

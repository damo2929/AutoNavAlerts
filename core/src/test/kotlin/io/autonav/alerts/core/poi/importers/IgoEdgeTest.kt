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
 * Edges of the iGO TXT reader (DESIGN.md §5.2): Direction values outside 0–360, comments and
 * blank lines, a byte-order mark, Windows-1252 text, short rows, a second header, and the type
 * code table. The direction rule (§11 step 3) needs a row with no usable Direction to be ANY.
 * Made-up rows only; never PGW data.
 */
class IgoEdgeTest {
    private val header = "X,Y,Type,Speed,DirType,Direction"
    private fun parse(text: String, unit: SpeedUnit = SpeedUnit.MPH) = IgoTxtImporter(unit).parse(text.reader().buffered())

    @Test
    fun directionsThatAreNotABearingMakeTheRowAny() {
        val r = parse("$header\n-1.5,53.8,1,30,1,720\n-1.5,53.8,1,30,1,-10\n-1.5,53.8,1,30,1,east\n-1.5,53.8,1,30,2,359.5\n-1.5,53.8,1,30,3,90\n")
        assertEquals(listOf(DirectionType.ANY, DirectionType.ANY, DirectionType.ANY, DirectionType.BOTH, DirectionType.ANY), r.rows.map { it.dirType })
        assertEquals(listOf(null, null, null, 359.5f, null), r.rows.map { it.bearing })
        assertEquals(0, r.errors.size)
    }

    @Test
    fun commentsAndBlankLinesAreNotRowsAndTheHeaderMayFollowThem() {
        val r = parse("; made-up test file\n\n   ; indented comment\n$header\n-1.5,53.8,1,30,1,90\n\n")
        assertEquals(1, r.rows.size)
        assertEquals(0, r.errors.size)
        assertEquals(0, r.silentlySkipped)
    }

    @Test
    fun aByteOrderMarkHidesTheHeaderWhichIsThenSkippedQuietly() {
        val r = parse("﻿$header\r\n-1.5,53.8,1,30,1,90\r\n")
        assertEquals(1, r.rows.size)
        assertEquals(0, r.errors.size)
        assertEquals(1, r.silentlySkipped)
        assertFalse(r.failed)
    }

    @Test
    fun windows1252TextReadsAndTheWatermarkIsStillSkipped() {
        val bytes = "$header\r\n-1.5,53.8,5,40,,\r\n0.7,51.5,© Example Ltd,,\r\n".toByteArray(charset("windows-1252"))
        val r = bytes.inputStream().bufferedReader(charset("windows-1252")).use { IgoTxtImporter().parse(it) }
        assertEquals(1, r.rows.size)
        assertEquals("5", r.rows[0].categoryCode)
        assertEquals(1, r.silentlySkipped)
    }

    @Test
    fun aRowWithFewerThanThreeFieldsIsAnErrorAndThreeAreEnough() {
        val r = parse("$header\n-1.5,53.8\n-1.5,53.8,4\n")
        assertEquals(listOf("expected 6 fields, got 2"), r.errors.map { it.reason })
        assertEquals(2, r.errors[0].lineNumber)
        val row = r.rows.single()
        assertEquals(DirectionType.ANY, row.dirType)
        assertNull(row.speed)
        assertEquals("4", row.categoryCode)
    }

    @Test
    fun spacesAroundFieldsAndTheConstructorsUnit() {
        val row = parse("$header\n -1.5 , 53.8 , 1 , 50 , 1 , 90 \n", SpeedUnit.KMH).rows.single()
        assertEquals(53.8, row.lat, 0.0)
        assertEquals(50, row.speed)
        assertEquals(SpeedUnit.KMH, row.speedUnit)
        assertEquals(90f, row.bearing)
        assertNull(row.name)
    }

    @Test
    fun aSecondHeaderIsSkippedLikeAWatermark() {
        val r = parse("$header\n-1.5,53.8,1,30,1,90\n$header\n-1.6,53.9,1,30,1,90\n")
        assertEquals(2, r.rows.size)
        assertEquals(1, r.silentlySkipped)
        assertEquals(0, r.errors.size)
    }

    @Test
    fun sniffIgnoresSpacesAndLooksAtTheFirstLineOnly() {
        assertTrue(IgoTxtImporter().sniff("X, Y, Type, Speed, DirType, Direction\r\n"))
        assertFalse(IgoTxtImporter().sniff(""))
        assertFalse(IgoTxtImporter().sniff("; comment\n$header"))
        assertFalse(IgoTxtImporter().sniff("X,Y,Type,Speed,DirType"))
    }

    @Test
    fun typeCodesFromTheDesignTable() {
        assertEquals(
            listOf("Fixed camera", "Red light camera", "Average speed camera", "Mobile camera site", "Fixed camera (type 11)"),
            listOf("1", "3", "4", "5", "11").map(IgoTxtImporter::categoryName),
        )
        assertEquals(setOf("4"), IgoTxtImporter.PGW_AVERAGE_SPEED_CODES)
        assertEquals("Type 2", IgoTxtImporter.categoryName("2"))
    }
}

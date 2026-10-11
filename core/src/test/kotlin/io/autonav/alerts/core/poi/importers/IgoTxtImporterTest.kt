// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Synthetic fixtures only: they copy the structure of a PGW iGO file (DESIGN.md §5.2)
 * but every coordinate and value is made up. Never paste real PGW rows here.
 */
class IgoTxtImporterTest {

    private fun parse(text: String) = IgoTxtImporter().parse(text.reader().buffered())

    private val synthetic = """
        X,Y,Type,Speed,DirType,Direction
        -1.50000,53.80000,1,30,1,90
        -1.51000,53.81000,4,50,2,180
        -1.52000,53.82000,5,40,,
        -1.53000,53.83000,3,,1,360
        -1.54000,53.84000,11,0,1,45
        -1.55000,53.85000,7,30,0,10
        0.70000,51.50000,Copyright Example Ltd,,
    """.trimIndent()

    @Test
    fun parsesAllCameraRowsAndSkipsWatermarkSilently() {
        val r = parse(synthetic)
        assertEquals(6, r.rows.size)
        assertEquals(0, r.errors.size)
        assertEquals(1, r.silentlySkipped)
        assertFalse(r.failed)
    }

    @Test
    fun mapsDirectionTypes() {
        val rows = parse(synthetic).rows
        assertEquals(DirectionType.ONE_WAY, rows[0].dirType)
        assertEquals(90f, rows[0].bearing)
        assertEquals(DirectionType.BOTH, rows[1].dirType)
        // Empty DirType/Direction → ANY with no bearing
        assertEquals(DirectionType.ANY, rows[2].dirType)
        assertNull(rows[2].bearing)
        // DirType 0 → ANY
        assertEquals(DirectionType.ANY, rows[5].dirType)
        assertNull(rows[5].bearing)
    }

    @Test
    fun direction360BecomesZero() {
        assertEquals(0f, parse(synthetic).rows[3].bearing)
    }

    @Test
    fun emptyOrZeroSpeedIsNull() {
        val rows = parse(synthetic).rows
        assertNull(rows[3].speed)
        assertNull(rows[3].speedUnit)
        assertNull(rows[4].speed)
        assertEquals(30, rows[0].speed)
        assertEquals(SpeedUnit.MPH, rows[0].speedUnit)
    }

    @Test
    fun coordinatesAreLonThenLat() {
        val row = parse(synthetic).rows[0]
        assertEquals(53.8, row.lat, 1e-9)
        assertEquals(-1.5, row.lon, 1e-9)
    }

    @Test
    fun handlesCrlfAndLowercaseHeader() {
        val r = parse("x,y,type,speed,dirtype,direction\r\n-1.5,53.8,1,30,1,90\r\n")
        assertEquals(1, r.rows.size)
        assertEquals(90f, r.rows[0].bearing)
    }

    @Test
    fun badCoordinatesAreReportedAndTwentyPercentRuleApplies() {
        val r = parse(
            """
            X,Y,Type,Speed,DirType,Direction
            -1.5,53.8,1,30,1,90
            abc,53.8,1,30,1,90
            -1.5,95.0,1,30,1,90
            """.trimIndent(),
        )
        assertEquals(1, r.rows.size)
        assertEquals(2, r.errors.size)
        assertTrue(r.failed)
    }

    @Test
    fun unknownCodesGetGenericCategory() {
        assertEquals("Fixed camera", IgoTxtImporter.categoryName("1"))
        assertEquals("Type 7", IgoTxtImporter.categoryName("7"))
    }

    @Test
    fun sniffRecognisesHeader() {
        assertTrue(IgoTxtImporter().sniff("X,Y,Type,Speed,DirType,Direction\n1,2,3"))
        assertFalse(IgoTxtImporter().sniff("lat,lon,name"))
    }

    /**
     * Optional check against your own PGW download. Never commit the file.
     *   PGW_IGO_FILE=/path/to/pocketgps_uk_sc.txt ./gradlew :core:test
     */
    @Test
    fun realPgwFileIfProvided() {
        val path = System.getenv("PGW_IGO_FILE")
        assumeTrue("PGW_IGO_FILE not set", !path.isNullOrBlank())
        val r = java.io.File(path!!).bufferedReader(Charsets.ISO_8859_1).use { IgoTxtImporter().parse(it) }
        println("PGW file: ${r.rows.size} rows, ${r.errors.size} errors, ${r.silentlySkipped} skipped; " +
            "types=${r.rows.groupingBy { it.categoryCode }.eachCount()}; " +
            "dir=${r.rows.groupingBy { it.dirType }.eachCount()}")
        assertFalse(r.failed)
        assertEquals(0, r.errors.size)
        assertEquals(1, r.silentlySkipped)
        assertTrue(r.rows.all { (it.bearing ?: 0f) < 360f })
    }
}

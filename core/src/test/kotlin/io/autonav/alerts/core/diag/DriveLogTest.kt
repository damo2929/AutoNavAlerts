// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.diag

import io.autonav.alerts.core.location.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** The drive recorder's CSV (DESIGN.md §15.5). */
class DriveLogTest {
    private val fix = DriveLog.RecordedFix(
        tMs = 1_700_000_000_123, kind = DriveLog.KIND_FIX, src = DriveLog.SRC_PHONE, used = true,
        lat = 53.1234567, lon = -1.2345678, accM = 4.5, spdMps = 12.3, brgDeg = 91.5, hasBrg = true, altM = 120.0,
        fixTMs = 1_700_000_000_100, engSpdMps = 12.0, engHdgDeg = 90.0, hdgSrc = "BLEND", carRawMps = 12.1, carDispMps = 12.6,
    )

    @Test
    fun headerMatchesTheDesign() {
        assertEquals(17, DriveLog.COLUMNS.size)
        assertEquals("t_ms", DriveLog.COLUMNS.first())
        assertEquals("car_disp_mps", DriveLog.COLUMNS.last())
        assertEquals(17, DriveLog.format(fix).split(',').size)
    }

    @Test
    fun formatAndParseRoundTrip() {
        val text = DriveLog.HEADER + "\n" + DriveLog.format(fix) + "\n"
        val back = DriveLog.parse(StringReader(text)).toList()
        assertEquals(listOf(fix), back)
    }

    @Test
    fun emptyFieldsAreNullsAndBadLinesAreSkipped() {
        val speedRow = "1700000000500,speed,car,1,,,,,,0,,,,,,13.2,13.7"
        val text = """
            |${DriveLog.HEADER}
            |
            |$speedRow
            |not,a,row
            |abc,fix,phone,1,53.0,0.0,5,0,0,1,,,,,,,
        """.trimMargin()
        val rows = DriveLog.parse(StringReader(text)).toList()
        assertEquals(1, rows.size)
        val r = rows.single()
        assertEquals(DriveLog.KIND_SPEED, r.kind)
        assertEquals(DriveLog.SRC_CAR, r.src)
        assertNull(r.lat); assertNull(r.spdMps); assertNull(r.hdgSrc)
        assertFalse(r.hasBrg)
        assertEquals(13.2, r.carRawMps!!, 0.0)
        assertEquals(13.7, r.carDispMps!!, 0.0)
    }

    @Test
    fun toFixUsesWhatTheEngineWasGiven() {
        val f = fix.toFix()
        assertEquals(12.0, f.speedMps, 0.0)
        assertEquals(90.0, f.heading!!, 0.0)
        assertEquals(1_700_000_000_100, f.timeMs)
        assertEquals(53.1234567, f.lat, 0.0)
        // Without engine values the fix's own speed is used; no heading.
        val raw = fix.copy(engSpdMps = null, engHdgDeg = null, fixTMs = null).toFix()
        assertEquals(12.3, raw.speedMps, 0.0)
        assertNull(raw.heading)
        assertEquals(1_700_000_000_123, raw.timeMs)
    }

    @Test
    fun toInputCarriesTheSourceAndBearingFlag() {
        val i = fix.toInput()
        assertEquals(LocationSource.PHONE, i.source)
        assertEquals(91.5, i.bearingDeg!!, 0.0)
        assertEquals(12.3, i.speedMps!!, 0.0)
        assertEquals(4.5, i.accuracyM!!, 0.0)
        assertNull(fix.copy(hasBrg = false).toInput().bearingDeg)
        assertEquals(LocationSource.CAR, fix.copy(src = DriveLog.SRC_CAR).toInput().source)
        assertEquals(LocationSource.SIM, fix.copy(src = DriveLog.SRC_SIM).toInput().source)
        assertTrue(fix.copy(spdMps = null).toInput().speedMps == null)
    }
}

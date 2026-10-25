// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.diag

import io.autonav.alerts.core.location.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drive recorder's CSV rows (DESIGN.md §15.5 "Drive recorder", "Replay"): every column
 * round-trips on its own, empty or present; the flags accept 1 and true; spacing and extra columns
 * are tolerated; only an unreadable time drops a row, while an unreadable optional number reads
 * as empty. Made-up positions.
 */
class DriveLogParseTest {
    private val full = DriveLog.RecordedFix(
        tMs = 1_700_000_000_000, kind = DriveLog.KIND_FIX, src = DriveLog.SRC_CAR, used = true,
        lat = -33.8688, lon = 1.0E-5, accM = 3.0, spdMps = 0.0, brgDeg = 359.99, hasBrg = true, altM = -12.5,
        fixTMs = 1_699_999_999_950, engSpdMps = 31.29, engHdgDeg = 0.0, hdgSrc = "TRACK", carRawMps = 31.0, carDispMps = 32.2,
    )

    private fun roundTrip(r: DriveLog.RecordedFix) = DriveLog.parseLine(DriveLog.format(r))

    @Test
    fun everyOptionalColumnRoundTripsEmptyOnItsOwn() {
        assertEquals(full, roundTrip(full))
        val blanks = listOf<(DriveLog.RecordedFix) -> DriveLog.RecordedFix>(
            { it.copy(lat = null) }, { it.copy(lon = null) }, { it.copy(accM = null) }, { it.copy(spdMps = null) },
            { it.copy(brgDeg = null) }, { it.copy(altM = null) }, { it.copy(fixTMs = null) }, { it.copy(engSpdMps = null) },
            { it.copy(engHdgDeg = null) }, { it.copy(hdgSrc = null) }, { it.copy(carRawMps = null) }, { it.copy(carDispMps = null) },
            { it.copy(used = false) }, { it.copy(hasBrg = false) }, { it.copy(kind = DriveLog.KIND_SPEED, src = DriveLog.SRC_SIM) },
        )
        for ((i, blank) in blanks.withIndex()) {
            val r = blank(full)
            assertEquals("case $i", r, roundTrip(r))
        }
    }

    @Test
    fun flagsAcceptOneOrTrueInAnyCase() {
        val base = DriveLog.format(full.copy(used = false, hasBrg = false)).split(',').toMutableList()
        base[3] = "TRUE"; base[9] = "true"
        val r = DriveLog.parseLine(base.joinToString(","))!!
        assertTrue(r.used && r.hasBrg)
        base[3] = "yes"; base[9] = "0"
        val s = DriveLog.parseLine(base.joinToString(","))!!
        assertFalse(s.used || s.hasBrg)
    }

    @Test
    fun spacingAndExtraColumnsAreTolerated() {
        val line = "  " + DriveLog.format(full) + ",extra,columns  "
        val r = DriveLog.parseLine(line)!!
        assertEquals(full.copy(carDispMps = 32.2), r)
    }

    @Test
    fun onlyAnUnreadableTimeDropsTheRow() {
        val f = DriveLog.format(full).split(',').toMutableList()
        assertNull(DriveLog.parseLine((listOf("1.7e12") + f.drop(1)).joinToString(",")))
        assertNull(DriveLog.parseLine((listOf("") + f.drop(1)).joinToString(",")))
        // An optional number that doesn't read is simply empty, and the row is kept.
        f[11] = "soon"; f[4] = "north"
        val r = DriveLog.parseLine(f.joinToString(","))!!
        assertNull(r.fixTMs)
        assertNull(r.lat)
        assertEquals(full.lon, r.lon)
        // Sixteen fields is one short.
        assertNull(DriveLog.parseLine(DriveLog.format(full).substringBeforeLast(',')))
        assertNull(DriveLog.parseLine("t_ms is the header whatever follows"))
    }

    @Test
    fun anUnknownSourceReplaysAsThePhone() {
        val r = DriveLog.parseLine(DriveLog.format(full.copy(src = "tablet")))!!
        assertEquals("tablet", r.src)
        assertEquals(LocationSource.PHONE, r.toInput().source)
        // A row with no position replays at (0, 0) rather than failing.
        val noPos = full.copy(lat = null, lon = null, fixTMs = null).toInput()
        assertEquals(0.0, noPos.lat, 0.0)
        assertEquals(full.tMs, noPos.timeMs)
    }
}

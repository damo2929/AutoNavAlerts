// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.diag

import io.autonav.alerts.core.diag.DriveLog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The recorder's CSV text for a car speed row and a fix row (DESIGN.md §15.5), pinned byte for
 * byte to what the app wrote before the rows were built as [DriveLog.RecordedFix] directly: the
 * `Float` fields Android gives (accuracy, speed, bearing, the car's speeds) are widened to Double
 * exactly as before, so a replay of an old recording and a new one read the same.
 */
class DriveRecorderCsvTest {
    @Test fun speedRowWithEveryOptionalNull() {
        // As CarDataSource builds it: only the car's two speeds, here both missing.
        val row = DriveLog.RecordedFix(tMs = 1_700_000_000_500, kind = DriveLog.KIND_SPEED, src = DriveLog.SRC_CAR, used = true)
        assertEquals("1700000000500,speed,car,1,,,,,,0,,,,,,,", DriveLog.format(row))
    }

    @Test fun fixRowFullyPopulatedWithAwkwardFloats() {
        // As AdaptiveLocationSource builds it from a Location: the Float fields widened with toDouble().
        val row = DriveLog.RecordedFix(
            tMs = 1_700_000_000_123, kind = DriveLog.KIND_FIX, src = DriveLog.SRC_PHONE, used = false,
            lat = 53.1234567, lon = -1.2345678, accM = 12.3456789f.toDouble(), spdMps = 0.1f.toDouble(), brgDeg = (-0.0f).toDouble(), hasBrg = true,
            altM = 120.5, fixTMs = 1_700_000_000_100, engSpdMps = 0.1f.toDouble(), engHdgDeg = 359.99, hdgSrc = "blend",
            carRawMps = 12.3456789f.toDouble(), carDispMps = 13.1f.toDouble(),
        )
        assertEquals(
            "1700000000123,fix,phone,0,53.1234567,-1.2345678,12.34567928314209,0.10000000149011612,-0.0,1,120.5,1700000000100," +
                "0.10000000149011612,359.99,blend,12.34567928314209,13.100000381469727",
            DriveLog.format(row),
        )
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import io.autonav.alerts.core.diag.DriveLog
import io.autonav.alerts.core.location.Estimate
import io.autonav.alerts.core.location.HeadingSource
import io.autonav.alerts.core.location.LocationSource
import io.autonav.alerts.core.location.SpeedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The Estimate → engine Fix bridge (DESIGN.md §10.6a "what the engine sees"). */
class EstimateMapperTest {
    private fun estimate(
        speedSource: SpeedSource = SpeedSource.GPS,
        headingSource: HeadingSource = HeadingSource.TRACK,
        heading: Double? = 92.0,
        location: LocationSource = LocationSource.PHONE,
    ) = Estimate(
        timeMs = 1_000L, speedMps = 13.4, speedSource = speedSource,
        headingDeg = heading, headingSource = headingSource, headingAccuracyDeg = if (heading == null) null else 5.0,
        chordM = 40.0, trackDeg = 91.0, gpsBearingDeg = 93.0, gpsBearingStale = false,
        locationSource = location, resetReason = null,
    )

    @Test fun everySpeedSourceMaps() {
        val expected = mapOf(
            SpeedSource.GPS to io.autonav.alerts.core.alerts.SpeedSource.GPS,
            SpeedSource.DISPLACEMENT to io.autonav.alerts.core.alerts.SpeedSource.DERIVED,
            SpeedSource.CAR_RAW to io.autonav.alerts.core.alerts.SpeedSource.CAR_RAW,
            SpeedSource.SIMULATED to io.autonav.alerts.core.alerts.SpeedSource.SIMULATED,
            SpeedSource.NONE to io.autonav.alerts.core.alerts.SpeedSource.NONE,
        )
        assertEquals(SpeedSource.entries.size, expected.size)
        for (s in SpeedSource.entries) {
            assertEquals(s.name, expected.getValue(s), EstimateMapper.speedSource(s))
            assertEquals(s.name, expected.getValue(s), EstimateMapper.toFix(estimate(speedSource = s), 51.0, -1.0, 4.0).speedSource)
        }
    }

    @Test fun everyHeadingSourceMaps() {
        val expected = mapOf(
            HeadingSource.GPS to io.autonav.alerts.core.alerts.HeadingSource.GPS,
            HeadingSource.TRACK to io.autonav.alerts.core.alerts.HeadingSource.TRACK,
            HeadingSource.BLEND to io.autonav.alerts.core.alerts.HeadingSource.BLEND,
            HeadingSource.HELD to io.autonav.alerts.core.alerts.HeadingSource.HELD,
            HeadingSource.NONE to io.autonav.alerts.core.alerts.HeadingSource.NONE,
        )
        assertEquals(HeadingSource.entries.size, expected.size)
        for (h in HeadingSource.entries) assertEquals(h.name, expected.getValue(h), EstimateMapper.headingSource(h))
    }

    @Test fun everyLocationSourceMapsAndNamesItsRecorderSrc() {
        val expected = mapOf(
            LocationSource.PHONE to (io.autonav.alerts.core.alerts.LocationSource.PHONE to DriveLog.SRC_PHONE),
            LocationSource.CAR to (io.autonav.alerts.core.alerts.LocationSource.CAR to DriveLog.SRC_CAR),
            LocationSource.SIM to (io.autonav.alerts.core.alerts.LocationSource.SIM to DriveLog.SRC_SIM),
        )
        assertEquals(LocationSource.entries.size, expected.size)
        for (l in LocationSource.entries) {
            assertEquals(l.name, expected.getValue(l).second, EstimateMapper.recorderSrc(l))
            assertEquals(l.name, expected.getValue(l).first, EstimateMapper.toFix(estimate(location = l), 51.0, -1.0, 4.0).locationSource)
        }
    }

    @Test fun noneHeadingBecomesNullWithNoAccuracy() {
        val fix = EstimateMapper.toFix(estimate(headingSource = HeadingSource.NONE, heading = null), 51.0, -1.0, 4.0)
        assertNull(fix.heading)
        assertNull(fix.headingAccuracyDeg)
        assertEquals(io.autonav.alerts.core.alerts.HeadingSource.NONE, fix.headingSource)
        // Belt and braces: a NONE source with a left-over value still gives the engine no heading.
        assertNull(EstimateMapper.toFix(estimate(headingSource = HeadingSource.NONE, heading = 45.0), 51.0, -1.0, 4.0).heading)
    }

    @Test fun positionTimeAccuracyAndHeadingCarryOver() {
        val fix = EstimateMapper.toFix(estimate(), 51.5, -1.25, 3.5)
        assertEquals(51.5, fix.lat, 0.0)
        assertEquals(-1.25, fix.lon, 0.0)
        assertEquals(1_000L, fix.timeMs)
        assertEquals(3.5, fix.accuracyM!!, 0.0)
        assertEquals(92.0, fix.heading!!, 0.0)
        assertEquals(5.0, fix.headingAccuracyDeg!!, 0.0)
        assertEquals(13.4, fix.speedMps, 0.0)
        assertNull(fix.displaySpeedMps)
    }

    @Test fun freshCarSpeedOverridesTheEstimateAndCarriesTheDialSpeed() {
        val fix = EstimateMapper.toFix(estimate(speedSource = SpeedSource.DISPLACEMENT), 51.0, -1.0, 4.0, carRawMps = 20.0, carDispMps = 21.0)
        assertEquals(20.0, fix.speedMps, 0.0)
        assertEquals(io.autonav.alerts.core.alerts.SpeedSource.CAR_RAW, fix.speedSource)
        assertNotNull(fix.displaySpeedMps)
        assertEquals(21.0, fix.displaySpeedMps!!, 0.0)
        // Without a fresh car report the dial speed is null: the panel then shows the engine's speed.
        assertNull(EstimateMapper.toFix(estimate(), 51.0, -1.0, 4.0, carRawMps = null, carDispMps = 21.0).displaySpeedMps)
    }
}

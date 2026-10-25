// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import androidx.car.app.hardware.common.CarValue
import io.autonav.alerts.nav.CarDataSource.Companion.okValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car feed rules of DESIGN.md §10.6–10.7 behind [CarDataSource.freshSpeed] and [CarDataSource.carLocationFresh]:
 * a car speed is used only while "Speed and location source" allows it, with a raw value, no older than 3 s; the
 * car's GPS leads only within 3 s of its last good fix; and a reported value counts only with SUCCESS status and
 * inside 0–120 m/s (anything else falls back to the phone, "Fallback").
 */
class CarDataRulesTest {
    private val now = 100_000L
    private fun speed(raw: Float?, ageMs: Long, display: Float? = raw) = CarSpeed(raw, display, now - ageMs)

    @Test fun aCarSpeedIsFreshForThreeSecondsInclusive() {
        assertTrue(CarDataSource.speedUsable(true, speed(13f, 0), now))
        assertTrue(CarDataSource.speedUsable(true, speed(13f, CarDataSource.SPEED_FRESH_MS), now))
        assertFalse(CarDataSource.speedUsable(true, speed(13f, CarDataSource.SPEED_FRESH_MS + 1), now))
        assertEquals(3_000L, CarDataSource.SPEED_FRESH_MS)
    }

    @Test fun aCarSpeedNeedsTheRawValueAndTheSettingOn() {
        // Only the car's dial value: the engine never takes the speedometer reading as the measured speed.
        assertFalse(CarDataSource.speedUsable(true, speed(null, 0, display = 14f), now))
        assertTrue(CarDataSource.speedUsable(true, speed(0f, 0, display = null), now))
        // "Phone only".
        assertFalse(CarDataSource.speedUsable(false, speed(13f, 0), now))
    }

    @Test fun theCarsGpsLeadsWithinThreeSecondsOfItsLastFix() {
        assertFalse(CarDataSource.locationFresh(true, 0L, now))
        assertTrue(CarDataSource.locationFresh(true, now - 1, now))
        assertTrue(CarDataSource.locationFresh(true, now - CarDataSource.LOCATION_FRESH_MS, now))
        assertFalse(CarDataSource.locationFresh(true, now - CarDataSource.LOCATION_FRESH_MS - 1, now))
        assertFalse(CarDataSource.locationFresh(false, now - 1, now))
        assertEquals(3_000L, CarDataSource.LOCATION_FRESH_MS)
    }

    @Test fun onlyASuccessfulPlausibleValueCounts() {
        fun v(x: Float?, status: Int = CarValue.STATUS_SUCCESS) = CarValue(x, 0L, status).okValue()
        assertEquals(0f, v(0f))
        assertEquals(31.3f, v(31.3f))
        assertEquals(119.9f, v(119.9f))
        // 120 m/s (about 270 mph) and above, or negative, is a bad value, not a car.
        assertNull(v(120f))
        assertNull(v(-0.1f))
        assertNull(v(Float.NaN))
        assertNull(v(null))
        for (s in listOf(CarValue.STATUS_UNKNOWN, CarValue.STATUS_UNIMPLEMENTED, CarValue.STATUS_UNAVAILABLE)) assertNull("status $s", v(13f, s))
    }
}

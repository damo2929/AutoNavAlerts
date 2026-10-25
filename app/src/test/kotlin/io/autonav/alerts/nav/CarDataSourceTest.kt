// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.app.Application
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.info.CarSensors
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.diag.DriveRecorder
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The car's own speed and GPS (DESIGN.md §10.6, §10.7) through `CarDataSource.listenTo` on a fake head unit:
 * GPS asked at UPDATE_RATE_FASTEST; a speed report is usable for 3 s and only with a raw value; the dial
 * speed stands in for nothing but the dial; bad statuses, 0,0 fixes and impossible speeds are ignored; "Phone
 * only" hands nothing on; a refused listener is logged and the other still works; stop forgets everything.
 * Runs on the Robolectric main thread, whose boot clock the tests advance. Upholds AGENTS.md §3.2 "car GPS
 * at UPDATE_RATE_FASTEST".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class CarDataSourceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val looper = shadowOf(Looper.getMainLooper())
    private val eventLog = EventLog(app)
    private val car = FakeCar()
    private val source = CarDataSource(eventLog, DriveRecorder(app, eventLog, SettingsStore(app)))
    private val delivered = ArrayList<Location>()

    init {
        source.onLocation = { delivered += it }
    }

    private fun listen() = source.listenTo(car.hardware, car.executor)
    private fun advance(ms: Long) = looper.idleFor(Duration.ofMillis(ms))
    private fun navLines(): List<String> {
        eventLog.flush("test")
        return eventLog.tail(EventLog.Channel.NAV, 200).filter { "CarData" in it }
    }

    @Test fun carGpsIsAskedForAtTheFastestRate() {
        listen()
        assertEquals(CarSensors.UPDATE_RATE_FASTEST, car.locationRate)
        val lines = navLines()
        assertTrue(lines.toString(), lines.any { it.endsWith("listening speed") })
        assertTrue(lines.any { it.endsWith("listening location rate=fastest") })
    }

    @Test fun nothingIsFreshBeforeTheCarReports() {
        listen()
        assertNull(source.freshSpeed())
        assertFalse(source.carLocationFresh())
    }

    @Test fun aSpeedReportIsUsableForThreeSeconds() {
        listen()
        car.sendSpeed(raw = 25f, display = 26f)
        val s = source.freshSpeed()!!
        assertEquals(25f, s.rawMps!!, 0f)
        assertEquals("the dial figure is kept apart, never passed off as measured", 26f, s.displayMps!!, 0f)
        assertEquals(SystemClock.elapsedRealtime(), s.atMs)
        advance(CarDataSource.SPEED_FRESH_MS)
        assertEquals(25f, source.freshSpeed()!!.rawMps!!, 0f)
        advance(1)
        assertNull("stale after 3 s: use the phone", source.freshSpeed())
    }

    @Test fun withNoDialSpeedTheRawStandsInForThePanel() {
        listen()
        car.sendSpeed(raw = 12f, display = null, displayStatus = CarValue.STATUS_UNAVAILABLE)
        assertEquals(12f, source.freshSpeed()!!.displayMps!!, 0f)
    }

    @Test fun aReportWithoutARawSpeedIsNotUsedForTheEngine() {
        listen()
        car.sendSpeed(raw = null, display = 20f, rawStatus = CarValue.STATUS_UNAVAILABLE)
        assertNull(source.freshSpeed())
    }

    @Test fun impossibleAndUnsuccessfulSpeedsAreIgnored() {
        listen()
        car.sendSpeed(raw = 130f, display = 130f)
        assertNull("over 120 m/s is a bad value, not a car", source.freshSpeed())
        car.sendSpeed(raw = -1f, display = -1f)
        assertNull(source.freshSpeed())
        car.sendSpeed(raw = 20f, rawStatus = CarValue.STATUS_UNKNOWN, displayStatus = CarValue.STATUS_UNKNOWN)
        assertNull(source.freshSpeed())
    }

    @Test fun aRealZeroFromTheCarIsKeptAndLoggedOnce() {
        listen()
        car.sendSpeed(raw = 0f, display = 0f)
        car.sendSpeed(raw = 0f, display = 0f)
        assertEquals(0f, source.freshSpeed()!!.rawMps!!, 0f)
        assertEquals(1, navLines().count { "zeroFix raw=0" in it })
    }

    @Test fun aCarFixIsHandedOnAndFreshForThreeSeconds() {
        listen()
        val loc = FakeCar.fix(53.38, -1.47)
        car.sendLocation(loc)
        assertEquals(listOf(loc), delivered)
        assertTrue(source.carLocationFresh())
        advance(CarDataSource.LOCATION_FRESH_MS)
        assertTrue(source.carLocationFresh())
        advance(1)
        assertFalse(source.carLocationFresh())
        assertEquals(1, navLines().count { "firstLocation" in it })
    }

    @Test fun badCarFixesAreIgnored() {
        listen()
        car.sendLocation(FakeCar.fix(53.38, -1.47), status = CarValue.STATUS_UNAVAILABLE)
        car.sendLocation(FakeCar.fix(0.0, 0.0))
        assertTrue(delivered.isEmpty())
        assertFalse(source.carLocationFresh())
        assertTrue(navLines().any { it.endsWith("location 0,0 ignored") })
    }

    @Test fun phoneOnlyHandsNothingOnButStillListens() {
        listen()
        source.enabled = false
        car.sendSpeed(raw = 25f)
        car.sendLocation(FakeCar.fix(53.38, -1.47))
        assertNull(source.freshSpeed())
        assertFalse(source.carLocationFresh())
        assertTrue(delivered.isEmpty())
        // Turned back on, the last report counts again while it is fresh.
        source.enabled = true
        assertEquals(25f, source.freshSpeed()!!.rawMps!!, 0f)
        assertTrue(source.carLocationFresh())
    }

    @Test fun aRefusedSpeedListenerIsLoggedAndLocationStillWorks() {
        car.refuseSpeed = true
        listen()
        assertTrue(navLines().any { "unavailable speed: CAR_SPEED not granted" in it })
        car.sendLocation(FakeCar.fix(53.38, -1.47))
        assertEquals(1, delivered.size)
        source.stop()
        assertEquals("only what the host took is taken off", listOf("location"), car.removed)
    }

    @Test fun stopRemovesTheListenersAndForgetsTheLastReport() {
        listen()
        car.sendSpeed(raw = 25f)
        car.sendLocation(FakeCar.fix(53.38, -1.47))
        source.stop()
        assertEquals(listOf("speed", "location"), car.removed)
        assertNull("a stale car speed can't feed the next session", source.freshSpeed())
        assertFalse(source.carLocationFresh())
        source.stop()
        assertEquals("safe to stop twice", 2, car.removed.size)
    }
}

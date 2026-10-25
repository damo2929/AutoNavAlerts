// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.app.Application
import android.location.Location
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.diag.DriveRecorder
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The location feeds' arbitration (DESIGN.md §10.2, §10.6, §10.7) with a fake head unit behind the real
 * `CarDataSource`: the phone asked once at 1 Hz with no minimum interval (never forced faster, any faster fix
 * taken); the car's GPS used at once while it is the live feed and the phone then only recorded; the phone
 * taking over 3 s after the car goes quiet; the car taking back only on its second fix in a row; every flip
 * logged and the track reset; the car's raw speed preferred while fresh; the delivered rate in
 * `LocationStats`; a simulated drive ignoring both real feeds. Phone fixes are handed to the fused provider's
 * callback directly; the boot clock is the Robolectric main looper's. Upholds AGENTS.md §3.2 "phone GPS at
 * the rate the phone offers, never forced faster".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class AdaptiveLocationSourceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val looper = shadowOf(Looper.getMainLooper())
    private val eventLog = EventLog(app)
    private val recorder = DriveRecorder(app, eventLog, SettingsStore(app))
    private val car = FakeCar()
    private val carData = CarDataSource(eventLog, recorder).also { it.listenTo(car.hardware, car.executor) }
    private val source = AdaptiveLocationSource(app, carData, eventLog, recorder).also { it.start() }

    @After fun stop() = source.stop()

    private var north = 0.0
    /** A fix 25 m further north than the last one: a car moving at 25 m/s on a one-second feed. */
    private fun next(provider: String): Location { north += 25.0; return FakeCar.fix(53.38 + north / 111_195.0, -1.47, provider) }

    private fun phone(loc: Location = next("phone")): Location { source.callback.onLocationResult(LocationResult.create(listOf(loc))); return loc }
    private fun carFix(loc: Location = next("car")): Location { car.sendLocation(loc); return loc }
    private fun advance(ms: Long) = looper.idleFor(Duration.ofMillis(ms))
    private fun navLines(): List<String> {
        eventLog.flush("test")
        return eventLog.tail(EventLog.Channel.NAV, 300).filter { " Location " in it }
    }

    @Test fun thePhoneIsAskedForOneHertzAndAnyFasterFixIsAccepted() {
        val req = AdaptiveLocationSource.phoneRequest()
        assertEquals(1, AdaptiveLocationSource.GPS_HZ)
        assertEquals(1_000L, req.intervalMillis)
        assertEquals("never drops a faster fix the phone offers", 0L, req.minUpdateIntervalMillis)
        // setMaxUpdateDelayMillis(0) reads back as the interval: Play services' way of saying "no batching".
        assertEquals(req.intervalMillis, req.maxUpdateDelayMillis)
        assertFalse(req.isBatched)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, req.priority)
        assertFalse(req.isWaitForAccurateLocation)
        assertEquals(1, source.stats.value.requestedHz)
        assertTrue(navLines().any { it.endsWith("request interval=1000ms min=0") })
    }

    @Test fun theRequestIsSentOnceAndNeverChangedWhileRunning() {
        source.start()
        assertEquals(1, navLines().count { "request interval=" in it })
    }

    @Test fun phoneFixesFeedTheStatsAtTheRateTheyArrive() {
        repeat(4) { phone(); advance(1_000) }
        assertEquals(1.0, source.stats.value.actualHz, 0.01)
        // A phone that delivers faster is taken at its own rate, never throttled.
        repeat(6) { phone(); advance(200) }
        assertTrue(source.stats.value.actualHz > 1.5)
        assertFalse(source.stats.value.locationFromCar)
        assertEquals("gps", source.stats.value.speedSrc)
        assertEquals(20.0 / Geo.MPS_PER_MPH, source.stats.value.speedMph, 0.01)
    }

    @Test fun aCarFixIsUsedAtOnceWhileTheCarIsTheLiveFeed() {
        val loc = carFix()
        assertSame(loc, source.lastLocation.value)
        assertTrue(source.stats.value.locationFromCar)
        assertTrue(navLines().any { it.endsWith("source car") })
    }

    @Test fun phoneFixesAreOnlyABackupWhileTheCarIsFresh() {
        val c = carFix()
        advance(1_000)
        phone()
        assertSame("the phone is recorded, not used", c, source.lastLocation.value)
        advance(1_000)
        val c2 = carFix()
        assertSame(c2, source.lastLocation.value)
    }

    @Test fun thePhoneTakesOverThreeSecondsAfterTheCarGoesQuiet() {
        carFix()
        advance(2_000)
        val stillIgnored = phone()
        assertTrue(source.stats.value.locationFromCar)
        advance(1_100)
        val p = phone()
        assertSame(p, source.lastLocation.value)
        assertFalse(source.stats.value.locationFromCar)
        val lines = navLines()
        assertTrue(lines.toString(), lines.any { it.endsWith("source car→phone (+3100 ms)") })
        assertTrue(lines.any { it.endsWith("TRACK_RESET reason=handover car→phone") })
        assertTrue(stillIgnored !== source.lastLocation.value)
    }

    @Test fun theCarTakesBackOnlyOnItsSecondFixInARow() {
        val p = phone()
        advance(1_000)
        carFix()
        assertSame("one car fix after the phone took over is not enough", p, source.lastLocation.value)
        advance(1_000)
        val c = carFix()
        assertSame(c, source.lastLocation.value)
        assertTrue(source.stats.value.locationFromCar)
        assertTrue(navLines().any { it.contains("source phone→car") })
    }

    @Test fun carFixesTooFarApartDoNotCountAsARow() {
        phone()
        advance(1_000)
        val first = carFix()
        advance(3_500)
        carFix()
        assertTrue("a 3.5 s gap restarts the streak", source.lastLocation.value !== first)
        assertFalse(source.stats.value.locationFromCar)
    }

    @Test fun theCarsRawSpeedIsTheEnginesSpeedWhileFresh() {
        car.sendSpeed(raw = 30f, display = 31f)
        phone()
        val s = source.stats.value
        assertTrue(s.speedFromCar)
        assertEquals("car_raw", s.speedSrc)
        assertEquals(30.0 / Geo.MPS_PER_MPH, s.speedMph, 0.01)
        assertEquals("the dial shows the car's display speed", 31.0 / Geo.MPS_PER_MPH, s.dialSpeedMph!!, 0.01)
        advance(3_100)
        phone()
        assertFalse("stale after 3 s: the phone's speed", source.stats.value.speedFromCar)
        assertNull(source.stats.value.dialSpeedMph)
    }

    @Test fun aSimulatedDriveIgnoresBothRealFeeds() {
        source.setSimulated(true)
        val real = phone()
        carFix()
        assertNull(source.lastLocation.value)
        val sim = next("sim")
        source.inject(sim)
        assertSame(sim, source.lastLocation.value)
        assertTrue(real !== source.lastLocation.value)
        assertTrue(navLines().any { it.endsWith("TRACK_RESET reason=simulation on") })
        source.setSimulated(false)
        val after = phone()
        assertSame(after, source.lastLocation.value)
        // A late simulator callback after the drive can't move the car.
        source.inject(next("sim"))
        assertSame(after, source.lastLocation.value)
    }

    @Test fun poorAccuracyIsLoggedOnceEachWay() {
        phone(FakeCar.fix(53.38, -1.47, "phone", accuracyM = 80f))
        advance(1_000)
        phone(FakeCar.fix(53.3802, -1.47, "phone", accuracyM = 90f))
        advance(1_000)
        phone(FakeCar.fix(53.3804, -1.47, "phone", accuracyM = 5f))
        val lines = navLines()
        assertEquals(1, lines.count { "accuracy>50m acc=80" in it })
        assertEquals(1, lines.count { "accuracy ok acc=5" in it })
        assertEquals(5.0, source.stats.value.accuracyM!!, 0.0)
    }

    @Test fun aGapOverFiveSecondsIsLogged() {
        phone()
        advance(6_000)
        phone()
        assertTrue(navLines().any { it.endsWith("gap ms=6000") })
    }

    @Test fun stopUnhooksTheCarFeed() {
        source.stop()
        assertNull(carData.onLocation)
        carFix()
        assertNull(source.lastLocation.value)
    }
}

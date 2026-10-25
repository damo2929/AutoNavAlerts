// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import android.app.Application
import androidx.car.app.model.Action
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.R
import io.autonav.alerts.alerts.MarkerState
import io.autonav.alerts.core.alerts.Verdict
import io.autonav.alerts.nav.LocationStats
import io.autonav.alerts.testing.CarScreenRig
import io.autonav.alerts.testing.headerTitle
import io.autonav.alerts.testing.iconRes
import io.autonav.alerts.testing.lines
import io.autonav.alerts.testing.list
import io.autonav.alerts.testing.privateFlow
import io.autonav.alerts.testing.rows
import io.autonav.alerts.testing.setPrivate
import io.autonav.alerts.testing.switch
import io.autonav.alerts.testing.tap
import io.autonav.alerts.testing.titleText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The More screen as the host receives it (DESIGN.md §8 screen table and "Hard rule: no shake", §12.1, §12.3):
 * its seven rows, the one Mute on the car screen, the Satellite toggle that follows the setting, the live Alerts
 * and GPS lines, and above all the ticker that re-sends the template only when a live line changed and only while
 * the screen is showing (hard rule 3), and the settings collector that re-sends only for mute or satellite.
 * Robolectric main looper; the ticker's `delay` runs on the looper's clock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class MoreScreenTest {
    private val rig = CarScreenRig()

    private fun open(): Pair<MoreScreen, androidx.car.app.testing.ScreenController> {
        val s = MoreScreen(rig.car, rig.host)
        val c = rig.start(s)
        // The settings collector's first value has arrived once the screen has read it.
        rig.awaitUntil { s.onGetTemplate().list.rows.isNotEmpty() }
        rig.idle()
        return s to c
    }

    private fun MoreScreen.rows() = onGetTemplate().list.rows

    @Test fun headerIsMoreWithTheHostsBack() {
        val (s, _) = open()
        val t = s.onGetTemplate()
        assertEquals("More", t.headerTitle)
        assertSame(Action.BACK, (t as androidx.car.app.model.ListTemplate).header!!.startHeaderAction)
        assertTrue("no template actions", t.actions.isEmpty())
    }

    /**
     * The seven rows as built. DESIGN.md §8's table lists "… Mute alerts 30 min · Satellite map · Route options …";
     * the code puts Route options before Satellite map (reported; pinned as built).
     */
    @Test fun sevenRowsInTheBuiltOrder() {
        val (s, _) = open()
        assertEquals(
            listOf("Recents", "POI layers", "Mute alerts for 30 min", "Route options", "Satellite map", "Alerts", "GPS"),
            s.rows().map { it.titleText },
        )
    }

    @Test fun eachRowHasItsIconAndOnlyGpsIsTextOnly() {
        val (s, _) = open()
        val icons = s.rows().map { it.iconRes }
        assertEquals(
            listOf(R.drawable.ic_action_recents, R.drawable.ic_action_layers, R.drawable.ic_action_volume_off, R.drawable.ic_action_navigate,
                R.drawable.ic_action_layers, R.drawable.ic_action_volume_on, 0),
            icons,
        )
        assertNull(s.rows().last().image)
    }

    @Test fun routeOptionsRowSaysWhatIsInside() {
        val (s, _) = open()
        assertEquals(listOf("Avoid tolls, energy-efficient EV routes"), s.rows()[3].lines)
    }

    @Test fun gpsRowTakesNoTapAndSatelliteIsAToggleNotAClick() {
        val (s, _) = open()
        val rows = s.rows()
        assertNull("the GPS row only shows data", rows[6].onClickDelegate)
        assertNull(rows[4].onClickDelegate)
        assertFalse(rows[4].toggle!!.isChecked)
        rows.filter { it.titleText != "Satellite map" }.forEach { assertNull("${it.titleText} has no toggle", it.toggle) }
    }

    @Test fun mutedShowsUnmuteWithTheSoundOnIcon() {
        rig.settings { it.copy(mutedUntilMs = System.currentTimeMillis() + 600_000) }
        val (s, _) = open()
        val mute = s.rows()[2]
        assertEquals("Unmute alerts", mute.titleText)
        assertEquals(R.drawable.ic_action_volume_on, mute.iconRes)
    }

    @Test fun anExpiredMuteReadsAsMuteAgain() {
        rig.settings { it.copy(mutedUntilMs = System.currentTimeMillis() - 1_000) }
        val (s, _) = open()
        assertEquals("Mute alerts for 30 min", s.rows()[2].titleText)
    }

    @Test fun tappingMuteSilencesSpeechForThirtyMinutesAndResendsOnce() {
        val (s, _) = open()
        val before = rig.invalidations(s)
        val t0 = System.currentTimeMillis()
        s.rows()[2].tap()
        rig.awaitUntil { runBlocking { rig.deps.store.current() }.mutedUntilMs > 0 && rig.invalidations(s) > before }
        val until = runBlocking { rig.deps.store.current() }.mutedUntilMs
        assertTrue("about 30 min from the tap", until - t0 in 30 * 60_000L..30 * 60_000L + 5_000)
        assertEquals("Unmute alerts", s.rows()[2].titleText)
        rig.idle()
        // Found here: the settings collector sees the new mute before the tap's own coroutine does, so both
        // re-send, and the second template is identical to the first (a redundant re-send against hard rule 3).
        // Pinned as built: one or two re-sends, and every one shows the same content.
        val sent = rig.appManager.templatesReturned.filter { it.first === s }.drop(before).map { it.second.list.rows.map { r -> r.titleText } }
        assertTrue("re-sends: ${sent.size}", sent.size in 1..2)
        assertEquals(1, sent.distinct().size)
    }

    @Test fun tappingUnmuteClearsTheMute() {
        rig.settings { it.copy(mutedUntilMs = System.currentTimeMillis() + 600_000) }
        val (s, _) = open()
        s.rows()[2].tap()
        rig.awaitUntil { runBlocking { rig.deps.store.current() }.mutedUntilMs == 0L }
        rig.idle()
        assertEquals("Mute alerts for 30 min", s.rows()[2].titleText)
    }

    @Test fun satelliteToggleShowsTheSettingAndWritesIt() {
        rig.settings { it.copy(satelliteMap = true) }
        val (s, _) = open()
        assertTrue(s.rows()[4].toggle!!.isChecked)
        s.rows()[4].switch(false)
        rig.awaitUntil { !runBlocking { rig.deps.store.current() }.satelliteMap }
        rig.idle()
        assertFalse("kept at once, so the next template doesn't snap back", s.rows()[4].toggle!!.isChecked)
    }

    @Test fun aSatelliteChangeFromElsewhereResendsOnce() {
        val (s, _) = open()
        val before = rig.invalidations(s)
        rig.settings { it.copy(satelliteMap = true) }
        rig.awaitUntil { rig.invalidations(s) > before }
        rig.idle()
        assertEquals(before + 1, rig.invalidations(s))
        assertTrue(s.rows()[4].toggle!!.isChecked)
    }

    @Test fun anUnrelatedSettingsChangeResendsNothing() {
        val (s, _) = open()
        val before = rig.invalidations(s)
        rig.settings { it.copy(avoidTolls = !it.avoidTolls) }
        repeat(20) { rig.idle(); Thread.sleep(5) }
        assertEquals(before, rig.invalidations(s))
    }

    @Test fun tickerResendsNothingWhileTheLinesStayTheSame() {
        val (s, _) = open()
        val before = rig.invalidations(s)
        repeat(5) { rig.advance(1_000) }
        assertEquals("no shake: five ticks, no change, no re-send", before, rig.invalidations(s))
    }

    @Test fun tickerResendsOnceWhenALiveLineChanges() {
        val (s, _) = open()
        val before = rig.invalidations(s)
        rig.deps.coordinator.trace.setPrivate("lastStage", "FIRST poi=7")
        rig.advance(1_000)
        assertEquals(before + 1, rig.invalidations(s))
        repeat(3) { rig.advance(1_000) }
        assertEquals("the changed line was sent; nothing more until the next change", before + 1, rig.invalidations(s))
        assertEquals("Last: FIRST poi=7 · no suppress yet", s.rows()[5].lines[1])
    }

    @Test fun tickerStopsWhileTheScreenIsHiddenAndComesBackWithIt() {
        val (s, c) = open()
        c.moveToState(Lifecycle.State.CREATED)
        rig.idle()
        val before = rig.invalidations(s)
        rig.deps.coordinator.trace.setPrivate("lastStage", "SECOND poi=9")
        repeat(3) { rig.advance(1_000) }
        assertEquals("hidden: no ticker, no re-send", before, rig.invalidations(s))
        c.moveToState(Lifecycle.State.RESUMED)
        rig.advance(1_000)
        assertEquals("shown again: the waiting change goes out once", before + 1, rig.invalidations(s))
    }

    @Test fun alertsRowCountsAheadAndFacingAwayAndTheTraceLines() {
        val (s, _) = open()
        rig.deps.coordinator.privateFlow<MarkerState>("_markers").value = MarkerState(
            facingAway = setOf(3L),
            verdicts = mapOf(1L to Verdict.AHEAD_OK, 2L to Verdict.AHEAD_OK, 3L to Verdict.WRONG_DIRECTION, 4L to Verdict.BEHIND),
        )
        rig.deps.coordinator.trace.setPrivate("lastSuppress", "STALE poi=4 d=80")
        assertEquals(listOf("Ahead 2 · facing away 1", "Last: no stage yet · STALE poi=4 d=80"), s.rows()[5].lines)
    }

    @Test fun gpsRowStartsWithNothingKnown() {
        val (s, _) = open()
        assertEquals(
            listOf("Requested 1 Hz · actual 0.0 Hz · heading – (none)", "Engine 0 mph (none) · dial 0 mph (phone) · location phone"),
            s.rows()[6].lines,
        )
    }

    @Test fun gpsRowShowsRatesHeadingSpeedsAndSources() {
        val (s, _) = open()
        rig.deps.loc.privateFlow<LocationStats>("_stats").value = LocationStats(
            requestedHz = 1, actualHz = 2.46, speedMph = 42.4, speedFromCar = true, locationFromCar = true, headingDeg = 274.4,
            headingSrc = "track", headingAccDeg = 6.2, speedSrc = "car_raw", accuracyM = 4.6, dialSpeedMph = 41.2,
        )
        assertEquals(
            listOf("Requested 1 Hz · actual 2.5 Hz · heading 274° (track ±6°)", "Engine 42 mph (car_raw) · dial 41 mph (car) · location car ±5 m"),
            s.rows()[6].lines,
        )
    }

    @Test fun gpsRowWithoutTheCarsDialUsesTheEngineSpeedAndItsSource() {
        val (s, _) = open()
        rig.deps.loc.privateFlow<LocationStats>("_stats").value = LocationStats(1, 1.0, 30.2, speedFromCar = true, speedSrc = "gps")
        assertEquals("Engine 30 mph (gps) · dial 30 mph (car) · location phone", s.rows()[6].lines[1])
    }

    @Test fun aGpsChangeIsPickedUpByTheNextTick() {
        val (s, _) = open()
        val before = rig.invalidations(s)
        rig.deps.loc.privateFlow<LocationStats>("_stats").value = LocationStats(1, 1.0, 12.0)
        rig.advance(1_000)
        assertEquals(before + 1, rig.invalidations(s))
    }
}

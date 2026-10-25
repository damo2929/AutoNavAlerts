// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.core.alerts.AlertEvent
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.alerts.SpeechScheduler.Outcome
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.diag.EventLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration

/**
 * Alert routing (DESIGN.md §12, §12.2, §12.3): the car screen through the sink when it takes the alert, else
 * a navigation-category heads-up notification with a CarAppExtender on the `poi_alerts` channel (high
 * importance, silent, no vibration), one id per POI so later stages update it, nothing posted without
 * POST_NOTIFICATIONS; speech through `NavTts` with the chime only for PASSING-priority events, or a MUTED
 * record when speech is off, so an event is never dropped. Real `NavTts`, `IconRepository` and `EventLog` on
 * Robolectric's shadows; runs on the paused main looper. Upholds AGENTS.md §3.2 "events are never dropped"
 * and "mute silences speech only".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class AlertDispatcherTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val looper = shadowOf(Looper.getMainLooper())
    private val tts = NavTts(app, EventLog(app))
    private val dispatcher = AlertDispatcher(app, tts, IconRepository(app), EventLog(app))
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val shown = ArrayList<Long>()

    private fun alert(poiId: Long = 42, display: String = "Speed camera 30 mph · 300 yd") =
        ActiveAlert(poiId, AlertKind.FIRST, display, "Speed camera in 300 yards", IconRepository.CAMERA_FIXED, 274.0, 30, 1, 0L)

    private fun event(kind: AlertKind, poiId: Long = 42, priority: Int = kind.priority, staleBelowM: Double? = 130.0) =
        AlertEvent(poiId, kind, 274.0, false, priority, staleBelowM = staleBelowM)

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    /** Brings the engine up with a silent progress listener and returns NavTts's own, so the test ends each utterance. */
    private fun readyEngine(): UtteranceProgressListener {
        tts.warmUp()
        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()!!
        shadowOf(engine).onInitListener.onInit(TextToSpeech.SUCCESS)
        looper.idle()
        val progress = shadowOf(engine).utteranceProgressListener
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = Unit
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = Unit
        })
        return progress
    }

    private fun spoken(): List<String> = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()!!).spokenTextList

    @Test fun theCarScreenTakesTheAlertAndNoNotificationIsPosted() {
        grant()
        dispatcher.carSink = CarAlertSink { shown += it.poiId; true }
        dispatcher.dispatch(alert(), event(AlertKind.FIRST), speak = true, chime = false)
        looper.idle()
        assertEquals(listOf(42L), shown)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun withNoCarScreenTheHeadsUpIsANavigationNotificationForTheCar() {
        grant()
        dispatcher.dispatch(alert(), event(AlertKind.FIRST), speak = true, chime = false)
        val n: Notification = shadowOf(nm).getNotification(20_042)!!
        assertEquals(NotificationCompat.CATEGORY_NAVIGATION, n.category)
        assertEquals(AlertDispatcher.CHANNEL, n.channelId)
        assertEquals("Speed camera 30 mph · 300 yd", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("gone after 10 s, a little after the banner's 8 s", 10_000L, n.timeoutAfter)
        assertNotNull(n.getLargeIcon())
        val car = CarAppExtender(n)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, car.importance)
        assertEquals("Speed camera 30 mph · 300 yd", car.contentTitle.toString())
        assertNotNull(car.largeIcon)
        assertTrue(car.smallIcon != 0)
        // As built (DESIGN §12.2 lists more): no content text, no content intent, no Mute action on the car card.
        assertNull(car.contentText)
        assertNull(car.contentIntent)
        assertTrue(car.actions.isEmpty())
    }

    @Test fun theChannelIsHighImportanceSilentAndStill() {
        grant()
        dispatcher.dispatch(alert(), event(AlertKind.FIRST), speak = true, chime = false)
        val ch = nm.getNotificationChannel(AlertDispatcher.CHANNEL)!!
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        assertNull("the TTS does the speaking", ch.sound)
        assertFalse(ch.shouldVibrate())
        assertEquals("Speed camera and POI alerts", ch.description)
    }

    @Test fun ensureChannelMakesTheChannelWithoutAnyAlert() {
        dispatcher.ensureChannel()
        assertNotNull(nm.getNotificationChannel(AlertDispatcher.CHANNEL))
    }

    @Test fun laterStagesForOnePoiUpdateOneNotificationAndAnotherPoiGetsItsOwn() {
        grant()
        dispatcher.dispatch(alert(display = "Camera 500 yd"), event(AlertKind.FIRST), speak = true, chime = false)
        dispatcher.dispatch(alert(display = "Camera 150 yd"), event(AlertKind.SECOND), speak = true, chime = false)
        dispatcher.dispatch(alert(display = "Camera passing"), event(AlertKind.PASSING), speak = true, chime = false)
        assertEquals(1, shadowOf(nm).allNotifications.size)
        assertEquals("Camera passing", shadowOf(nm).getNotification(20_042)!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        dispatcher.dispatch(alert(poiId = 10_042, display = "Other"), event(AlertKind.FIRST, poiId = 10_042), speak = true, chime = false)
        assertEquals("ids wrap at 10,000 (§12.2: two POIs 10,000 apart share one)", 1, shadowOf(nm).allNotifications.size)
        dispatcher.dispatch(alert(poiId = 7, display = "Seven"), event(AlertKind.FIRST, poiId = 7), speak = true, chime = false)
        assertEquals(2, shadowOf(nm).allNotifications.size)
        assertNotNull(shadowOf(nm).getNotification(20_007))
    }

    @Test fun aSinkThatDeclinesOrThrowsFallsBackToTheNotification() {
        grant()
        dispatcher.carSink = CarAlertSink { false }
        dispatcher.dispatch(alert(poiId = 1), event(AlertKind.FIRST, poiId = 1), speak = true, chime = false)
        dispatcher.carSink = CarAlertSink { error("surface gone") }
        dispatcher.dispatch(alert(poiId = 2), event(AlertKind.FIRST, poiId = 2), speak = true, chime = false)
        assertEquals(2, shadowOf(nm).allNotifications.size)
    }

    @Test fun withoutNotificationPermissionNothingIsPostedButTheAlertIsStillSpoken() {
        val progress = readyEngine()
        dispatcher.dispatch(alert(), event(AlertKind.FIRST), speak = true, chime = false)
        looper.idle()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertEquals(listOf("Speed camera in 300 yards"), spoken())
        progress.onDone("autonav-1")
        looper.idle()
        assertEquals(Outcome.SPOKEN, tts.records.value.single().outcome)
    }

    @Test fun speechOffRecordsMutedAndStillShowsTheCard() {
        grant()
        dispatcher.dispatch(alert(), event(AlertKind.PASSING), speak = false, chime = true)
        looper.idle()
        assertNotNull("mute silences speech only: the card still shows", shadowOf(nm).getNotification(20_042))
        val r = tts.records.value.single()
        assertEquals(Outcome.MUTED, r.outcome)
        assertEquals(AlertKind.PASSING, r.utterance.kind)
        assertEquals("Speed camera in 300 yards", r.utterance.text)
        assertEquals(274.0, r.utterance.distanceM, 0.0)
    }

    @Test fun theUtteranceCarriesTheEventsKindPriorityDistanceAndStaleness() {
        val progress = readyEngine()
        dispatcher.dispatch(alert(), event(AlertKind.SECOND, staleBelowM = 130.0), speak = true, chime = false)
        looper.idle()
        progress.onDone("autonav-1")
        looper.idle()
        val u = tts.records.value.single().utterance
        assertEquals(AlertKind.SECOND, u.kind)
        assertEquals(42L, u.poiId)
        assertEquals(AlertKind.SECOND.priority, u.priority)
        assertEquals(274.0, u.distanceM, 0.0)
        assertEquals(130.0, u.staleBelowM!!, 0.0)
    }

    @Test fun theChimeIsOnlyForPassingPriorityEventsWithTheSettingOn() {
        val progress = readyEngine()
        val cases = listOf(
            Triple(AlertKind.PASSING, true, true),
            Triple(AlertKind.PASSING, false, false),
            Triple(AlertKind.FIRST, true, false),
            Triple(AlertKind.SECOND, true, false),
        )
        for ((i, c) in cases.withIndex()) {
            val (kind, setting, expected) = c
            val id = i + 1L
            dispatcher.dispatch(alert(poiId = id), event(kind, poiId = id), speak = true, chime = setting)
            looper.idle()
            // With the chime, the words wait 250 ms for it.
            assertEquals("$kind chime=$setting", expected, spoken().size < i + 1)
            looper.idleFor(Duration.ofMillis(250))
            assertEquals(i + 1, spoken().size)
            progress.onDone("autonav-$id")
            looper.idle()
            assertEquals(expected, tts.records.value.last().utterance.chime)
            looper.idleFor(Duration.ofMillis(400))
        }
    }
}

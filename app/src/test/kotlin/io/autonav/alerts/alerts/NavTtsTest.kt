// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.alerts.SpeechScheduler.Outcome
import io.autonav.alerts.diag.EventLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/**
 * `NavTts`, the Android side of the speech policy (DESIGN.md §12.3), on Robolectric's TextToSpeech and
 * AudioManager shadows: focus is AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK with no pause when ducked, on the
 * navigation-guidance usage, taken before speaking and given back once nothing waits; mute stops alert
 * speech only and leaves a direction alone; the chime delays the words by 250 ms; engine errors retry once,
 * then fall back to the chime with one toast; a failed or dead engine is dropped and made again; every
 * outcome is recorded and reported to the coordinator. The engine's callbacks are driven by hand; everything
 * runs on the paused main looper. Upholds AGENTS.md §3.2 "media is ducked, never paused" and "mute
 * silences speech only".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class NavTtsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val looper = shadowOf(Looper.getMainLooper())
    private val audio = shadowOf(app.getSystemService(AudioManager::class.java))
    private val tts = NavTts(app, EventLog(app))
    private val outcomes = ArrayList<String>()

    init {
        tts.spokenCallback = { poiId, kind, spoken, _, outcome -> outcomes += "$kind:$poiId:$spoken:$outcome" }
    }

    private fun engine(): TextToSpeech = ShadowTextToSpeech.getLastTextToSpeechInstance()!!
    private fun shadowEngine() = shadowOf(engine())

    /** NavTts's own progress listener; the shadow engine gets a silent one so the test decides when speech ends. */
    private lateinit var progress: UtteranceProgressListener

    /** Reports the current engine ready, as the platform does once its service is bound, and takes over its callbacks. */
    private fun initEngine() {
        shadowEngine().onInitListener.onInit(TextToSpeech.SUCCESS)
        looper.idle()
        progress = shadowEngine().utteranceProgressListener
        engine().setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = Unit
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = Unit
        })
    }

    private fun ready() {
        tts.warmUp()
        initEngine()
    }

    private fun say(text: String, kind: AlertKind = AlertKind.FIRST, poiId: Long = 1, priority: Int = kind.priority, chime: Boolean = false) {
        tts.speak(text, priority, poiId, chime, kind, 300.0, null)
        looper.idle()
    }

    private fun done(id: Long) { progress.onDone("autonav-$id"); looper.idle() }
    private fun started(id: Long) { progress.onStart("autonav-$id"); looper.idle() }
    private fun failed(id: Long, code: Int = TextToSpeech.ERROR_SYNTHESIS) { progress.onError("autonav-$id", code); looper.idle() }
    private fun outcomesOf() = tts.records.value.map { "${it.utterance.kind}:${it.utterance.poiId}:${it.outcome}" }

    @Test fun focusIsTransientMayDuckOnTheGuidanceChannelAndNeverPausesMedia() {
        ready()
        say("Speed camera in 300 yards")
        val req = audio.lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, req.focusGain)
        assertFalse("media is ducked, never paused", req.willPauseWhenDucked())
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, req.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, req.audioAttributes.contentType)
        assertEquals("Speed camera in 300 yards", shadowEngine().lastSpokenText)
        assertEquals("the scheduler is the only queue", TextToSpeech.QUEUE_FLUSH, shadowEngine().queueMode)
    }

    @Test fun focusIsGivenBackAfterTheLastUtteranceOnly() {
        ready()
        say("First camera", poiId = 1)
        say("Second camera", poiId = 2)
        val req = audio.lastAudioFocusRequest.audioFocusRequest
        started(1)
        done(1)
        assertNull("the second still waits: focus kept, no flapping", audio.lastAbandonedAudioFocusRequest)
        assertEquals("not yet: the 400 ms quiet gap", "First camera", shadowEngine().lastSpokenText)
        looper.idleFor(Duration.ofMillis(400))
        assertNull(audio.lastAbandonedAudioFocusRequest)
        assertEquals("Second camera", shadowEngine().lastSpokenText)
        assertSame("the held focus is reused, not asked again", req, audio.lastAudioFocusRequest.audioFocusRequest)
        started(2)
        done(2)
        assertSame(req, audio.lastAbandonedAudioFocusRequest)
        assertEquals(listOf("FIRST:1:SPOKEN", "FIRST:2:SPOKEN"), outcomesOf())
        assertEquals(listOf("FIRST:1:true:SPOKEN", "FIRST:2:true:SPOKEN"), outcomes)
    }

    @Test fun aRefusedFocusIsRecordedAndAskedAgainNextTime() {
        ready()
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        say("Camera")
        assertEquals("it still speaks", "Camera", shadowEngine().lastSpokenText)
        assertEquals(listOf("FIRST:1:FOCUS_DENIED"), outcomesOf())
        started(1)
        done(1)
        assertNull("nothing was held, nothing to give back", audio.lastAbandonedAudioFocusRequest)
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        say("Next", poiId = 2)
        looper.idleFor(Duration.ofMillis(400))
        assertEquals("Next", shadowEngine().lastSpokenText)
        done(2)
        assertNotNull(audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun speechQueuedBeforeTheEngineIsReadyIsSpokenOnceItIs() {
        say("Early camera")
        assertTrue(shadowEngine().spokenTextList.isEmpty())
        shadowEngine().onInitListener.onInit(TextToSpeech.SUCCESS)
        looper.idle()
        assertEquals(listOf("Early camera"), shadowEngine().spokenTextList)
        assertTrue(tts.healthy.value)
    }

    @Test fun warmUpIsIdempotent() {
        tts.warmUp()
        val first = engine()
        tts.warmUp()
        say("x")
        assertSame(first, engine())
    }

    @Test fun theChimeComesFirstAndTheWords250MsLater() {
        ready()
        say("Passing camera", AlertKind.PASSING, chime = true)
        assertTrue("words wait for the chime", shadowEngine().spokenTextList.isEmpty())
        looper.idleFor(Duration.ofMillis(250))
        assertEquals(listOf("Passing camera"), shadowEngine().spokenTextList)
    }

    @Test fun muteStopsAlertSpeechAndDropsWhatWaitsPassingIncluded() {
        ready()
        say("First camera", poiId = 1)
        started(1)
        say("Passing", AlertKind.PASSING, poiId = 2)
        tts.muteNow()
        looper.idle()
        assertTrue(shadowEngine().isStopped)
        assertEquals(setOf("FIRST:1:MUTED", "PASSING:2:MUTED"), outcomesOf().toSet())
        assertEquals("nothing left to say", listOf("First camera"), shadowEngine().spokenTextList)
        assertNotNull("focus given back", audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun muteLeavesADirectionSpeaking() {
        ready()
        say("In 300 yards, turn left", AlertKind.GUIDANCE, poiId = -2)
        started(1)
        tts.muteNow()
        looper.idle()
        assertFalse("a direction is not an alert: not stopped", shadowEngine().isStopped)
        assertTrue(outcomesOf().isEmpty())
        done(1)
        assertEquals(listOf("GUIDANCE:-2:SPOKEN"), outcomesOf())
    }

    @Test fun aMutedAlertIsRecordedNotSpoken() {
        ready()
        tts.recordMuted(5, AlertKind.PASSING, "Passing camera", 20.0)
        looper.idle()
        assertTrue(shadowEngine().spokenTextList.isEmpty())
        assertEquals(listOf("PASSING:5:MUTED"), outcomesOf())
        assertEquals(listOf("PASSING:5:false:MUTED"), outcomes)
        assertEquals("Passing camera", tts.records.value.single().utterance.text)
    }

    @Test fun anEngineErrorIsRetriedOnceThenChimedWithOneToast() {
        ready()
        say("Camera")
        failed(1)
        assertEquals("retried", listOf("Camera", "Camera"), shadowEngine().spokenTextList)
        assertTrue(tts.healthy.value)
        failed(1)
        assertEquals(listOf("FIRST:1:TTS_ERROR"), outcomesOf())
        assertFalse("directions go back to the SDK's voice", tts.healthy.value)
        assertEquals("Speech failed: alerts are shown and chimed", ShadowToast.getTextOfLatestToast())
        // A second failure doesn't toast again.
        say("Another", poiId = 2)
        repeat(2) { failed(2) }
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test fun aCallbackForAnEarlierUtteranceIsIgnored() {
        ready()
        say("Camera")
        done(99)
        assertTrue(outcomesOf().isEmpty())
    }

    @Test fun twoErrorsInARowDropTheEngineAndMakeANewOne() {
        ready()
        val old = engine()
        say("Camera")
        // A dead TTS service: two errors before anything else runs.
        progress.onError("autonav-1", TextToSpeech.ERROR_SERVICE)
        progress.onError("autonav-1", TextToSpeech.ERROR_SERVICE)
        looper.idle()
        assertTrue("the broken engine is shut down", shadowOf(old).isShutdown)
        assertNotSame("and a new one is made", old, engine())
        assertEquals(listOf("FIRST:1:TTS_ERROR"), outcomesOf())
        initEngine()
        assertTrue("healthy again once the new engine is up", tts.healthy.value)
        say("Next", poiId = 2)
        assertEquals("Next", shadowEngine().lastSpokenText)
    }

    @Test fun aFailedStartChimesWhatWaitsAndRetriesTheEngine() {
        tts.warmUp()
        val first = engine()
        say("Camera")
        shadowOf(first).onInitListener.onInit(TextToSpeech.ERROR)
        looper.idle()
        assertFalse(tts.healthy.value)
        assertTrue(shadowOf(first).isShutdown)
        assertEquals("never left queued and silent", listOf("FIRST:1:TTS_ERROR"), outcomesOf())
        assertTrue(tts.records.value.single().detail!!.contains("-100"))
        looper.idleFor(Duration.ofSeconds(5))
        assertNotSame("tried again after 5 s", first, engine())
        shadowEngine().onInitListener.onInit(TextToSpeech.SUCCESS)
        looper.idle()
        assertTrue(tts.healthy.value)
    }

    @Test fun theSnapshotNamesWhatIsSpeakingAndWaiting() {
        ready()
        say("First camera", poiId = 1)
        say("Second camera", AlertKind.SECOND, poiId = 2)
        val s = tts.snapshot()
        assertTrue(s, s.startsWith("tts ready=true speaking=FIRST poi=1 \"First camera\" waiting=1"))
        assertTrue(s, s.contains("SECOND poi=2"))
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.alerts.SpeechScheduler.Decision
import io.autonav.alerts.core.alerts.SpeechScheduler.Outcome
import io.autonav.alerts.core.alerts.SpeechScheduler.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speech queue policy (DESIGN.md §12.3). */
class SpeechSchedulerTest {
    private var nextId = 1L

    private fun u(
        kind: AlertKind, poi: Long = 1, queuedMs: Long = 0, distanceM: Double = 400.0, staleBelowM: Double? = null,
        text: String = "Speed camera ahead", priority: Int = when (kind) { AlertKind.PASSING -> 100; AlertKind.SECOND -> 50; else -> 10 },
    ) = Utterance(nextId++, poi, kind, text, priority, distanceM, staleBelowM, chime = false, queuedMs = queuedMs)

    private fun speak(s: SpeechScheduler, now: Long): Utterance {
        val d = s.next(now)
        assertTrue("expected Speak at $now, got $d", d is Decision.Speak)
        val utt = (d as Decision.Speak).utterance
        s.onStarted(utt, now)
        return utt
    }

    private fun outcomes(s: SpeechScheduler) = s.records().map { it.outcome }

    @Test
    fun limitChangeIsNotAStageAndTheNewestOneWins() {
        val s = SpeechScheduler()
        val first = Utterance(900, -1, AlertKind.LIMIT_CHANGE, "Speed limit changes to thirty ahead", 10, 0.0, null, false, 0)
        val camera = Utterance(901, 7, AlertKind.FIRST, "Fixed camera ahead", 10, 400.0, 200.0, false, 10)
        val second = Utterance(902, -1, AlertKind.LIMIT_CHANGE, "Speed limit changes to forty ahead", 10, 0.0, null, false, 20)
        s.offer(first, 0)
        s.offer(camera, 10)
        val d = s.offer(second, 20)
        // The waiting limit announcement is replaced by the newer one; the camera stage is untouched.
        assertTrue(d.any { it is Decision.Replaced && it.old === first })
        assertEquals(listOf(camera, second).map { it.id }.sorted(), s.waiting.map { it.id }.sorted())
        assertEquals(Outcome.REPLACED, s.records().last().outcome)
        assertTrue(!SpeechScheduler.isStage(AlertKind.LIMIT_CHANGE))
        // A camera stage never replaces, and is never replaced by, a limit announcement (different kinds, different POI).
        s.offer(Utterance(903, 7, AlertKind.SECOND, "Fixed camera, 200 yards", 50, 200.0, null, false, 30), 30)
        assertTrue(s.waiting.any { it.id == second.id })
    }

    @Test
    fun interruptedUtteranceDoesNotBlockTheQueue() {
        val s = SpeechScheduler()
        val a = u(AlertKind.FIRST, poi = 1)
        s.offer(a, 0)
        assertEquals(a, speak(s, 0))
        val b = u(AlertKind.FIRST, poi = 2, queuedMs = 500)
        s.offer(b, 500)
        assertTrue(s.next(600) is Decision.Wait)
        s.onInterrupted(a, 800) // the car's audio cut in
        assertTrue(s.next(900) is Decision.Wait) // the 400 ms gap
        assertEquals(b, speak(s, 1_200))
        assertEquals(listOf(Outcome.INTERRUPTED), outcomes(s))
    }

    @Test
    fun watchdogFreesAStuckEngine() {
        val s = SpeechScheduler()
        val a = u(AlertKind.FIRST, poi = 1, text = "x".repeat(20))
        s.offer(a, 0)
        speak(s, 0)
        val expected = 3_000 + 120 * 20L
        assertEquals(expected, s.deadlineMs())
        assertNull(s.onWatchdog(expected - 1))
        assertEquals(a, s.onWatchdog(expected))
        assertNull(s.speaking)
        val b = u(AlertKind.SECOND, poi = 2, queuedMs = 1_000)
        s.offer(b, 1_000)
        assertEquals(b, speak(s, expected + 400))
        assertEquals(listOf(Outcome.TIMED_OUT), outcomes(s))
        // Long texts cap at 15 s.
        assertEquals(15_000L, s.expectedMs(u(AlertKind.FIRST, text = "y".repeat(500))))
    }

    @Test
    fun laterStageReplacesWaitingEarlierOne() {
        val s = SpeechScheduler()
        val other = u(AlertKind.FIRST, poi = 9)
        s.offer(other, 0); speak(s, 0) // another camera is being spoken: the FIRST waits
        val first = u(AlertKind.FIRST, poi = 1)
        s.offer(first, 0)
        val second = u(AlertKind.SECOND, poi = 1, queuedMs = 300, distanceM = 200.0)
        val decisions = s.offer(second, 300)
        assertTrue(decisions.any { it is Decision.Replaced && it.old == first && it.by == second })
        assertEquals(listOf(second), s.waiting)
        s.onDone(other, 350)
        assertEquals(second, speak(s, 800))
        assertEquals(listOf(Outcome.REPLACED, Outcome.SPOKEN), outcomes(s))
        assertEquals(first, s.records().first().utterance)
        assertEquals(AlertKind.SECOND, s.records().first().byKind)
        // The other way round: an earlier stage never replaces a later one still waiting.
        val s2 = SpeechScheduler()
        val sec = u(AlertKind.SECOND, poi = 1)
        s2.offer(sec, 0)
        val late = s2.offer(u(AlertKind.FIRST, poi = 1, queuedMs = 10), 10)
        assertTrue(late.single() is Decision.Dropped)
        assertEquals(listOf(sec), s2.waiting)
    }

    @Test
    fun firstIsStaleInsideSecondTriggerButSecondStillSpeaks() {
        val s = SpeechScheduler()
        val other = u(AlertKind.FIRST, poi = 9)
        s.offer(other, 0)
        speak(s, 0)
        val first = u(AlertKind.FIRST, poi = 1, queuedMs = 100, distanceM = 400.0, staleBelowM = 235.0)
        s.offer(first, 100)
        s.onDistance(1, 200.0) // already inside the 2nd trigger by the time the channel frees
        s.onDone(other, 2_000)
        assertEquals(Decision.Idle, s.next(2_000)) // the FIRST is stale: nothing left to say
        assertEquals(listOf(Outcome.SPOKEN, Outcome.STALE), outcomes(s))
        assertTrue(s.waiting.isEmpty())
        val second = u(AlertKind.SECOND, poi = 1, queuedMs = 2_500, distanceM = 200.0, staleBelowM = 142.0)
        s.offer(second, 2_500)
        assertEquals(second, speak(s, 2_500))
    }

    @Test
    fun passingNeverDroppedAndSpokenFirst() {
        val s = SpeechScheduler()
        val p = u(AlertKind.PASSING, poi = 1, queuedMs = 1_000, distanceM = 90.0, staleBelowM = 50.0)
        s.offer(p, 1_000)
        s.onDistance(1, 10.0) // staleness never applies to PASSING
        assertEquals(p, speak(s, 1_000))
        // Spoken at once, ahead of a FIRST queued earlier.
        val s2 = SpeechScheduler()
        s2.offer(u(AlertKind.FIRST, poi = 2, queuedMs = 0, distanceM = 400.0), 0)
        val p2 = u(AlertKind.PASSING, poi = 3, queuedMs = 50, distanceM = 90.0)
        s2.offer(p2, 50)
        assertEquals(p2, speak(s2, 50))
    }

    @Test
    fun engineErrorRetriesOnceThenFallsBack() {
        val s = SpeechScheduler()
        val a = u(AlertKind.SECOND, poi = 1)
        s.offer(a, 0)
        speak(s, 0)
        val retry = s.onEngineError(a, -1, 10)
        assertEquals(Decision.Retry(a), retry)
        assertNull(s.speaking)
        assertEquals(listOf(a), s.waiting)
        assertEquals(a, speak(s, 20)) // straight away, no gap after an error
        val fallback = s.onEngineError(a, -1, 30)
        assertEquals(Decision.Fallback(a, -1), fallback)
        assertNull(s.speaking)
        assertTrue(s.waiting.isEmpty())
        assertEquals(listOf(Outcome.TTS_ERROR), outcomes(s))
        assertEquals("status -1", s.records().single().detail)
    }

    @Test
    fun twoCamerasAtOneJunctionBothPassingsSpoken() {
        val s = SpeechScheduler()
        val p1 = u(AlertKind.PASSING, poi = 1, queuedMs = 0, distanceM = 90.0)
        val p2 = u(AlertKind.PASSING, poi = 2, queuedMs = 100, distanceM = 85.0)
        s.offer(p1, 0)
        assertEquals(p1, speak(s, 0))
        assertTrue(s.offer(p2, 100).single() is Decision.Queued)
        s.onDone(p1, 1_500)
        assertEquals(p2, speak(s, 1_900))
        s.onDone(p2, 3_000)
        assertEquals(listOf(Outcome.SPOKEN, Outcome.SPOKEN), outcomes(s))
        assertEquals(0L, s.records()[0].waitedMs)
        assertEquals(1_800L, s.records()[1].waitedMs)
        // Different POIs never replace each other, whatever the stage.
        val s2 = SpeechScheduler()
        s2.offer(u(AlertKind.FIRST, poi = 1), 0)
        s2.offer(u(AlertKind.SECOND, poi = 2, queuedMs = 10), 10)
        assertEquals(2, s2.waiting.size)
    }

    @Test
    fun mutedRecorded() {
        val s = SpeechScheduler()
        val a = u(AlertKind.PASSING, poi = 1)
        val d = s.offer(a, 0, muted = true)
        assertEquals(listOf(Decision.Dropped(a, Outcome.MUTED)), d)
        assertTrue(s.waiting.isEmpty())
        assertEquals(Decision.Idle, s.next(0))
        assertEquals(listOf(Outcome.MUTED), outcomes(s))
        s.onFocus(a, granted = false, nowMs = 5)
        assertEquals(listOf(Outcome.MUTED, Outcome.FOCUS_DENIED), outcomes(s))
    }

    @Test
    fun muteStopsWhatIsSpeakingAndWhatWaits() {
        val s = SpeechScheduler()
        s.offer(u(AlertKind.SECOND, poi = 1), 0)
        val speaking = speak(s, 0)
        s.offer(u(AlertKind.PASSING, poi = 1, queuedMs = 100), 100)
        s.offer(u(AlertKind.FIRST, poi = 2, queuedMs = 100), 100)
        assertTrue(s.muteAll(200))
        assertTrue(s.waiting.isEmpty())
        assertNull(s.speaking)
        assertEquals(listOf(Outcome.MUTED, Outcome.MUTED, Outcome.MUTED), outcomes(s))
        // A late callback for the muted utterance changes nothing.
        s.onDone(speaking, 300)
        assertEquals(3, s.records().size)
        assertFalse(s.muteAll(400))
    }

    @Test
    fun priorityThenDistanceAcrossPois() {
        val s = SpeechScheduler()
        val farSecond = u(AlertKind.SECOND, poi = 1, queuedMs = 0, distanceM = 230.0)
        val nearFirst = u(AlertKind.FIRST, poi = 2, queuedMs = 0, distanceM = 150.0)
        val nearerSecond = u(AlertKind.SECOND, poi = 3, queuedMs = 5, distanceM = 180.0)
        s.offer(nearFirst, 0); s.offer(farSecond, 0); s.offer(nearerSecond, 5)
        assertEquals(nearerSecond, speak(s, 10))
        s.onDone(nearerSecond, 1_000)
        assertEquals(farSecond, speak(s, 1_400))
        s.onDone(farSecond, 2_000)
        assertEquals(nearFirst, speak(s, 2_400))
    }

    @Test
    fun recordRingKeepsFifty() {
        val s = SpeechScheduler()
        repeat(60) { s.offer(u(AlertKind.FIRST, poi = it.toLong()), it.toLong(), muted = true) }
        assertEquals(50, s.records().size)
        assertEquals(10L, s.records().first().utterance.poiId)
    }

    @Test
    fun lateCallbacksForAnOldUtteranceAreIgnored() {
        val s = SpeechScheduler()
        val a = u(AlertKind.FIRST, poi = 1)
        s.offer(a, 0)
        speak(s, 0)
        assertNotNull(s.onWatchdog(20_000))
        val b = u(AlertKind.FIRST, poi = 2, queuedMs = 20_000)
        s.offer(b, 20_000)
        speak(s, 20_400)
        s.onDone(a, 20_500) // the stuck engine finally reports: must not free b
        assertEquals(b, s.speaking)
    }

    @Test
    fun watchdogExtendsWhileTheEngineStillSpeaksUpToTheCap() {
        val s = SpeechScheduler()
        val a = u(AlertKind.SECOND, poi = 1, text = "x".repeat(20))
        s.offer(a, 0)
        assertNull("nothing current", s.extendWhileSpeaking(100))
        val d = s.next(0) as Decision.Speak
        assertNull("handed to the engine, onStart not yet reported", s.extendWhileSpeaking(100))
        s.onStarted(d.utterance, 200)
        // Past the 3000 + 120 × 20 ms deadline but still audibly speaking: it goes on, with the time since onStart.
        assertEquals(5_400L, s.extendWhileSpeaking(5_600))
        assertEquals(14_999L, s.extendWhileSpeaking(15_199))
        // 15 s from onStart is the cap: the watchdog frees the engine.
        assertNull(s.extendWhileSpeaking(15_200))
        assertEquals(a, s.onWatchdog(15_200))
        assertNull(s.extendWhileSpeaking(15_300))
        assertEquals(listOf(Outcome.TIMED_OUT), outcomes(s))
    }

    @Test
    fun recordTextForTheSnapshotAndTheLog() {
        val utt = Utterance(7, 4, AlertKind.SECOND, "Fixed camera, 200 yards", 50, 200.0, null, false, 1_000)
        val spoken = SpeechScheduler.Record(utt, Outcome.SPOKEN, atMs = 4_000, startMs = 1_800, waitedMs = 800)
        assertEquals("SECOND poi=4 SPOKEN waited=800ms", spoken.describe())
        assertEquals("done SECOND poi=4 dur=2200 waited=800", spoken.logLine())
        val replaced = SpeechScheduler.Record(utt, Outcome.REPLACED, atMs = 1_200, byKind = AlertKind.PASSING)
        assertEquals("SECOND poi=4 REPLACED by=PASSING", replaced.describe())
        assertEquals("replaced SECOND poi=4 by=PASSING", replaced.logLine())
        val stale = SpeechScheduler.Record(utt, Outcome.STALE, atMs = 1_200, detail = "d=120 < 142")
        assertEquals("SECOND poi=4 STALE d=120 < 142", stale.describe())
        assertEquals("dropped SECOND poi=4 reason=stale d=120 < 142", stale.logLine())
        assertEquals("muted SECOND poi=4", SpeechScheduler.Record(utt, Outcome.MUTED, 1_200).logLine())
        // Interrupted, timed out and an engine error are logged where they happen; a denied focus is the one
        // outcome the utterance speaks through.
        for (o in listOf(Outcome.INTERRUPTED, Outcome.TIMED_OUT, Outcome.TTS_ERROR, Outcome.FOCUS_DENIED)) {
            assertNull(o.name, SpeechScheduler.Record(utt, o, 1_200).logLine())
        }
        assertFalse(SpeechScheduler.Record(utt, Outcome.FOCUS_DENIED, 1_200).terminal)
        assertTrue(spoken.terminal)
    }

    // --- Directions (GUIDANCE, DESIGN.md §12.3) ---------------------------------------------------

    private fun dir(text: String, queuedMs: Long, distanceM: Double = 300.0, staleBelowM: Double? = null) =
        Utterance(nextId++, -2, AlertKind.GUIDANCE, text, 80, distanceM, staleBelowM, chime = false, queuedMs = queuedMs)

    @Test
    fun newerDirectionReplacesOneWaiting() {
        val s = SpeechScheduler()
        val busy = u(AlertKind.FIRST, poi = 7)
        s.offer(busy, 0); speak(s, 0)
        s.offer(dir("In 300 yards, turn right", 10), 10)
        val now = dir("Turn right", 20, distanceM = 60.0)
        val d = s.offer(now, 20)
        assertTrue(d.any { it is Decision.Replaced })
        assertEquals(listOf(now), s.waiting)
    }

    @Test
    fun directionGoesBeforeCameraStagesButAfterPassing() {
        val s = SpeechScheduler()
        val first = u(AlertKind.FIRST, poi = 7, distanceM = 100.0)
        val second = u(AlertKind.SECOND, poi = 8, distanceM = 100.0)
        val turn = dir("Turn left", 0, distanceM = 50.0)
        val passing = u(AlertKind.PASSING, poi = 9)
        for (x in listOf(first, second, turn, passing)) s.offer(x, 0)
        val order = mutableListOf<AlertKind>()
        var t = 0L
        repeat(4) {
            val a = speak(s, t); order += a.kind; s.onDone(a, t + 100); t += 1_000
        }
        assertEquals(listOf(AlertKind.PASSING, AlertKind.GUIDANCE, AlertKind.SECOND, AlertKind.FIRST), order)
    }

    @Test
    fun muteKeepsDirections() {
        val s = SpeechScheduler()
        val turn = dir("Turn left", 0)
        s.offer(turn, 0); speak(s, 0)
        val cam = u(AlertKind.FIRST, poi = 7, queuedMs = 10)
        val next = dir("Then keep right", 20)
        s.offer(cam, 10); s.offer(next, 20)
        assertFalse("a direction speaking isn't stopped", s.muteAll(30))
        assertEquals(turn, s.speaking)
        assertEquals(listOf(next), s.waiting)
        assertTrue(s.records().any { it.utterance == cam && it.outcome == Outcome.MUTED })
    }

    @Test
    fun stalePrepareDirectionIsDropped() {
        val s = SpeechScheduler()
        val busy = u(AlertKind.PASSING, poi = 9)
        s.offer(busy, 0); speak(s, 0)
        val prepare = dir("In 300 yards, turn right", 10, distanceM = 280.0, staleBelowM = 67.0)
        s.offer(prepare, 10)
        s.onDistance(-2, 50.0)
        s.onDone(busy, 2_000)
        assertEquals(Decision.Idle, s.next(3_000))
        assertTrue(s.records().any { it.utterance == prepare && it.outcome == Outcome.STALE })
    }
}

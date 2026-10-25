// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.alerts.SpeechScheduler.Decision
import io.autonav.alerts.core.alerts.SpeechScheduler.Outcome
import io.autonav.alerts.core.alerts.SpeechScheduler.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speech queue's edges (DESIGN.md §12.3): the watchdog deadline from `onStart`, the configured
 * cap, gap and ring size, the non-terminal FOCUS_DENIED, the distance bookkeeping, which kinds
 * compete with which, repeated stages, the pick order's tie-break, and late or foreign callbacks.
 * SpeechSchedulerTest covers the main policy; nothing here repeats it.
 */
class SpeechSchedulerPolicyTest {
    private var nextId = 1L

    private fun u(
        kind: AlertKind, poi: Long = 1, queuedMs: Long = 0, distanceM: Double = 400.0, staleBelowM: Double? = null,
        text: String = "Speed camera ahead", priority: Int = when (kind) { AlertKind.PASSING -> 100; AlertKind.SECOND, AlertKind.OVERSPEED -> 50; AlertKind.GUIDANCE -> 80; AlertKind.ZONE_ENTER, AlertKind.ZONE_EXIT -> 60; else -> 10 },
    ) = Utterance(nextId++, poi, kind, text, priority, distanceM, staleBelowM, chime = false, queuedMs = queuedMs)

    private fun speak(s: SpeechScheduler, now: Long): Utterance = (s.next(now) as Decision.Speak).utterance

    @Test
    fun deadlineRunsFromOnStartOnceReportedElseFromTheHandover() {
        val s = SpeechScheduler()
        val a = u(AlertKind.FIRST, text = "x".repeat(10)) // 3000 + 1200 ms
        s.offer(a, 0)
        speak(s, 100)
        assertEquals(4_300L, s.deadlineMs())
        assertEquals(Decision.Wait(4_300L, "speaking"), s.next(1_000))
        assertEquals(400L, s.onStarted(a, 400))
        assertEquals(4_600L, s.deadlineMs())
        assertEquals(Decision.Wait(4_600L, "speaking"), s.next(1_000))
        // onStarted for an utterance that isn't current is ignored.
        assertNull(s.onStarted(u(AlertKind.FIRST, poi = 2), 500))
        assertEquals(4_600L, s.deadlineMs())
        assertNull(s.onWatchdog(4_599))
        assertEquals(a, s.onWatchdog(4_600))
        assertNull(s.deadlineMs())
        assertNull(s.onWatchdog(5_000))
    }

    @Test
    fun watchdogNumbersComeFromTheConfig() {
        val s = SpeechScheduler(SpeechScheduler.Config(watchdogBaseMs = 1_000, watchdogPerCharMs = 10, watchdogCapMs = 1_500, watchdogExtendMs = 100))
        assertEquals(1_000L, s.expectedMs(u(AlertKind.FIRST, text = "")))
        assertEquals(1_200L, s.expectedMs(u(AlertKind.FIRST, text = "x".repeat(20))))
        assertEquals(1_500L, s.expectedMs(u(AlertKind.FIRST, text = "x".repeat(200))))
        val a = u(AlertKind.SECOND)
        s.offer(a, 0)
        s.onStarted(speak(s, 0), 0)
        assertEquals(1_499L, s.extendWhileSpeaking(1_499))
        assertNull(s.extendWhileSpeaking(1_500))
        assertEquals(100L, s.config.watchdogExtendMs)
    }

    @Test
    fun gapAndRingSizeComeFromTheConfig() {
        val s = SpeechScheduler(SpeechScheduler.Config(minGapMs = 1_000, ringSize = 3))
        val a = u(AlertKind.FIRST, poi = 1)
        s.offer(a, 0); speak(s, 0); s.onDone(a, 1_000)
        s.offer(u(AlertKind.FIRST, poi = 2, queuedMs = 1_100), 1_100)
        assertEquals(Decision.Wait(2_000L, "gap"), s.next(1_500))
        assertTrue(s.next(2_000) is Decision.Speak)
        repeat(5) { s.offer(u(AlertKind.FIRST, poi = 10L + it), 3_000, muted = true) }
        assertEquals(3, s.records().size)
        assertEquals(listOf(12L, 13L, 14L), s.records().map { it.utterance.poiId })
    }

    @Test
    fun focusDeniedIsNotTheEndOfAnUtterance() {
        // A denied focus is recorded but the utterance goes on: its distance stays for the staleness rule.
        val s = SpeechScheduler()
        val busy = u(AlertKind.PASSING, poi = 9)
        s.offer(busy, 0); speak(s, 0)
        val first = u(AlertKind.FIRST, poi = 1, queuedMs = 10, distanceM = 400.0, staleBelowM = 200.0)
        s.offer(first, 10)
        s.onDistance(1, 150.0)
        s.onFocus(first, granted = false, nowMs = 20)
        assertEquals(listOf(Outcome.FOCUS_DENIED), s.records().map { it.outcome })
        s.onDone(busy, 1_000)
        assertEquals(Decision.Idle, s.next(1_500))
        assertEquals(listOf(Outcome.FOCUS_DENIED, Outcome.SPOKEN, Outcome.STALE), s.records().map { it.outcome })
        assertEquals("d=150 < 200", s.records().last().detail)
        // A granted focus records nothing.
        s.onFocus(first, granted = true, nowMs = 30)
        assertEquals(3, s.records().size)
    }

    @Test
    fun distancesAreKeptOnlyWhileThePoiHasSpeechQueued() {
        // A distance reported before anything is queued for that POI is not remembered.
        val s = SpeechScheduler()
        s.onDistance(7, 10.0)
        val a = u(AlertKind.FIRST, poi = 7, distanceM = 400.0, staleBelowM = 200.0)
        s.offer(a, 0)
        assertEquals(a, speak(s, 0))
        // Once its utterance is finished the distance goes, so a later stage is judged on its own distance.
        s.onDistance(7, 10.0) // kept: 7 is speaking
        s.onDone(a, 1_000)
        val b = u(AlertKind.SECOND, poi = 7, queuedMs = 1_500, distanceM = 180.0, staleBelowM = 142.0)
        s.offer(b, 1_500)
        assertEquals(b, speak(s, 1_500))
        // Mute drops the distances of what it silenced.
        val m = SpeechScheduler()
        m.offer(u(AlertKind.SECOND, poi = 3, staleBelowM = 142.0), 0)
        m.onDistance(3, 50.0)
        m.muteAll(10)
        val again = u(AlertKind.SECOND, poi = 3, queuedMs = 20, distanceM = 180.0, staleBelowM = 142.0)
        m.offer(again, 20)
        assertEquals(again, speak(m, 20))
    }

    @Test
    fun staleIsJudgedOnTheQueuedDistanceWithoutAnUpdate() {
        val s = SpeechScheduler()
        val busy = u(AlertKind.PASSING, poi = 9)
        s.offer(busy, 0); speak(s, 0)
        // Queued already inside its stale distance, never updated: dropped when its turn comes.
        s.offer(u(AlertKind.SECOND, poi = 1, queuedMs = 10, distanceM = 100.0, staleBelowM = 142.0), 10)
        s.onDone(busy, 1_000)
        assertEquals(Decision.Idle, s.next(1_500))
        assertEquals(Outcome.STALE, s.records().last().outcome)
        // Exactly the stale distance is not stale (strictly closer).
        val t = SpeechScheduler()
        val edge = u(AlertKind.SECOND, poi = 1, distanceM = 142.0, staleBelowM = 142.0)
        t.offer(edge, 0)
        assertEquals(edge, speak(t, 0))
        // No stale distance given: never dropped.
        val n = SpeechScheduler()
        val open = u(AlertKind.FIRST, poi = 1, distanceM = 400.0, staleBelowM = null)
        n.offer(open, 0)
        n.onDistance(1, 1.0)
        assertEquals(open, speak(n, 0))
    }

    @Test
    fun onlyStagesLimitChangesAndDirectionsCompete() {
        val s = SpeechScheduler()
        val busy = u(AlertKind.PASSING, poi = 9)
        s.offer(busy, 0); speak(s, 0)
        // Two zone events of one POI both wait; an OVERSPEED never displaces a stage of the same POI, nor the reverse.
        s.offer(u(AlertKind.ZONE_ENTER, poi = 1, queuedMs = 10), 10)
        s.offer(u(AlertKind.ZONE_EXIT, poi = 1, queuedMs = 20), 20)
        s.offer(u(AlertKind.OVERSPEED, poi = 1, queuedMs = 30), 30)
        s.offer(u(AlertKind.FIRST, poi = 1, queuedMs = 40), 40)
        s.offer(u(AlertKind.OVERSPEED, poi = 1, queuedMs = 50), 50)
        assertEquals(5, s.waiting.size)
        assertTrue(s.records().none { it.outcome == Outcome.REPLACED })
        // A limit change and a direction are different kinds: neither replaces the other.
        val lim = Utterance(900, -1, AlertKind.LIMIT_CHANGE, "Speed limit changes to thirty ahead", 10, 0.0, null, false, 60)
        val dir = Utterance(901, -2, AlertKind.GUIDANCE, "Turn left", 80, 100.0, null, false, 70)
        s.offer(lim, 60); s.offer(dir, 70)
        assertEquals(7, s.waiting.size)
    }

    @Test
    fun aRepeatedStageIsDroppedAsStale() {
        val s = SpeechScheduler()
        val busy = u(AlertKind.PASSING, poi = 9)
        s.offer(busy, 0); speak(s, 0)
        val second = u(AlertKind.SECOND, poi = 1, queuedMs = 10)
        s.offer(second, 10)
        val again = u(AlertKind.SECOND, poi = 1, queuedMs = 20)
        val d = s.offer(again, 20)
        assertEquals(listOf(Decision.Dropped(again, Outcome.STALE)), d)
        assertEquals(listOf(second), s.waiting)
        val r = s.records().last()
        assertEquals(again, r.utterance)
        assertEquals("later stage waiting", r.detail)
        assertEquals("SECOND poi=1 STALE later stage waiting", r.describe())
        // A PASSING replaces a waiting SECOND, and a second PASSING is dropped the same way.
        val passing = u(AlertKind.PASSING, poi = 1, queuedMs = 30)
        assertTrue(s.offer(passing, 30).any { it is Decision.Replaced && it.old == second })
        assertTrue(s.offer(u(AlertKind.PASSING, poi = 1, queuedMs = 40), 40).single() is Decision.Dropped)
        assertEquals(listOf(passing), s.waiting)
    }

    @Test
    fun equalPriorityAndDistanceGoFirstComeFirstServed() {
        val s = SpeechScheduler()
        val a = u(AlertKind.FIRST, poi = 1, queuedMs = 5, distanceM = 300.0)
        val b = u(AlertKind.FIRST, poi = 2, queuedMs = 0, distanceM = 300.0)
        s.offer(a, 5); s.offer(b, 5)
        assertEquals(b, speak(s, 10))
        assertTrue("nothing is recorded until an utterance ends", s.records().isEmpty())
        s.onDone(b, 1_000)
        assertEquals(a, speak(s, 1_400))
        // The latest distance, not the queued one, orders two waiting stages of equal priority.
        val t = SpeechScheduler()
        val busy = u(AlertKind.PASSING, poi = 9)
        t.offer(busy, 0); speak(t, 0)
        val far = u(AlertKind.FIRST, poi = 1, queuedMs = 10, distanceM = 500.0)
        val near = u(AlertKind.FIRST, poi = 2, queuedMs = 20, distanceM = 300.0)
        t.offer(far, 10); t.offer(near, 20)
        t.onDistance(1, 100.0)
        t.onDone(busy, 1_000)
        assertEquals(far, speak(t, 1_400))
    }

    @Test
    fun retryBookkeepingClearsOnceTheUtteranceEnds() {
        val s = SpeechScheduler()
        val a = u(AlertKind.SECOND, poi = 1)
        s.offer(a, 0); speak(s, 0)
        assertEquals(Decision.Retry(a), s.onEngineError(a, -1, 10))
        assertEquals(a, speak(s, 20))
        s.onDone(a, 1_000)
        assertEquals(listOf(Outcome.SPOKEN), s.records().map { it.outcome })
        // Spoken in the end: the retry allowance is back, so a later error on the same id retries again.
        s.offer(a, 2_000); speak(s, 2_000)
        assertEquals(Decision.Retry(a), s.onEngineError(a, -2, 2_010))
        // An error for an utterance that isn't current still counts as its retry.
        val t = SpeechScheduler()
        val other = u(AlertKind.FIRST, poi = 2)
        t.offer(other, 0); speak(t, 0)
        val ghost = u(AlertKind.FIRST, poi = 3)
        assertEquals(Decision.Retry(ghost), t.onEngineError(ghost, -1, 10))
        assertEquals(other, t.speaking)
        assertEquals(listOf(ghost), t.waiting)
    }

    @Test
    fun interruptedAndDoneAreIgnoredForAnUtteranceThatIsNotCurrent() {
        val s = SpeechScheduler()
        val a = u(AlertKind.FIRST, poi = 1)
        s.offer(a, 0); speak(s, 0)
        val stray = u(AlertKind.FIRST, poi = 2)
        s.onInterrupted(stray, 100)
        s.onDone(stray, 200)
        assertEquals(a, s.speaking)
        assertTrue(s.records().isEmpty())
        // After an interruption the gap is timed from the interruption, and the record carries the start.
        s.onStarted(a, 50)
        s.onInterrupted(a, 300)
        val r = s.records().single()
        assertEquals(Outcome.INTERRUPTED, r.outcome)
        assertEquals(50L, r.startMs)
        assertNull(r.waitedMs)
        s.offer(u(AlertKind.FIRST, poi = 3, queuedMs = 310), 310)
        assertEquals(Decision.Wait(700L, "gap"), s.next(400))
    }

    @Test
    fun describeAndLogLineForTheTimedOutAndErroredRecords() {
        val utt = u(AlertKind.PASSING, poi = 4, queuedMs = 100)
        val timedOut = SpeechScheduler.Record(utt, Outcome.TIMED_OUT, atMs = 20_000, startMs = 1_000)
        assertEquals("PASSING poi=4 TIMED_OUT", timedOut.describe())
        assertNull(timedOut.logLine())
        val err = SpeechScheduler.Record(utt, Outcome.TTS_ERROR, atMs = 500, detail = "status -1")
        assertEquals("PASSING poi=4 TTS_ERROR status -1", err.describe())
        assertTrue(err.terminal)
        // A SPOKEN record without a start uses its own time as the start: zero duration.
        val spoken = SpeechScheduler.Record(utt, Outcome.SPOKEN, atMs = 2_000)
        assertEquals("done PASSING poi=4 dur=0 waited=-", spoken.logLine())
        assertFalse(SpeechScheduler.isStage(AlertKind.GUIDANCE))
        assertFalse(SpeechScheduler.isStage(AlertKind.OVERSPEED))
        assertTrue(SpeechScheduler.isStage(AlertKind.PASSING))
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.alerts.SpeechScheduler.Decision
import io.autonav.alerts.core.alerts.SpeechScheduler.Outcome
import io.autonav.alerts.core.alerts.SpeechScheduler.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speech queue policy (DESIGN.md §12.3). */
class SpeechSchedulerTest {
    private var nextId = 1L

    private fun u(
        kind: AlertKind, poi: Long = 1, queuedMs: Long = 0, distanceM: Double = 400.0, staleBelowM: Double? = null,
        text: String = "Speed camera ahead", priority: Int = when (kind) { AlertKind.PASSING -> 100; AlertKind.SECOND -> 50; else -> 10 },
    ) = Utterance(nextId++, poi, kind, text, priority, distanceM, staleBelowM, chime = false, queuedMs = queuedMs)

    private fun speak(s: SpeechScheduler, now: Long, busy: Boolean = false): Utterance {
        val d = s.next(now, busy)
        assertTrue("expected Speak at $now, got $d", d is Decision.Speak)
        val utt = (d as Decision.Speak).utterance
        s.onStarted(utt, now)
        return utt
    }

    private fun outcomes(s: SpeechScheduler) = s.records().map { it.outcome }

    @Test
    fun interruptedUtteranceDoesNotBlockTheQueue() {
        val s = SpeechScheduler()
        val a = u(AlertKind.FIRST, poi = 1)
        s.offer(a, 0)
        assertEquals(a, speak(s, 0))
        val b = u(AlertKind.FIRST, poi = 2, queuedMs = 500)
        s.offer(b, 500)
        assertTrue(s.next(600, false) is Decision.Wait)
        s.onInterrupted(a, 800) // the SDK voice cut in
        assertTrue(s.next(900, false) is Decision.Wait) // the 400 ms gap
        assertEquals(b, speak(s, 1_200))
        assertEquals(listOf(Outcome.INTERRUPTED), outcomes(s))
    }

    @Test
    fun watchdogFreesAStuckEngine() {
        val s = SpeechScheduler()
        val a = u(AlertKind.FIRST, poi = 1, text = "x".repeat(20))
        s.offer(a, 0)
        speak(s, 0)
        val expected = 1_500 + 70 * 20L
        assertEquals(expected, s.deadlineMs())
        assertNull(s.onWatchdog(expected - 1))
        assertEquals(a, s.onWatchdog(expected))
        assertNull(s.speaking)
        val b = u(AlertKind.SECOND, poi = 2, queuedMs = 1_000)
        s.offer(b, 1_000)
        assertEquals(b, speak(s, expected + 400))
        assertEquals(listOf(Outcome.TIMED_OUT), outcomes(s))
        // Long texts cap at 12 s.
        assertEquals(12_000L, s.expectedMs(u(AlertKind.FIRST, text = "y".repeat(500))))
    }

    @Test
    fun laterStageReplacesWaitingEarlierOne() {
        val s = SpeechScheduler()
        val first = u(AlertKind.FIRST, poi = 1)
        s.offer(first, 0)
        assertTrue(s.next(0, true) is Decision.Wait) // the channel is busy: it waits
        val second = u(AlertKind.SECOND, poi = 1, queuedMs = 300, distanceM = 200.0)
        val decisions = s.offer(second, 300)
        assertTrue(decisions.any { it is Decision.Replaced && it.old == first && it.by == second })
        assertEquals(listOf(second), s.waiting)
        assertEquals(second, speak(s, 400))
        assertEquals(listOf(Outcome.DEFERRED, Outcome.REPLACED), outcomes(s))
        assertEquals(first, s.records().last().utterance)
        assertEquals(AlertKind.SECOND, s.records().last().byKind)
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
        assertEquals(Decision.Idle, s.next(2_000, false)) // the FIRST is stale: nothing left to say
        assertEquals(listOf(Outcome.SPOKEN, Outcome.STALE), outcomes(s))
        assertTrue(s.waiting.isEmpty())
        val second = u(AlertKind.SECOND, poi = 1, queuedMs = 2_500, distanceM = 200.0, staleBelowM = 142.0)
        s.offer(second, 2_500)
        assertEquals(second, speak(s, 2_500))
    }

    @Test
    fun passingNeverDroppedOrDeferredBeyondHalfASecond() {
        val s = SpeechScheduler()
        val p = u(AlertKind.PASSING, poi = 1, queuedMs = 1_000, distanceM = 90.0, staleBelowM = 50.0)
        s.offer(p, 1_000)
        s.onDistance(1, 10.0) // staleness never applies to PASSING
        val w = s.next(1_000, true)
        assertEquals(Decision.Wait(1_500, "channel busy"), w)
        assertTrue(s.next(1_499, true) is Decision.Wait)
        assertEquals(p, speak(s, 1_500, busy = true))
        assertEquals(listOf(Outcome.DEFERRED), outcomes(s))
        // Not busy: spoken at once, ahead of a higher-queued FIRST.
        val s2 = SpeechScheduler()
        s2.offer(u(AlertKind.FIRST, poi = 2, queuedMs = 0, distanceM = 400.0), 0)
        val p2 = u(AlertKind.PASSING, poi = 3, queuedMs = 50, distanceM = 90.0)
        s2.offer(p2, 50)
        assertEquals(p2, speak(s2, 50))
    }

    @Test
    fun channelBusyCapsAtTwoSecondsForStages() {
        val s = SpeechScheduler()
        val f = u(AlertKind.FIRST, poi = 1, queuedMs = 1_000)
        s.offer(f, 1_000)
        assertEquals(Decision.Wait(3_000, "channel busy"), s.next(1_000, true))
        assertEquals(Decision.Wait(3_000, "channel busy"), s.next(2_999, true))
        assertEquals(f, speak(s, 3_000, busy = true))
        // When the channel frees early, it speaks then.
        val s2 = SpeechScheduler()
        val g = u(AlertKind.SECOND, poi = 1, queuedMs = 0)
        s2.offer(g, 0)
        assertTrue(s2.next(0, true) is Decision.Wait)
        assertEquals(g, speak(s2, 700, busy = false))
    }

    @Test
    fun channelBusyForFiveSecondsIsIgnored() {
        val s = SpeechScheduler()
        // Android Auto keeps the guidance usage busy from t=0.
        for (t in 0L..5_000L step 500) assertEquals(Decision.Idle, s.next(t, true))
        val f = u(AlertKind.FIRST, poi = 1, queuedMs = 5_100)
        s.offer(f, 5_100)
        assertEquals(f, speak(s, 5_100, busy = true)) // busy ignored: spoken at once
        s.onDone(f, 7_000)
        // Still ignored within the 60 s window...
        val g = u(AlertKind.FIRST, poi = 2, queuedMs = 30_000)
        s.offer(g, 30_000)
        assertEquals(g, speak(s, 30_000, busy = true))
        s.onDone(g, 31_000)
        // ...and respected again once the channel has been quiet and goes busy afresh.
        assertEquals(Decision.Idle, s.next(70_000, false))
        val h = u(AlertKind.FIRST, poi = 3, queuedMs = 70_100)
        s.offer(h, 70_100)
        assertEquals(Decision.Wait(72_100, "channel busy"), s.next(70_100, true))
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
        assertEquals(Decision.Idle, s.next(0, false))
        assertEquals(listOf(Outcome.MUTED), outcomes(s))
        s.onFocus(a, granted = false, nowMs = 5)
        assertEquals(listOf(Outcome.MUTED, Outcome.FOCUS_DENIED), outcomes(s))
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
}

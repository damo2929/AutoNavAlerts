// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import kotlin.math.min

/**
 * The speech queue policy (DESIGN.md §12.3), pure with the clock passed in. `NavTts` drives it:
 * [offer] each alert's utterance, [next] whenever something changes (a new utterance, the channel
 * going quiet, an utterance ending, a timer), then tell it what the engine did.
 *
 * Policy: only a later stage replaces an earlier one still waiting for the same POI; a waiting
 * FIRST or SECOND is stale once the car is closer than [Utterance.staleBelowM]; PASSING goes first,
 * then priority, then distance; at least 400 ms between utterances; behind a busy channel a stage
 * waits at most 2 s and a PASSING 0.5 s; a channel busy for more than 5 s on end is ignored for
 * 60 s; a stuck engine is freed by [onWatchdog] after 1500 + 70·chars ms (at most 12 s); an engine
 * error is retried once, then the caller falls back to the chime and banner. Every decision goes
 * into a 50-entry [records] ring.
 */
class SpeechScheduler(private val config: Config = Config()) {
    data class Config(
        val minGapMs: Long = 400,
        val stageBusyWaitMs: Long = 2_000,
        val passingBusyWaitMs: Long = 500,
        val busyIgnoreAfterMs: Long = 5_000,
        val busyIgnoreForMs: Long = 60_000,
        val watchdogBaseMs: Long = 1_500,
        val watchdogPerCharMs: Long = 70,
        val watchdogCapMs: Long = 12_000,
        val ringSize: Int = 50,
    )

    data class Utterance(
        val id: Long,
        val poiId: Long,
        val kind: AlertKind,
        val text: String,
        val priority: Int,
        val distanceM: Double,
        /** FIRST/SECOND only: not worth saying once the car is closer than this. */
        val staleBelowM: Double?,
        val chime: Boolean,
        val queuedMs: Long,
    )

    enum class Outcome { SPOKEN, REPLACED, STALE, INTERRUPTED, TIMED_OUT, TTS_ERROR, MUTED, FOCUS_DENIED, DEFERRED }

    data class Record(
        val utterance: Utterance,
        val outcome: Outcome,
        val atMs: Long,
        val startMs: Long? = null,
        val waitedMs: Long? = null,
        val byKind: AlertKind? = null,
        val detail: String? = null,
    )

    sealed interface Decision {
        /** Start this one now; [waitedMs] is how long it queued. */
        data class Speak(val utterance: Utterance, val waitedMs: Long) : Decision
        /** Nothing to do before [untilMs] (call [next] again then, or sooner if something changes). */
        data class Wait(val untilMs: Long, val reason: String) : Decision
        data object Idle : Decision
        data class Queued(val utterance: Utterance) : Decision
        data class Replaced(val old: Utterance, val by: Utterance) : Decision
        data class Dropped(val utterance: Utterance, val outcome: Outcome) : Decision
        /** Speak it again (it is back at the head of the queue). */
        data class Retry(val utterance: Utterance) : Decision
        /** Give up on speech for it: chime, banner and a toast. */
        data class Fallback(val utterance: Utterance, val code: Int) : Decision
    }

    private val queue = ArrayList<Utterance>()
    private var current: Utterance? = null
    private var currentSpeakMs = 0L
    private var currentStartMs: Long? = null
    private var lastDoneMs = Long.MIN_VALUE / 2
    private var busySinceMs: Long? = null
    private var ignoreBusyUntilMs = Long.MIN_VALUE
    private val distances = HashMap<Long, Double>()
    private val retried = HashSet<Long>()
    private val deferred = HashSet<Long>()
    private val ring = ArrayDeque<Record>()

    val speaking: Utterance? get() = current
    val waiting: List<Utterance> get() = queue.toList()

    /** The decisions so far, oldest first, at most [Config.ringSize]. */
    fun records(): List<Record> = ring.toList()

    /** The time by which the current utterance must have ended, for the caller's watchdog timer. */
    fun deadlineMs(): Long? = current?.let { (currentStartMs ?: currentSpeakMs) + expectedMs(it) }

    fun expectedMs(u: Utterance): Long = min(config.watchdogCapMs, config.watchdogBaseMs + config.watchdogPerCharMs * u.text.length)

    /** A new alert. While [muted] it is recorded and dropped (the visual alert still shows). */
    fun offer(u: Utterance, nowMs: Long, muted: Boolean = false): List<Decision> {
        if (muted) {
            record(Record(u, Outcome.MUTED, nowMs))
            return listOf(Decision.Dropped(u, Outcome.MUTED))
        }
        val out = ArrayList<Decision>()
        if (u.kind == AlertKind.LIMIT_CHANGE) {
            // Only the newest limit announcement matters: one still waiting is replaced, never a camera stage.
            for (e in queue.filter { it.kind == AlertKind.LIMIT_CHANGE }) {
                queue.remove(e)
                record(Record(e, Outcome.REPLACED, nowMs, byKind = u.kind))
                out += Decision.Replaced(e, u)
            }
        }
        if (isStage(u.kind)) {
            val same = queue.filter { it.poiId == u.poiId && isStage(it.kind) }
            for (e in same) {
                if (stageRank(u.kind) > stageRank(e.kind)) {
                    queue.remove(e)
                    record(Record(e, Outcome.REPLACED, nowMs, byKind = u.kind))
                    out += Decision.Replaced(e, u)
                } else {
                    // An earlier (or repeated) stage while a later one waits: the later one says it all.
                    record(Record(u, Outcome.STALE, nowMs, detail = "later stage waiting"))
                    out += Decision.Dropped(u, Outcome.STALE)
                    return out
                }
            }
        }
        queue += u
        out += Decision.Queued(u)
        return out
    }

    /** The car's latest distance to a POI, for the staleness rule. */
    fun onDistance(poiId: Long, distanceM: Double) {
        distances[poiId] = distanceM
    }

    fun next(nowMs: Long, channelBusy: Boolean): Decision {
        updateBusy(nowMs, channelBusy)
        current?.let { return Decision.Wait(deadlineMs()!!, "speaking") }
        dropStale(nowMs)
        if (queue.isEmpty()) return Decision.Idle
        if (nowMs < lastDoneMs + config.minGapMs) return Decision.Wait(lastDoneMs + config.minGapMs, "gap")
        val head = pick()
        val busy = channelBusy && nowMs >= ignoreBusyUntilMs
        if (busy) {
            val maxWait = if (head.kind == AlertKind.PASSING) config.passingBusyWaitMs else config.stageBusyWaitMs
            val deadline = head.queuedMs + maxWait
            if (nowMs < deadline) {
                if (deferred.add(head.id)) record(Record(head, Outcome.DEFERRED, nowMs, detail = "channel busy"))
                return Decision.Wait(deadline, "channel busy")
            }
        }
        queue.remove(head)
        current = head
        currentSpeakMs = nowMs
        currentStartMs = null
        return Decision.Speak(head, nowMs - head.queuedMs)
    }

    /** The engine began the utterance. Returns how long it waited, for the STAGE event. */
    fun onStarted(u: Utterance, nowMs: Long): Long? {
        if (current?.id != u.id) return null
        currentStartMs = nowMs
        return nowMs - u.queuedMs
    }

    fun onDone(u: Utterance, nowMs: Long) {
        if (current?.id != u.id) return
        val start = currentStartMs ?: currentSpeakMs
        record(Record(u, Outcome.SPOKEN, nowMs, startMs = start, waitedMs = start - u.queuedMs))
        finish(nowMs)
    }

    fun onInterrupted(u: Utterance, nowMs: Long) {
        if (current?.id != u.id) return
        record(Record(u, Outcome.INTERRUPTED, nowMs, startMs = currentStartMs ?: currentSpeakMs))
        finish(nowMs)
    }

    /** A non-SUCCESS `speak()` or `onError`: once → [Decision.Retry], again → [Decision.Fallback]. */
    fun onEngineError(u: Utterance, code: Int, nowMs: Long): Decision {
        if (current?.id == u.id) { current = null; currentStartMs = null }
        if (retried.add(u.id)) {
            queue.add(0, u)
            return Decision.Retry(u)
        }
        record(Record(u, Outcome.TTS_ERROR, nowMs, detail = "status $code"))
        return Decision.Fallback(u, code)
    }

    /** The audio-focus request's result; a refusal is only recorded (TTS plays anyway). */
    fun onFocus(u: Utterance, granted: Boolean, nowMs: Long) {
        if (!granted) record(Record(u, Outcome.FOCUS_DENIED, nowMs))
    }

    /** Frees an engine that never reported back. Returns the utterance given up on, or null. */
    fun onWatchdog(nowMs: Long): Utterance? {
        val u = current ?: return null
        if (nowMs < deadlineMs()!!) return null
        record(Record(u, Outcome.TIMED_OUT, nowMs, startMs = currentStartMs ?: currentSpeakMs))
        finish(nowMs)
        return u
    }

    private fun finish(nowMs: Long) {
        current = null
        currentStartMs = null
        lastDoneMs = nowMs
    }

    private fun updateBusy(nowMs: Long, channelBusy: Boolean) {
        if (!channelBusy) { busySinceMs = null; return }
        val since = busySinceMs ?: nowMs.also { busySinceMs = it }
        if (nowMs - since > config.busyIgnoreAfterMs && nowMs >= ignoreBusyUntilMs) {
            ignoreBusyUntilMs = nowMs + config.busyIgnoreForMs
        }
    }

    private fun dropStale(nowMs: Long) {
        val it = queue.iterator()
        while (it.hasNext()) {
            val u = it.next()
            if (u.kind != AlertKind.FIRST && u.kind != AlertKind.SECOND) continue
            val below = u.staleBelowM ?: continue
            val d = distances[u.poiId] ?: u.distanceM
            if (d < below) {
                it.remove()
                record(Record(u, Outcome.STALE, nowMs, detail = "d=${d.toInt()} < ${below.toInt()}"))
            }
        }
    }

    private fun pick(): Utterance = queue.minWith(
        compareBy<Utterance> { if (it.kind == AlertKind.PASSING) 0 else 1 }
            .thenByDescending { it.priority }
            .thenBy { distances[it.poiId] ?: it.distanceM }
            .thenBy { it.queuedMs },
    )

    private fun record(r: Record) {
        ring.addLast(r)
        while (ring.size > config.ringSize) ring.removeFirst()
    }

    companion object {
        fun isStage(k: AlertKind) = k == AlertKind.FIRST || k == AlertKind.SECOND || k == AlertKind.PASSING

        private fun stageRank(k: AlertKind) = when (k) {
            AlertKind.FIRST -> 0
            AlertKind.SECOND -> 1
            AlertKind.PASSING -> 2
            else -> -1
        }
    }
}

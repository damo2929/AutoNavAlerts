// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import kotlin.math.min

/**
 * The speech queue policy (DESIGN.md §12.3), pure with the clock passed in. `NavTts` drives it:
 * [offer] each alert's utterance, [next] whenever something changes (a new utterance, an
 * utterance ending, a timer), then tell it what the engine did.
 *
 * Policy: only a later stage replaces an earlier one still waiting for the same POI; a waiting
 * FIRST or SECOND is stale once the car is closer than [Utterance.staleBelowM]; PASSING goes first,
 * then priority, then distance; at least 400 ms between utterances; a stuck engine is freed by
 * [onWatchdog] (see [Config.watchdogBaseMs]), though one the engine still calls speaking is given
 * longer by [extendWhileSpeaking], up to [Config.watchdogCapMs]; an engine error is retried once,
 * then the caller falls back to the chime and banner. Ours is the only voice on the guidance
 * channel (the Navigation SDK's is off while we speak the directions, DESIGN.md §12.3), so nothing
 * waits behind another speaker. Every decision goes into a 50-entry [records] ring. Directions
 * (GUIDANCE, §12.3): a newer one replaces one waiting, they go stale by distance like a stage, and
 * mute never touches them (it is the alerts' control).
 *
 * No Android, no threads of its own and no clock: every call takes `nowMs` from the caller (the
 * app passes its time since boot; only differences are used), and the app's `NavTts` is the one
 * caller, from one thread at a time. Distances are metres. The engine never drops an event (§11
 * step 7), so this is the only place a stage can go unspoken, and every such decision is recorded.
 */
class SpeechScheduler(val config: Config = Config()) {
    data class Config(
        /** The quiet gap between two utterances, so two alerts don't run into one sentence. */
        val minGapMs: Long = 400,
        // The allowance is 3000 + 120 × characters ms, capped at 15 s (DESIGN.md §12.3 "Speaking watchdog").
        /**
         * Allowance before an utterance counts as stuck: numbers speak far longer than their characters
         * ("148 yards" is "one hundred and forty-eight yards"), and on the bench 1500 + 70/char cut every
         * SECOND off mid-sentence (2026-10-13). While the engine still says it is speaking the deadline
         * is extended in [watchdogExtendMs] steps, never past [watchdogCapMs] from its start.
         */
        val watchdogBaseMs: Long = 3_000,
        val watchdogPerCharMs: Long = 120,
        val watchdogCapMs: Long = 15_000,
        val watchdogExtendMs: Long = 1_500,
        /** The decision ring shown in More → Alerts and Diagnostics (§12.3). */
        val ringSize: Int = 50,
    )

    /**
     * One thing to say. [id] is unique per utterance (the TTS utterance id); [poiId] groups the stages
     * of one camera (directions are −2, limit changes −1, as `NavTts` sets them); [priority] is the engine's (§12.3);
     * [distanceM] the distance when queued; [chime] whether the earcon plays first; [queuedMs] when it
     * was offered, for the waited time written back onto the STAGE line.
     */
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

    /** How an utterance ended, as recorded in the ring (§12.3 "Every decision is recorded"). */
    enum class Outcome { SPOKEN, REPLACED, STALE, INTERRUPTED, TIMED_OUT, TTS_ERROR, MUTED, FOCUS_DENIED }

    /** One ring entry: the utterance, its outcome, when, and for a spoken one when it started and how long it waited. */
    data class Record(
        val utterance: Utterance,
        val outcome: Outcome,
        val atMs: Long,
        val startMs: Long? = null,
        val waitedMs: Long? = null,
        val byKind: AlertKind? = null,
        val detail: String? = null,
    ) {
        /** False only for FOCUS_DENIED, which the utterance speaks through; every other outcome ends it. */
        val terminal: Boolean get() = outcome != Outcome.FOCUS_DENIED

        /** One line for the engine snapshot (§15.5): `KIND poi=… OUTCOME waited=…ms by=… detail`. */
        fun describe(): String = buildString {
            append(utterance.kind).append(" poi=").append(utterance.poiId).append(' ').append(outcome)
            waitedMs?.let { append(" waited=").append(it).append("ms") }
            byKind?.let { append(" by=").append(it) }
            detail?.let { append(' ').append(it) }
        }

        /**
         * The `Tts …` line of §15.5 for an outcome decided in here (done, replaced, dropped stale, muted),
         * or null for one the caller logs where it happens: INTERRUPTED (with the host's flag), TIMED_OUT
         * (the watchdog) and TTS_ERROR (the error path); FOCUS_DENIED is logged at the request.
         */
        fun logLine(): String? {
            val u = utterance
            return when (outcome) {
                Outcome.SPOKEN -> "done ${u.kind} poi=${u.poiId} dur=${atMs - (startMs ?: atMs)} waited=${waitedMs ?: "-"}"
                Outcome.REPLACED -> "replaced ${u.kind} poi=${u.poiId} by=$byKind"
                Outcome.STALE -> "dropped ${u.kind} poi=${u.poiId} reason=stale ${detail ?: ""}"
                Outcome.MUTED -> "muted ${u.kind} poi=${u.poiId}"
                else -> null
            }
        }
    }

    /** What the caller should do now. [offer] returns several; [next] and the error path return one. */
    sealed interface Decision {
        /** Start this one now; [waitedMs] is how long it queued. */
        data class Speak(val utterance: Utterance, val waitedMs: Long) : Decision
        /** Nothing to do before [untilMs] (call [next] again then, or sooner if something changes). */
        data class Wait(val untilMs: Long, val reason: String) : Decision
        /** Nothing queued and nothing speaking. */
        data object Idle : Decision
        /** Accepted into the queue; call [next] to see whether it speaks now. */
        data class Queued(val utterance: Utterance) : Decision
        /** [old] was waiting and is dropped in favour of [by] (a later stage of the same POI, or a newer direction). */
        data class Replaced(val old: Utterance, val by: Utterance) : Decision
        /** Not queued: muted, or an earlier stage offered while a later one of the same POI already waits. */
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
    private val distances = HashMap<Long, Double>()
    private val retried = HashSet<Long>()
    private val ring = ArrayDeque<Record>()

    /** The utterance the engine is speaking, or null. */
    val speaking: Utterance? get() = current
    /** A copy of the queue, in offer order (the pick order is [pick]'s, not this). */
    val waiting: List<Utterance> get() = queue.toList()
    /** When the current utterance began: the engine's `onStart` when it has reported, else when it was handed over. */
    private val startOrSpeakMs: Long get() = currentStartMs ?: currentSpeakMs

    /** The decisions so far, oldest first, at most [Config.ringSize]. */
    fun records(): List<Record> = ring.toList()

    /** The time by which the current utterance must have ended, for the caller's watchdog timer. */
    fun deadlineMs(): Long? = current?.let { startOrSpeakMs + expectedMs(it) }

    /** How long [u] may take before the watchdog frees the engine: base + per character, capped (§12.3). */
    fun expectedMs(u: Utterance): Long = min(config.watchdogCapMs, config.watchdogBaseMs + config.watchdogPerCharMs * u.text.length)

    /** A new alert. While [muted] it is recorded and dropped (the visual alert still shows). */
    fun offer(u: Utterance, nowMs: Long, muted: Boolean = false): List<Decision> {
        if (muted) {
            record(Record(u, Outcome.MUTED, nowMs))
            return listOf(Decision.Dropped(u, Outcome.MUTED))
        }
        val out = ArrayList<Decision>()
        // What [u] competes with: for a limit announcement or a direction, one of the same kind still
        // waiting (only the newest matters, never a camera stage); for a stage, the same POI's waiting stage.
        val rivals = when {
            u.kind == AlertKind.LIMIT_CHANGE || u.kind == AlertKind.GUIDANCE -> queue.filter { it.kind == u.kind }
            isStage(u.kind) -> queue.filter { it.poiId == u.poiId && isStage(it.kind) }
            else -> emptyList()
        }
        for (e in rivals) {
            // Only a later stage replaces an earlier one (AlertKind lists FIRST, SECOND, PASSING in that
            // order); an earlier or repeated stage while a later one waits is dropped: the later says it all.
            if (isStage(u.kind) && u.kind.ordinal <= e.kind.ordinal) {
                record(Record(u, Outcome.STALE, nowMs, detail = "later stage waiting"))
                out += Decision.Dropped(u, Outcome.STALE)
                return out
            }
            queue.remove(e)
            record(Record(e, Outcome.REPLACED, nowMs, byKind = u.kind))
            out += Decision.Replaced(e, u)
        }
        queue += u
        out += Decision.Queued(u)
        return out
    }

    /** The car's latest distance to a POI, for the staleness rule; kept only while it has speech queued. */
    fun onDistance(poiId: Long, distanceM: Double) {
        if (queue.any { it.poiId == poiId } || current?.poiId == poiId) distances[poiId] = distanceM
    }

    /**
     * Mute pressed (DESIGN.md §12.3): what is speaking and everything waiting is recorded as MUTED
     * and dropped, PASSING included (mute silences speech only; the cards still show). Returns
     * whether something was speaking, so the caller stops the engine.
     */
    fun muteAll(nowMs: Long): Boolean {
        // Directions aren't alerts: mute leaves them speaking and waiting (DESIGN.md §12.3).
        val speakingNow = current?.takeIf { it.kind != AlertKind.GUIDANCE }
        if (speakingNow != null) finish(Record(speakingNow, Outcome.MUTED, nowMs, startMs = startOrSpeakMs))
        val alerts = queue.filter { it.kind != AlertKind.GUIDANCE }
        for (u in alerts) record(Record(u, Outcome.MUTED, nowMs))
        queue.removeAll(alerts.toSet())
        distances.keys.retainAll(queue.map { it.poiId }.toSet() + listOfNotNull(current?.poiId))
        return speakingNow != null
    }

    /**
     * What to do now: [Decision.Wait] while something is speaking (until its deadline) or inside the
     * gap after the last one; [Decision.Idle] when nothing waits; else [Decision.Speak] with the
     * picked utterance, which becomes [speaking]. Stale FIRST/SECOND/GUIDANCE entries are dropped first.
     */
    fun next(nowMs: Long): Decision {
        current?.let { return Decision.Wait(deadlineMs()!!, "speaking") }
        dropStale(nowMs)
        if (queue.isEmpty()) return Decision.Idle
        if (nowMs < lastDoneMs + config.minGapMs) return Decision.Wait(lastDoneMs + config.minGapMs, "gap")
        val head = pick()
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

    /** The engine finished [u]: recorded as SPOKEN with its start and wait; ignored if [u] isn't current. */
    fun onDone(u: Utterance, nowMs: Long) {
        if (current?.id != u.id) return
        val start = startOrSpeakMs
        finish(Record(u, Outcome.SPOKEN, nowMs, startMs = start, waitedMs = start - u.queuedMs))
    }

    /** The engine's `onStop(interrupted)`: the car's audio handling cut [u] off (§12.3 "Speaking watchdog"). */
    fun onInterrupted(u: Utterance, nowMs: Long) {
        if (current?.id != u.id) return
        finish(Record(u, Outcome.INTERRUPTED, nowMs, startMs = startOrSpeakMs))
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

    /**
     * The watchdog fired but the engine still says it is speaking (§12.3 "Speaking watchdog"): the
     * utterance isn't stuck, so it may go on for another [Config.watchdogExtendMs] unless it has
     * already had [Config.watchdogCapMs] since the engine started it. Returns the milliseconds since
     * that start when it may go on, else null (nothing current, not started, or at the cap: the caller
     * then frees the engine with [onWatchdog]).
     */
    fun extendWhileSpeaking(nowMs: Long): Long? {
        val since = nowMs - (currentStartMs ?: return null)
        return since.takeIf { it < config.watchdogCapMs }
    }

    /** Frees an engine that never reported back. Returns the utterance given up on, or null. */
    fun onWatchdog(nowMs: Long): Utterance? {
        val u = current ?: return null
        if (nowMs < deadlineMs()!!) return null
        finish(Record(u, Outcome.TIMED_OUT, nowMs, startMs = startOrSpeakMs))
        return u
    }

    /** The current utterance ended as [r] says: recorded, the engine free, the gap timed from [r]'s time. */
    private fun finish(r: Record) {
        record(r)
        current = null
        currentStartMs = null
        lastDoneMs = r.atMs
    }

    private fun dropStale(nowMs: Long) {
        val it = queue.iterator()
        while (it.hasNext()) {
            val u = it.next()
            if (u.kind != AlertKind.FIRST && u.kind != AlertKind.SECOND && u.kind != AlertKind.GUIDANCE) continue
            val below = u.staleBelowM ?: continue
            val d = distances[u.poiId] ?: u.distanceM
            if (d < below) {
                it.remove()
                record(Record(u, Outcome.STALE, nowMs, detail = "d=${d.toInt()} < ${below.toInt()}"))
            }
        }
    }

    // Order (§12.3): a PASSING first whatever its priority, then higher priority, then the nearer POI
    // (its latest distance when known), then first come, first served.
    private fun pick(): Utterance = queue.minWith(
        compareBy<Utterance> { if (it.kind == AlertKind.PASSING) 0 else 1 }
            .thenByDescending { it.priority }
            .thenBy { distances[it.poiId] ?: it.distanceM }
            .thenBy { it.queuedMs },
    )

    private fun record(r: Record) {
        ring.addLast(r)
        while (ring.size > config.ringSize) ring.removeFirst()
        // A finished utterance needs no retry bookkeeping, nor its POI's distance once nothing else waits.
        if (r.terminal) {
            retried.remove(r.utterance.id)
            if (queue.none { it.poiId == r.utterance.poiId }) distances.remove(r.utterance.poiId)
        }
    }

    companion object {
        /** The three camera stages: the only kinds that replace one another per POI. */
        fun isStage(k: AlertKind) = k == AlertKind.FIRST || k == AlertKind.SECOND || k == AlertKind.PASSING
    }
}

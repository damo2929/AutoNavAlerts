// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.alerts.SpeechScheduler
import io.autonav.alerts.core.alerts.SpeechScheduler.Decision
import io.autonav.alerts.core.alerts.SpeechScheduler.Outcome
import io.autonav.alerts.core.alerts.SpeechScheduler.Record
import io.autonav.alerts.core.alerts.SpeechScheduler.Utterance
import io.autonav.alerts.diag.EventLog
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Speech on the navigation-guidance channel (DESIGN.md §12.3), the alerts and the directions in
 * one voice: ducks media, never pauses it. The policy lives in the core [SpeechScheduler]
 * (staleness by distance, PASSING never dropped, retry then fall back, a 50-entry record ring); this class is the Android side: the `TextToSpeech` engine, audio focus, the chime,
 * the watchdog timer and the `Tts …` lines in alert-debug. Everything runs on the main handler.
 *
 * Rules upheld here (AGENTS.md §3.2; DESIGN.md §9.4, §12.3): audio focus is
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with `setWillPauseWhenDucked(false)`, so media is ducked,
 * never paused; it is taken just before speaking and given back when nothing waits. Mute
 * ([muteNow], [recordMuted]) silences speech only; the cards are the coordinator's and still
 * show. The voice is the phone's default text-to-speech voice (owner, 2026-10-23); there is no
 * voice setting. The watchdog's allowance (3000 + 120 × characters ms, capped at 15 s) is the
 * scheduler's; [WATCHDOG_HARD_CAP_MS] is the cap applied here while the engine still says it is
 * speaking. One engine failure is retried once, then the chime, the banner and a one-off toast
 * (§12.3 Errors), so an alert is never silently lost.
 *
 * Lifecycle: a Hilt singleton. `NavSession` calls [warmUp] when the car connects and
 * `DirectionsSpeaker` when guidance starts; the phone's Settings use [speak] for the test
 * button; [shutdown] is for the phone app's exit. Callers may be on any thread: every public
 * method posts to the main handler, and the engine's callbacks are posted there too, so the
 * scheduler is only ever touched on the main thread.
 */
@Singleton
class NavTts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventLog: EventLog,
) {
    private val _healthy = MutableStateFlow(true)
    /**
     * False once the engine failed to start or speech fell back to the chime: the directions then go
     * back to the Navigation SDK's own voice for the rest of the route (DESIGN.md §12.3); true when ready.
     */
    val healthy: StateFlow<Boolean> = _healthy

    /** Audio focus and the tone generator's stream. */
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var focus: AudioFocusRequest? = null
    /** The pure queue policy (§12.3); driven on the main handler with `elapsedRealtime` as its clock. */
    private val scheduler = SpeechScheduler()
    /** Utterance ids, unique for the process; the engine id is `autonav-<id>`. */
    private var nextId = 1L
    /** The utterance handed to the engine, by its engine id. */
    private var current: Utterance? = null
    /** When the engine reported `onStart` for [current]; null until it has. */
    private var currentStartedMs: Long? = null
    private var currentEngineId: String? = null
    private var lastRecord: Record? = null
    private var toastShown = false
    /** The engine failed to start: queued speech falls back to the chime until a retry succeeds. */
    private var initFailed = false
    private var initAttempts = 0
    /** Consecutive engine errors; two in a row restart the engine (a dead TTS service). */
    private var engineErrors = 0
    /** One tone generator for the chime and the fallback beep, made on first use. */
    private var tone: ToneGenerator? = null

    private val _records = MutableStateFlow<List<Record>>(emptyList())
    /** The scheduler's decisions, oldest first, at most 50 (More → Alerts, Diagnostics). */
    val records: StateFlow<List<Record>> = _records

    /**
     * Set by the coordinator: every terminal outcome of an utterance (spoken, replaced, stale,
     * interrupted, timed out, error, muted), with how long it waited when it did speak, so the
     * STAGE trace line can say whether the alert was heard (§12.3, §15.5).
     */
    @Volatile var spokenCallback: ((poiId: Long, kind: AlertKind, spoken: Boolean, delayMs: Long?, outcome: String) -> Unit)? = null

    // Navigation-guidance usage: on Android Auto this is the car's navigation audio stream, the one turn
    // prompts use, so the car's own "navigation volume" applies (§12.3).
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val pumpRunnable = Runnable { pump() }
    private val watchdogRunnable = Runnable { onWatchdog() }
    /** Posted after a failed engine start, at [RETRY_INIT_MS] intervals. */
    private val retryInitRunnable = Runnable { warmUp() }

    /**
     * Starts the engine if it isn't up, so the first alert isn't delayed by engine start-up (§12.3).
     * Idempotent. The init callback picks the voice, logs `Tts ready engine= lang= voice=` and pumps
     * anything queued meanwhile; on failure it schedules a retry and chimes for what waits.
     */
    fun warmUp() {
        if (tts != null) return
        main.removeCallbacks(retryInitRunnable)
        initAttempts++
        tts = TextToSpeech(context) { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS
                if (!ready) {
                    // Never leave a dead engine in place: shut it down, chime meanwhile, and try again later.
                    initFailed = true
                    _healthy.value = false
                    runCatching { tts?.shutdown() }
                    tts = null
                    val retryMs = RETRY_INIT_MS.getOrElse(initAttempts - 1) { RETRY_INIT_MS.last() }
                    log("init failed status=$status attempt=$initAttempts retry in ${retryMs / 1000} s")
                    main.postDelayed(retryInitRunnable, retryMs)
                    pump()
                    return@post
                }
                initFailed = false
                _healthy.value = true
                initAttempts = 0
                engineErrors = 0
                val engine = tts ?: return@post
                val lang = pickLanguage(engine)
                val voice = runCatching { engine.voice?.name }.getOrNull() ?: "-"
                engine.setAudioAttributes(attrs)
                engine.setOnUtteranceProgressListener(listener)
                log("ready engine=${runCatching { engine.defaultEngine }.getOrNull()} lang=$lang voice=$voice")
                pump()
            }
        }
    }

    /** The engine's default voice, or the fallbacks of §12.3; returns the language tag used, for the log. */
    private fun pickLanguage(engine: TextToSpeech): String {
        // The phone's default TTS voice, as set in Android's text-to-speech settings (owner, 2026-10-23;
        // DESIGN.md §12.3): nothing is changed unless its language has no voice data; then the phone's
        // own language, then any English.
        val default = runCatching { engine.defaultVoice }.getOrNull()
        val wanted = default?.locale ?: Locale.getDefault()
        var r = engine.isLanguageAvailable(wanted)
        if (default != null && r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return wanted.toLanguageTag()
        for (fallback in listOf(Locale.getDefault(), Locale.ENGLISH)) {
            r = engine.setLanguage(fallback)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                log("language ${wanted.toLanguageTag()} unavailable (status=$r), using ${fallback.toLanguageTag()}")
                return fallback.toLanguageTag()
            }
        }
        log("language ${wanted.toLanguageTag()} unavailable and no fallback (status=$r)")
        return "none"
    }

    /** Releases the engine, the tone generator and audio focus. Nothing queued survives. Main thread. */
    fun shutdown() {
        main.removeCallbacks(pumpRunnable)
        main.removeCallbacks(watchdogRunnable)
        main.removeCallbacks(retryInitRunnable)
        runCatching { tone?.release() }
        tone = null
        tts?.shutdown()
        tts = null
        ready = false
        current = null
        currentEngineId = null
        abandonFocus()
    }

    /**
     * Queue speech. [kind], [distanceM] and [staleBelowM] drive the policy: a later stage for the
     * same POI replaces a waiting one; a waiting FIRST/SECOND isn't spoken once the car is closer
     * than [staleBelowM]; PASSING is never dropped. The 4-argument form (a limit announcement, the
     * phone's test button) is a FIRST with no staleness.
     *
     * Parameters: [priority] is the queue order (PASSING ≥ 100 also gets the chime when it's on, §12.3;
     * GUIDANCE 80; a limit change and FIRST/SECOND lower); [poiId] groups stages of one camera (directions
     * are -2, limit changes -1); [distanceM] and [staleBelowM] in metres. Any thread.
     */
    fun speak(
        text: String, priority: Int, poiId: Long, chime: Boolean,
        kind: AlertKind = AlertKind.FIRST, distanceM: Double = 0.0, staleBelowM: Double? = null,
    ) {
        main.post {
            warmUp()
            val now = now()
            val u = Utterance(nextId++, poiId, kind, text, priority, distanceM, staleBelowM, chime, now)
            for (d in scheduler.offer(u, now, muted = false)) {
                if (d is Decision.Queued) log("queued $kind poi=$poiId pri=$priority d=${distanceM.toInt()} queue=${scheduler.waiting.size}")
            }
            syncRecords()
            pump()
        }
    }

    /**
     * Mute pressed (DESIGN.md §12.3): stops what is being said and drops what is waiting, PASSING
     * included, each recorded as MUTED. Mute silences speech only: the cards still show.
     */
    fun muteNow() {
        main.post {
            val wasSpeaking = scheduler.muteAll(now())
            if (wasSpeaking) runCatching { tts?.stop() }
            clearCurrent()
            log("mute: stopped=${wasSpeaking}")
            afterTerminal()
        }
    }

    /** A muted alert (speech off): recorded as MUTED so the ring and the STAGE line show why it was silent. */
    fun recordMuted(poiId: Long, kind: AlertKind, text: String = "", distanceM: Double = 0.0) {
        main.post {
            val now = now()
            scheduler.offer(Utterance(nextId++, poiId, kind, text, 0, distanceM, null, false, now), now, muted = true)
            syncRecords()
        }
    }

    /** The car's latest distance to a POI, for the staleness rule (called per fix for tracked POIs). */
    fun onDistance(poiId: Long, distanceM: Double) {
        main.post { scheduler.onDistance(poiId, distanceM) }
    }

    /** For the engine snapshot (§15.5): what's speaking and what's waiting. */
    fun snapshot(): String {
        val s = scheduler.speaking
        val w = scheduler.waiting
        val sb = StringBuilder()
        sb.append("tts ready=").append(ready).append(" speaking=")
        sb.append(s?.let { "${it.kind} poi=${it.poiId} \"${it.text}\"" } ?: "-")
        sb.append(" waiting=").append(w.size)
        for (u in w) sb.append("\n  ").append(u.kind).append(" poi=").append(u.poiId).append(" pri=").append(u.priority).append(" \"").append(u.text).append('"')
        val recent = scheduler.records().takeLast(5)
        if (recent.isNotEmpty()) sb.append("\nrecent:")
        for (r in recent.asReversed()) sb.append("\n  ").append(describe(r))
        return sb.toString()
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {
            main.post {
                val u = forEngineId(id) ?: return@post
                currentStartedMs = now()
                scheduler.onStarted(u, now())
                scheduleWatchdog()
            }
        }

        override fun onDone(id: String?) {
            main.post {
                val u = forEngineId(id) ?: return@post
                scheduler.onDone(u, now())
                clearCurrent()
                afterTerminal()
            }
        }

        override fun onStop(id: String?, interrupted: Boolean) {
            main.post {
                val u = forEngineId(id) ?: return@post
                scheduler.onInterrupted(u, now())
                clearCurrent()
                log("interrupted ${u.kind} poi=${u.poiId} byHost=$interrupted")
                afterTerminal()
            }
        }

        override fun onError(id: String?, errorCode: Int) {
            main.post { forEngineId(id)?.let { engineError(it, errorCode) } }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) {
            main.post { forEngineId(id)?.let { engineError(it, -1) } }
        }

    }

    /** The utterance an engine callback is about, or null for a stale callback from an earlier one. */
    private fun forEngineId(id: String?): Utterance? = if (id != null && id == currentEngineId) current else null

    /** Nothing is being spoken any more: forget it and stop the watchdog. */
    private fun clearCurrent() {
        current = null
        currentEngineId = null
        currentStartedMs = null
        main.removeCallbacks(watchdogRunnable)
    }

    /** After any terminal outcome: write the records, drop focus if the queue is empty, start the next. */
    private fun afterTerminal() {
        syncRecords()
        if (scheduler.waiting.isEmpty()) abandonFocus()
        pump()
    }

    /**
     * Asks the scheduler what to do now and does it: start the next utterance, wait until its time
     * (a bounded wait), or go idle and release focus. Re-entered from every callback and timer.
     */
    private fun pump() {
        main.removeCallbacks(pumpRunnable)
        if (!ready) {
            // No engine (it failed to start): each waiting alert goes through the error path, so it is
            // retried once and then chimed, never left queued and silent for the whole drive.
            if (initFailed && current == null) {
                val d = scheduler.next(now())
                if (d is Decision.Speak) { current = d.utterance; engineError(d.utterance, ERROR_NO_ENGINE) }
                else if (d is Decision.Wait) main.postDelayed(pumpRunnable, max(50L, d.untilMs - now()))
            }
            return
        }
        val now = now()
        when (val d = scheduler.next(now)) {
            is Decision.Speak -> start(d.utterance, d.waitedMs)
            // While we speak, onDone/onStop/the watchdog pump again: no polling loop meanwhile.
            is Decision.Wait -> if (d.reason != "speaking") main.postDelayed(pumpRunnable, max(50L, d.untilMs - now))
            Decision.Idle -> if (current == null) abandonFocus()
            else -> Unit
        }
        syncRecords()
    }

    /**
     * Hands [u] to the engine: focus first (recorded either way), the chime when asked (180 ms, with
     * 250 ms before the words so they don't overlap it), then `speak()` with QUEUE_FLUSH, since the
     * scheduler is the only queue. [waitedMs] is how long it waited, for the log and the STAGE line.
     */
    private fun start(u: Utterance, waitedMs: Long) {
        val engine = tts ?: return
        current = u
        val engineId = "autonav-${u.id}"
        currentEngineId = engineId
        log("start ${u.kind} poi=${u.poiId} waited=$waitedMs queue=${scheduler.waiting.size}")
        val granted = requestFocus()
        scheduler.onFocus(u, granted, now())
        if (!granted) log("focus=denied ${u.kind} poi=${u.poiId}")
        if (u.chime) beep(180)
        val delay = if (u.chime) 250L else 0L
        main.postDelayed({
            if (current?.id != u.id) return@postDelayed
            val r = runCatching { engine.speak(u.text, TextToSpeech.QUEUE_FLUSH, null, engineId) }.getOrDefault(TextToSpeech.ERROR)
            if (r != TextToSpeech.SUCCESS) engineError(u, r) else { engineErrors = 0; scheduleWatchdog() }
        }, delay)
        scheduleWatchdog()
    }

    /**
     * A failed `speak()` or an `onError` for [u] (§12.3 Errors): the scheduler says retry once, then
     * fall back to the chime; [healthy] goes false on the fallback, which hands the directions back
     * to the SDK's voice (`GuidanceController`).
     */
    private fun engineError(u: Utterance, code: Int) {
        clearCurrent()
        // Two errors in a row from a running engine: its service has most likely died. Start a new one.
        if (code != ERROR_NO_ENGINE && ++engineErrors >= 2 && tts != null) {
            log("engine restart after $engineErrors errors")
            runCatching { tts?.shutdown() }
            tts = null
            ready = false
            engineErrors = 0
            initFailed = true
            main.post(retryInitRunnable)
        }
        when (val d = scheduler.onEngineError(u, code, now())) {
            is Decision.Retry -> { log("error status=$code ${u.kind} poi=${u.poiId} retry"); pump() }
            is Decision.Fallback -> {
                log("error status=${d.code} ${u.kind} poi=${u.poiId} fallback=chime")
                _healthy.value = false
                beep(300)
                if (!toastShown) {
                    toastShown = true
                    runCatching { Toast.makeText(context, "Speech failed: alerts are shown and chimed", Toast.LENGTH_LONG).show() }
                }
                afterTerminal()
            }
            else -> pump()
        }
    }

    /** Arms the stuck-speech timer at the scheduler's deadline for what is speaking (§12.3 watchdog). */
    private fun scheduleWatchdog() {
        main.removeCallbacks(watchdogRunnable)
        val deadline = scheduler.deadlineMs() ?: return
        main.postDelayed(watchdogRunnable, max(100L, deadline - now() + 50))
    }

    /** The deadline passed: extend while the engine is audibly speaking (up to the hard cap), else cut it off as TTS_STUCK. */
    private fun onWatchdog() {
        // Still audibly speaking? Then it isn't stuck: give it longer (up to the cap) rather than cut
        // it off. On the bench every SECOND ("…, 148 yards") was killed mid-sentence (2026-10-13).
        val speakingNow = runCatching { tts?.isSpeaking == true }.getOrDefault(false)
        val startedMs = currentStartedMs
        if (speakingNow && startedMs != null && now() - startedMs < WATCHDOG_HARD_CAP_MS) {
            log("watchdog extend ${current?.kind} poi=${current?.poiId} speaking=true after=${now() - startedMs}ms")
            main.postDelayed(watchdogRunnable, 1_500)
            return
        }
        val u = scheduler.onWatchdog(now())
        if (u == null) { scheduleWatchdog(); return }
        log("stuck ${u.kind} poi=${u.poiId} after=${scheduler.expectedMs(u)}ms")
        runCatching { tts?.stop() }
        clearCurrent()
        afterTerminal()
    }

    /** Every new scheduler record becomes a `Tts …` line and, for terminal outcomes, the coordinator's callback. */
    private fun syncRecords() {
        val all = scheduler.records()
        val last = lastRecord
        val from = if (last == null) 0 else all.lastIndexOf(last) + 1
        for (i in from until all.size) onRecord(all[i])
        lastRecord = all.lastOrNull()
        _records.value = all
    }

    /** One scheduler record: its `Tts …` line (where not logged at the event) and, if terminal, the coordinator's callback. */
    private fun onRecord(r: Record) {
        val u = r.utterance
        val terminal = when (r.outcome) {
            Outcome.SPOKEN -> { log("done ${u.kind} poi=${u.poiId} dur=${r.atMs - (r.startMs ?: r.atMs)} waited=${r.waitedMs ?: "-"}"); true }
            Outcome.REPLACED -> { log("replaced ${u.kind} poi=${u.poiId} by=${r.byKind}"); true }
            Outcome.STALE -> { log("dropped ${u.kind} poi=${u.poiId} reason=stale ${r.detail ?: ""}"); true }
            Outcome.INTERRUPTED -> true // logged where it happened, with the host flag
            Outcome.TIMED_OUT -> true // logged by the watchdog
            Outcome.TTS_ERROR -> true // logged by engineError
            Outcome.MUTED -> { log("muted ${u.kind} poi=${u.poiId}"); true }
            Outcome.FOCUS_DENIED -> false
        }
        if (terminal) spokenCallback?.invoke(u.poiId, u.kind, r.outcome == Outcome.SPOKEN, r.waitedMs, r.outcome.name)
    }

    /** Kept only when granted: a refused request isn't "held", so the next utterance asks again. */
    private fun requestFocus(): Boolean {
        focus?.let { return true }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .build()
        val r = runCatching { audio.requestAudioFocus(req) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val granted = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focus = req
        return granted
    }

    /** One tone generator for the whole session (each one holds a native AudioTrack), released in [shutdown]. */
    private fun beep(ms: Int) {
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, ms)
        }
    }

    /** Gives focus back so media comes up from the duck; only called when nothing waits. */
    private fun abandonFocus() {
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
    }

    /** A `Tts …` line in alert-debug (§15.5). */
    private fun log(msg: String) = eventLog.record(EventLog.Channel.ALERT, "Tts", msg)

    /** Monotonic: a clock change (NTP, GPS time, a time zone) must never stall or rush speech. */
    private fun now() = SystemClock.elapsedRealtime()

    /** A record's time ([Record.atMs], on [now]'s clock) as wall-clock milliseconds, for display. */
    fun wallMs(atMs: Long): Long = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - atMs)

    companion object {
        /** An utterance the engine still calls "speaking" is cut off only after this long (DESIGN.md §12.3). */
        private const val WATCHDOG_HARD_CAP_MS = 15_000L
        /** Waits before trying a failed engine again: 5 s, 30 s, then every minute. */
        private val RETRY_INIT_MS = longArrayOf(5_000, 30_000, 60_000)
        /**
         * engineError code when there is no engine at all (init failed).
         *
         * A code of our own, outside the engine's range, so the restart rule can tell it from a real engine error.
         */
        private const val ERROR_NO_ENGINE = -100

        /** One line for a record: `KIND poi=… OUTCOME waited=…`. */
        fun describe(r: Record): String {
            val u = r.utterance
            val sb = StringBuilder().append(u.kind).append(" poi=").append(u.poiId).append(' ').append(r.outcome)
            r.waitedMs?.let { sb.append(" waited=").append(it).append("ms") }
            r.byKind?.let { sb.append(" by=").append(it) }
            r.detail?.let { sb.append(' ').append(it) }
            return sb.toString()
        }
    }
}

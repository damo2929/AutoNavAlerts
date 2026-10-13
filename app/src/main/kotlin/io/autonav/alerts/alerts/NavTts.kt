// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max

/**
 * Speech on the navigation-guidance channel (DESIGN.md §12.3): ducks media, never pauses it, and
 * queues behind the Navigation SDK's own prompts. The policy lives in the core [SpeechScheduler]
 * (bounded waits, staleness by distance, PASSING never dropped, retry then fall back, a 50-entry
 * record ring); this class is the Android side: the `TextToSpeech` engine, audio focus, the chime,
 * the watchdog timer and the `Tts …` lines in alert-debug. Everything runs on the main handler.
 */
@Singleton
class NavTts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventLog: EventLog,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var focus: AudioFocusRequest? = null
    private val scheduler = SpeechScheduler()
    private var nextId = 1L
    /** The utterance handed to the engine, by its engine id. */
    private var current: Utterance? = null
    private var currentEngineId: String? = null
    private var lastRecord: Record? = null
    private var toastShown = false

    private val _records = MutableStateFlow<List<Record>>(emptyList())
    /** The scheduler's decisions, oldest first, at most 50 (More → Alerts, Diagnostics). */
    val records: StateFlow<List<Record>> = _records

    /**
     * Set by the coordinator: every terminal outcome of an utterance (spoken, replaced, stale,
     * interrupted, timed out, error, muted), with how long it waited when it did speak, so the
     * STAGE trace line can say whether the alert was heard (§12.3, §15.5).
     */
    @Volatile var spokenCallback: ((poiId: Long, kind: AlertKind, spoken: Boolean, delayMs: Long?, outcome: String) -> Unit)? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val pumpRunnable = Runnable { pump() }
    private val watchdogRunnable = Runnable { onWatchdog() }

    fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS
                if (!ready) { log("init failed status=$status"); return@post }
                val engine = tts ?: return@post
                val lang = pickLanguage(engine)
                engine.setAudioAttributes(attrs)
                engine.setOnUtteranceProgressListener(listener)
                log("ready engine=${runCatching { engine.defaultEngine }.getOrNull()} lang=$lang")
                pump()
            }
        }
    }

    private fun pickLanguage(engine: TextToSpeech): String {
        // British English first (owner, 2026-10-13; DESIGN.md §12.3), then the phone's own language, then any English.
        val wanted = Locale.UK
        var r = engine.setLanguage(wanted)
        if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return wanted.toLanguageTag()
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

    fun shutdown() {
        main.removeCallbacks(pumpRunnable)
        main.removeCallbacks(watchdogRunnable)
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
                log("interrupted $kindOf poi=${u.poiId} byHost=$interrupted")
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

        private val kindOf: String get() = current?.kind?.name ?: "?"
    }

    private fun forEngineId(id: String?): Utterance? = if (id != null && id == currentEngineId) current else null

    private fun clearCurrent() {
        current = null
        currentEngineId = null
        main.removeCallbacks(watchdogRunnable)
    }

    private fun afterTerminal() {
        syncRecords()
        if (scheduler.waiting.isEmpty()) abandonFocus()
        pump()
    }

    private fun sdkSpeaking(): Boolean = runCatching {
        audio.activePlaybackConfigurations.any { c: AudioPlaybackConfiguration ->
            c.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        }
    }.getOrDefault(false)

    private fun pump() {
        main.removeCallbacks(pumpRunnable)
        if (!ready) return
        val now = now()
        // Our own voice shows on the channel too: only look while we aren't speaking.
        val busy = current == null && sdkSpeaking()
        when (val d = scheduler.next(now, channelBusy = busy)) {
            is Decision.Speak -> start(d.utterance, d.waitedMs, busy)
            is Decision.Wait -> main.postDelayed(pumpRunnable, max(50L, d.untilMs - now))
            Decision.Idle -> if (current == null) abandonFocus()
            else -> Unit
        }
        syncRecords()
    }

    private fun start(u: Utterance, waitedMs: Long, busy: Boolean) {
        val engine = tts ?: return
        current = u
        val engineId = "autonav-${u.id}"
        currentEngineId = engineId
        log("start ${u.kind} poi=${u.poiId} waited=$waitedMs sdkSpeaking=$busy queue=${scheduler.waiting.size}")
        val granted = requestFocus()
        scheduler.onFocus(u, granted, now())
        if (!granted) log("focus=denied ${u.kind} poi=${u.poiId}")
        if (u.chime) runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
        }
        val delay = if (u.chime) 250L else 0L
        main.postDelayed({
            if (current?.id != u.id) return@postDelayed
            val r = runCatching { engine.speak(u.text, TextToSpeech.QUEUE_FLUSH, null, engineId) }.getOrDefault(TextToSpeech.ERROR)
            if (r != TextToSpeech.SUCCESS) engineError(u, r) else scheduleWatchdog()
        }, delay)
        scheduleWatchdog()
    }

    private fun engineError(u: Utterance, code: Int) {
        clearCurrent()
        when (val d = scheduler.onEngineError(u, code, now())) {
            is Decision.Retry -> { log("error status=$code ${u.kind} poi=${u.poiId} retry"); pump() }
            is Decision.Fallback -> {
                log("error status=${d.code} ${u.kind} poi=${u.poiId} fallback=chime")
                runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP2, 300) }
                if (!toastShown) {
                    toastShown = true
                    runCatching { Toast.makeText(context, "Speech failed: alerts are shown and chimed", Toast.LENGTH_LONG).show() }
                }
                afterTerminal()
            }
            else -> pump()
        }
    }

    private fun scheduleWatchdog() {
        main.removeCallbacks(watchdogRunnable)
        val deadline = scheduler.deadlineMs() ?: return
        main.postDelayed(watchdogRunnable, max(100L, deadline - now() + 50))
    }

    private fun onWatchdog() {
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
            Outcome.DEFERRED -> { log("wait ${u.kind} poi=${u.poiId} reason=channel busy"); false }
        }
        if (terminal) spokenCallback?.invoke(u.poiId, u.kind, r.outcome == Outcome.SPOKEN, r.waitedMs, r.outcome.name)
    }

    private fun requestFocus(): Boolean {
        focus?.let { return true }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .build()
        val r = runCatching { audio.requestAudioFocus(req) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focus = req
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
    }

    private fun log(msg: String) = eventLog.record(EventLog.Channel.ALERT, "Tts", msg)

    private fun now() = System.currentTimeMillis()

    companion object {
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

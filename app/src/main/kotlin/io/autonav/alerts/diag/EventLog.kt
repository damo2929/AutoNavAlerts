// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.diag

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.core.diag.BatchLog
import io.autonav.alerts.core.diag.FixSummary
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two always-on debug logs (DESIGN.md §15.5): `debug-nav.log` (route, map, navigation, the
 * location feeds) and `alert-debug.log` (alerts and what was done at each camera), under
 * `files/diag/`. Lines collect in memory and go to the file in one write every 15 s as a block
 * headed by the span and a fix summary; a forced [flush] writes what's pending now.
 *
 * Rules for callers: never a line per fix (the car can report several a second) (use [fix] for the header and [sample] for a
 * periodic line), nothing in marker redraws or `onGetTemplate`; [onChange] for sources and
 * verdicts that only matter when they change. Every line is mirrored to `Log.i("AutoNav")`.
 *
 * Any method may be called from any thread. The only thread that touches the files is the
 * `diag` [HandlerThread], exposed as [handler] for the drive recorder's flush tick.
 *
 * Lifecycle: a Hilt singleton created with the process (§2); `App.onCreate` writes `App start`
 * to both files, and the 15 s tick runs for the life of the process. The 1 MB cap and the one
 * rotation (`*.1.log`) are [BatchLog]'s, in `:core`, so the batching and rotation are JVM-tested;
 * this class adds the thread, the clock, the logcat mirror and the sample/on-change helpers.
 * Forced flushes come from the car session's destroy, `App.onTrimMemory`, the phone's
 * Diagnostics screen and the bench receiver (§15.5).
 */
@Singleton
class EventLog @Inject constructor(@ApplicationContext context: Context) {
    /** The two files of §15.5; [fileName] is under [dir]. */
    enum class Channel(val fileName: String) {
        NAV("debug-nav.log"),
        ALERT("alert-debug.log");

        /** The name used in export file names: `debug-nav`, `alert-debug`. */
        val shortName: String get() = fileName.removeSuffix(".log")
    }

    /** `files/diag/`: excluded from Android backup (§15.5). */
    val dir: File = File(context.filesDir, "diag")

    /** The only thread that writes the files. */
    private val thread = HandlerThread("diag").apply { start() }

    /** The diag thread. Other diagnostics (the drive recorder's one-second flush) post here. */
    val handler: Handler = Handler(thread.looper)

    /** One in-memory batch per channel: at most [MAX_LINES] lines, the oldest replaced by a `…dropped N` marker. */
    private val logs = EnumMap<Channel, BatchLog>(Channel::class.java).apply {
        for (c in Channel.entries) put(c, BatchLog(MAX_LINES, BatchLog.DEFAULT_MAX_BYTES))
    }
    /** Per-key state for [sample] (last time, epoch ms) and [onChange] (last value). */
    private val lastSample = ConcurrentHashMap<String, Long>()
    private val lastValue = ConcurrentHashMap<String, Any>()

    /** Epoch ms when the current block began, for the `--- from–to ---` header. */
    @Volatile private var blockStartMs = System.currentTimeMillis()

    /** The 15 s batch write (DESIGN.md §15.5), rescheduled after each run so writes never overlap. */
    private val tick = object : Runnable {
        override fun run() {
            flushNow()
            handler.postDelayed(this, BLOCK_MS)
        }
    }

    init {
        handler.postDelayed(tick, BLOCK_MS)
    }

    /** The channel's current file (its rotation sits beside it as `*.1.log`). */
    fun file(channel: Channel): File = File(dir, channel.fileName)

    /** The channel's rotation (if any) then its current file: what Save and Share export. */
    fun files(channel: Channel): List<File> = BatchLog.files(file(channel))

    /**
     * One line: `HH:mm:ss.SSS TAG msg`. Any thread.
     *
     * The stamp is the wall clock in the phone's zone, so a line can be matched to what the driver saw;
     * the mirror to logcat is for the emulator bench, since the owner's phone keeps no logcat (§15.5).
     */
    fun record(channel: Channel, tag: String, msg: String) {
        val line = STAMP.format(Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZONE)) + " " + tag + " " + msg
        logs.getValue(channel).record(line)
        Log.i(LOGCAT_TAG, "$tag $msg")
    }

    /** The same line in both files (process-level events: `App start`, `App clear`). */
    fun recordAll(tag: String, msg: String) {
        for (c in Channel.entries) record(c, tag, msg)
    }

    /** Counts a fix into the next block headers of both files. Never a line. */
    fun fix(summary: FixSummary) {
        for (l in logs.values) l.fix(summary)
    }

    /** Records at most once per [intervalMs] for [key]; [msg] is only built when it will be written. */
    fun sample(key: String, intervalMs: Long, channel: Channel, tag: String, msg: () -> String) {
        val now = System.currentTimeMillis()
        if (lastSample[key]?.let { now - it < intervalMs } == true) return
        lastSample[key] = now
        record(channel, tag, msg())
    }

    /** Records only when [value] differs from the last one seen for [key] (the first time counts). */
    fun onChange(key: String, value: Any?, channel: Channel, tag: String, msg: () -> String) {
        val v: Any = value ?: NULL
        if (lastValue.put(key, v) == v) return
        record(channel, tag, msg())
    }

    /**
     * Writes the pending blocks now on the diag thread. With [wait] the caller waits for it (at most
     * 500 ms): session end, a kill-level memory warning, export and the bench, where the next thing
     * may be reading the file or the process dying. Without it the main thread isn't held up.
     * Not used by the 15 s tick.
     */
    fun flush(reason: String, wait: Boolean = true) {
        Log.i(LOGCAT_TAG, "Diag flush reason=$reason")
        if (wait) runOnDiag { flushNow() } else handler.post { flushNow() }
    }

    /** The last [n] lines of the channel's file, oldest first, spanning the rotation if short. */
    fun tail(channel: Channel, n: Int): List<String> = BatchLog.tail(file(channel), n)

    /** The channel's current file size, bytes, for the Diagnostics screen. */
    fun sizeBytes(channel: Channel): Long = BatchLog.sizeBytes(file(channel))

    /** "yyyy-MM-dd HH:mm:ss" of the channel's oldest entry, or null when the files are empty. */
    fun oldest(channel: Channel): String? = BatchLog.oldest(file(channel))

    /** Writes what's pending, deletes both files, and starts them again with one `App clear`. */
    fun clear() {
        runOnDiag {
            flushNow()
            for (c in Channel.entries) BatchLog.clear(file(c))
            recordAll("App", "clear")
            flushNow()
        }
    }

    /** Runs [block] on the diag thread and waits up to [FLUSH_WAIT_MS]; runs it inline when already there (no self-deadlock). */
    private fun runOnDiag(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) { block(); return }
        val done = CountDownLatch(1)
        handler.post {
            try { block() } finally { done.countDown() }
        }
        done.await(FLUSH_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /** Diag thread only: one append per channel with something to say. */
    private fun flushNow() {
        val from = blockStartMs
        val to = System.currentTimeMillis()
        blockStartMs = to
        for ((c, l) in logs) {
            try {
                l.appendBlock(file(c), from, to)
            } catch (e: Exception) {
                Log.w(LOGCAT_TAG, "diag write failed for ${c.fileName}: $e")
            }
        }
    }

    private companion object {
        const val LOGCAT_TAG = "AutoNav"
        /** One write per channel every 15 s (DESIGN.md §15.5): a hard kill loses at most that much. */
        const val BLOCK_MS = 15_000L
        /** How long a waiting [flush] holds its caller: enough for two small appends, never a visible stall. */
        const val FLUSH_WAIT_MS = 500L
        /** The in-memory batch is bounded (§15.5): at 15 s that is well over the rate rules allow. */
        const val MAX_LINES = 400
        /** Stands in for a null value in [onChange]'s map, which can't hold null. */
        val NULL = Any()
        val ZONE: ZoneId = ZoneId.systemDefault()
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}

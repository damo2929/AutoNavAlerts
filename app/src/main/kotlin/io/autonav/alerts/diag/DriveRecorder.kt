// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.diag

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.App
import io.autonav.alerts.core.diag.DriveLog
import io.autonav.alerts.settings.SettingsStore
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Opt-in CSV of every fix and car report the app receives (DESIGN.md §15.5), so a drive can be
 * replayed on the bench exactly as the phone saw it. Enabled by the `driveRecordingOn` setting
 * (default off, not in the backup). Files `files/diag/drive-YYYYMMDD-HHMM.csv`, newest 3 kept,
 * 50 MB cap (an event is recorded and recording stops), buffered and flushed every second on the
 * diag thread.
 *
 * Callers: `start(sessionTag)` when a car session begins, `stop()` when it ends, `record(row)` with
 * a [DriveLog.RecordedFix] for each `Location` reaching the location source (phone and car,
 * `used=false` for the ones dropped while the other feed is fresh) and each car speed report. Any
 * thread.
 *
 * Lifecycle: a Hilt singleton. `NavSession` calls [start] on create and [stop] on destroy;
 * [AdaptiveLocationSource] and [CarDataSource] call [record]; the phone's Diagnostics screen
 * calls [flush], [files] and [latest] for Save and Share. The setting is watched on
 * `App.appScope`. The file is opened, flushed and closed on the diag thread ([EventLog.handler]),
 * never the main thread; [record] writes into the buffer under the lock and is cheap when no
 * file is open. The rows are `:core`'s [DriveLog.RecordedFix], written by [DriveLog.format] in
 * the column order of [DriveLog.HEADER], which the replay parses, so the two can't drift apart.
 */
@Singleton
class DriveRecorder @Inject constructor(
    @ApplicationContext context: Context,
    private val eventLog: EventLog,
    settings: SettingsStore,
) {
    /** `files/diag/`, shared with the two debug logs. */
    private val dir = File(context.filesDir, "diag")

    /** The `driveRecordingOn` setting; [wanted] is whether a session is up. A file is open only when both hold. */
    @Volatile private var enabled = false
    private var wanted = false
    private var sessionTag = ""
    private var writer: BufferedWriter? = null
    private var current: File? = null
    /** Bytes written to [current], for the 50 MB cap. */
    private var bytes = 0L

    /** The once-a-second flush on the diag thread (§15.5), so a hard kill loses at most a second. */
    private val tick = object : Runnable {
        override fun run() {
            synchronized(this@DriveRecorder) {
                val w = writer ?: return
                runCatching { w.flush() }.onFailure { Log.w(TAG, "drive flush failed: $it") }
                eventLog.handler.postDelayed(this, FLUSH_MS)
            }
        }
    }

    init {
        App.appScope.launch {
            settings.settings.map { it.driveRecordingOn }.distinctUntilChanged().collect { onSetting(it) }
        }
    }

    /** True while a file is open. */
    val isRecording: Boolean get() = synchronized(this) { writer != null }

    /** A session wants recording; a file opens if the setting is on (now or later). */
    @Synchronized
    fun start(sessionTag: String) {
        wanted = true
        this.sessionTag = sessionTag
        openLater()
    }

    /** The session ended: close the file. */
    @Synchronized
    fun stop() {
        wanted = false
        close("stop")
    }

    /** Appends one row (a fix or a car speed report, §15.5). Cheap when not recording. */
    fun record(row: DriveLog.RecordedFix) {
        synchronized(this) {
            val w = writer ?: return
            val line = DriveLog.format(row)
            try {
                w.write(line)
                w.newLine()
            } catch (e: Exception) {
                Log.w(TAG, "drive write failed: $e")
                close("writeFailed")
                return
            }
            bytes += line.length + 1
            if (bytes > MAX_BYTES) {
                eventLog.record(EventLog.Channel.NAV, "Drive", "recording stopped at the 50 MB cap file=${current?.name}")
                close("cap")
            }
        }
    }

    /** Writes buffered rows to disk now (before an export). */
    @Synchronized fun flush() { runCatching { writer?.flush() } }

    /** All recordings, newest first. */
    fun files(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** The newest recording (possibly the one being written), or null. */
    fun latest(): File? = files().firstOrNull()

    @Synchronized
    private fun onSetting(on: Boolean) {
        enabled = on
        if (on) openLater() else close("settingOff")
    }

    /** Opens the file on the diag thread, never the caller's (main) thread. */
    private fun openLater() {
        eventLog.handler.post { synchronized(this) { if (enabled && wanted && writer == null) open() } }
    }

    /**
     * Diag thread, under the lock: makes `drive-YYYYMMDD-HHMM.csv` (a `-2`, `-3` suffix if a file
     * of that minute exists), writes the header, deletes all but the newest [KEEP] recordings,
     * logs the start and arms the flush tick.
     */
    private fun open() {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        var f = File(dir, "$PREFIX$stamp$SUFFIX")
        var n = 2
        while (f.exists()) f = File(dir, "$PREFIX$stamp-${n++}$SUFFIX")
        try {
            val w = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8), 16 * 1024)
            w.write(DriveLog.HEADER)
            w.newLine()
            writer = w
            current = f
            bytes = DriveLog.HEADER.length + 1L
        } catch (e: Exception) {
            Log.w(TAG, "drive recording couldn't open ${f.name}: $e")
            eventLog.record(EventLog.Channel.NAV, "Drive", "recording failed to open file=${f.name} error=$e")
            return
        }
        // Newest KEEP files stay, this one included.
        files().drop(KEEP).forEach { it.delete() }
        eventLog.record(EventLog.Channel.NAV, "Drive", "recording start file=${f.name} session=$sessionTag")
        eventLog.handler.postDelayed(tick, FLUSH_MS)
    }

    /** Flushes, closes and logs the stop with its [reason] and byte count. Under the lock. */
    private fun close(reason: String) {
        val w = writer ?: return
        eventLog.handler.removeCallbacks(tick)
        runCatching { w.flush(); w.close() }.onFailure { Log.w(TAG, "drive close failed: $it") }
        eventLog.record(EventLog.Channel.NAV, "Drive", "recording stop reason=$reason file=${current?.name} bytes=$bytes")
        writer = null
        current = null
        bytes = 0
    }

    companion object {
        /** Recordings kept, newest first (DESIGN.md §15.5). */
        const val KEEP = 3
        /** The size cap: recording stops and says so in debug-nav (DESIGN.md §15.5). */
        const val MAX_BYTES = 50L * 1024 * 1024
        const val PREFIX = "drive-"
        const val SUFFIX = ".csv"
        /** The buffered writer is flushed this often on the diag thread (DESIGN.md §15.5). */
        private const val FLUSH_MS = 1_000L
        private const val TAG = "AutoNav"
    }
}

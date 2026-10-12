// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.diag

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.App
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
 * Callers: `start(sessionTag)` when a car session begins, `stop()` when it ends, `record(row)` for
 * each `Location` reaching the location source (phone and car, `used=false` for the ones dropped
 * while the other feed is fresh) and each car speed report. Any thread.
 */
@Singleton
class DriveRecorder @Inject constructor(
    @ApplicationContext context: Context,
    private val eventLog: EventLog,
    settings: SettingsStore,
) {
    /** One CSV row; blank cells for nulls. Column order is [HEADER]. */
    data class Row(
        val tMs: Long,
        /** What this row is: `phone`, `car` (a car fix) or `carSpeed` (a car speed report). */
        val kind: String,
        /** Which feed it came from: `phone` or `car`. */
        val src: String,
        /** Whether the app used it (false for a phone fix dropped while car GPS was fresh). */
        val used: Boolean,
        val lat: Double? = null,
        val lon: Double? = null,
        val accM: Float? = null,
        val spdMps: Float? = null,
        val brgDeg: Float? = null,
        val hasBrg: Boolean? = null,
        val altM: Double? = null,
        /** The fix's own timestamp (Location.time), as opposed to [tMs] when the app saw it. */
        val fixTMs: Long? = null,
        /** What the engine used after the speed/heading rules. */
        val engSpdMps: Double? = null,
        val engHdgDeg: Double? = null,
        val hdgSrc: String? = null,
        val carRawMps: Double? = null,
        val carDispMps: Double? = null,
    ) {
        fun toCsv(): String = listOf(
            tMs, kind, src, if (used) 1 else 0, lat, lon, accM, spdMps, brgDeg,
            hasBrg?.let { if (it) 1 else 0 }, altM, fixTMs, engSpdMps, engHdgDeg, hdgSrc, carRawMps, carDispMps,
        ).joinToString(",") { it?.toString() ?: "" }
    }

    private val dir = File(context.filesDir, "diag")

    @Volatile private var enabled = false
    private var wanted = false
    private var sessionTag = ""
    private var writer: BufferedWriter? = null
    private var current: File? = null
    private var bytes = 0L

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

    /** The file being written now, or null. */
    val currentFile: File? get() = synchronized(this) { current }

    /** A session wants recording; a file opens if the setting is on (now or later). */
    @Synchronized
    fun start(sessionTag: String) {
        wanted = true
        this.sessionTag = sessionTag
        if (enabled && writer == null) open()
    }

    /** The session ended: close the file. */
    @Synchronized
    fun stop() {
        wanted = false
        close("stop")
    }

    /** Appends one row. Cheap when not recording. */
    fun record(row: Row) {
        synchronized(this) {
            val w = writer ?: return
            val line = row.toCsv()
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
    @Synchronized
    fun flush() {
        runCatching { writer?.flush() }
    }

    /** All recordings, newest first. */
    fun files(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** The newest recording (possibly the one being written), or null. */
    fun latest(): File? = files().firstOrNull()

    @Synchronized
    private fun onSetting(on: Boolean) {
        enabled = on
        if (on) { if (wanted && writer == null) open() } else close("settingOff")
    }

    private fun open() {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        var f = File(dir, "$PREFIX$stamp$SUFFIX")
        var n = 2
        while (f.exists()) f = File(dir, "$PREFIX$stamp-${n++}$SUFFIX")
        try {
            val w = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8), 16 * 1024)
            w.write(HEADER)
            w.newLine()
            writer = w
            current = f
            bytes = HEADER.length + 1L
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
        /** The column order, exactly as DESIGN.md §15.5 lists it; `DriveLog` in core parses the same. */
        const val HEADER = "t_ms,kind,src,used,lat,lon,acc_m,spd_mps,brg_deg,has_brg,alt_m,fix_t_ms,eng_spd_mps,eng_hdg_deg,hdg_src,car_raw_mps,car_disp_mps"
        const val KEEP = 3
        const val MAX_BYTES = 50L * 1024 * 1024
        const val PREFIX = "drive-"
        const val SUFFIX = ".csv"
        private const val FLUSH_MS = 1_000L
        private const val TAG = "AutoNav"
    }
}

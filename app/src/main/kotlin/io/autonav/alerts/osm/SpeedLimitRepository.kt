// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.osm

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.App
import io.autonav.alerts.core.geo.LatLngLike
import io.autonav.alerts.core.osm.LimitIndex
import io.autonav.alerts.core.osm.LimitLookahead
import io.autonav.alerts.core.osm.OsmLimitFile
import io.autonav.alerts.core.osm.OsmSpeedLimits
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

/**
 * OpenStreetMap speed limits for the UK, from one bulk download of Geofabrik's extracts (DESIGN.md §8.4a).
 * The owner's rule (§8.4a, AGENTS.md §3.2): one bulk download, never live lookups. Nothing is looked up online
 * while driving: lookups read `files/osm/osm_limits.bin` on the phone, a few tiles at a time. A Hilt singleton.
 * Callers: `NavSession` once a second for the panel's limit ([limitAt]) and the next change ahead ([nextChange]);
 * `AlertCoordinator` per reload for the road ahead in free drive ([roadAhead], §11 step 4); the phone's
 * Settings → Speed limits card ([download], [cancel], [stats], [delete]); `SpeedLimitWorker` ([replaced], [report]);
 * the debug `BenchReceiver` ([reader]). The lookups never block: they read the index already in memory and
 * start a background load on `App.appScope` when the car nears a tile not loaded.
 * OSM limits only ever feed the panel's grey-dot limit and the limit-change announcement, never an alert (§8.4a "Shown on").
 */
@Singleton
class SpeedLimitRepository @Inject constructor(@ApplicationContext private val context: Context) {
    /** Download progress: which region and step, and compressed bytes done of the total. [finished] with no [error] is success. */
    data class Progress(val region: String, val step: String, val done: Long, val total: Long, val finished: Boolean, val error: String? = null)
    /** What the Settings card shows (§8.4a): the Geofabrik data date, road and tile counts, the file size in bytes. */
    data class Stats(val dataDate: String, val roads: Long, val tiles: Int, val bytes: Long)

    private val dir get() = File(context.filesDir, "osm").apply { mkdirs() }
    /** `files/osm/osm_limits.bin` (§8.4a "Result"): the `OsmLimitFile` the worker writes and the lookups read. */
    val file: File get() = File(dir, "osm_limits.bin")

    private val _progress = MutableStateFlow<Progress?>(null)
    /** Live download progress for the Settings card; null when no download is running or shown. */
    val progress: StateFlow<Progress?> = _progress
    /** The worker's progress updates. */
    internal fun report(p: Progress?) { _progress.value = p }

    @Volatile private var reader: OsmLimitFile.Reader? = null
    @Volatile private var index: LimitIndex = LimitIndex.EMPTY
    /** The lookahead over the current [index]; rebuilt when the index is replaced. */
    @Volatile private var lookahead: Pair<LimitIndex, LimitLookahead>? = null
    private var loading: Job? = null
    /** Set when there is no file, so lookups don't check the disk on every fix; cleared by [replaced]. */
    @Volatile private var noFile = false
    /** Bumped by [replaced]: a load that started before it doesn't put back the old file's tiles. */
    @Volatile private var generation = 0

    /**
     * The OSM limit (mph) of the road at this fix, or null. Never waits and allocates nothing in
     * the usual case: it uses the small index in memory, and reads the tiles within [NEAR_M] of
     * the car (usually one) from disk in the background when the car comes near a new one.
     */
    fun limitAt(lat: Double, lon: Double, heading: Double?): Double? {
        val idx = index
        ensureLoaded(idx, lat, lon)
        return idx.limitAt(lat, lon, heading)
    }

    /**
     * The road ahead of the car from the OSM pieces, up to [maxM] metres, for free drive's camera matching
     * (DESIGN.md §11 step 4); null without data under the car. Follows joins like [nextChange] but carries on
     * past forks by the straightest branch (§8.4a "The free-drive road ahead for cameras").
     */
    fun roadAhead(lat: Double, lon: Double, headingDeg: Double, maxM: Double): List<LatLngLike>? {
        val idx = index
        ensureLoaded(idx, lat, lon)
        if (idx === LimitIndex.EMPTY) return null
        val la = lookahead?.takeIf { it.first === idx }?.second ?: LimitLookahead(idx).also { lookahead = idx to it }
        return runCatching { la.roadAhead(lat, lon, headingDeg, maxM) }
            .onFailure { Log.w(TAG, "roadAhead: ${it.message}") }.getOrNull()
    }

    /**
     * The next change of limit on the road the car is on (DESIGN.md §8.4a, "Limit changes ahead"),
     * from the tiles already in memory: along [routeAhead] when there is one (first point at the
     * car, at least two points), else along the road by connectivity, stopping at the first fork.
     * Null with no data, no heading or no change within 600 m. Called once a second by `NavSession`, never per fix.
     */
    fun nextChange(lat: Double, lon: Double, headingDeg: Double, routeAhead: List<LatLngLike>?): LimitLookahead.Change? {
        val idx = index
        ensureLoaded(idx, lat, lon)
        if (idx === LimitIndex.EMPTY) return null
        val la = lookahead?.takeIf { it.first === idx }?.second ?: LimitLookahead(idx).also { lookahead = idx to it }
        return runCatching { la.nextChange(lat, lon, headingDeg, routeAhead?.takeIf { it.size >= 2 }) }
            .onFailure { Log.w(TAG, "lookahead: ${it.message}") }.getOrNull()
    }

    /** Reads the tiles around the car in the background when they aren't in memory yet; one load at a time, none when there is no file. */
    private fun ensureLoaded(idx: LimitIndex, lat: Double, lon: Double) {
        if (noFile || covers(idx, lat, lon) || loading?.isActive == true) return
        loading = App.appScope.launch { load(lat, lon) }
    }

    /** True when the tiles at all four points [NEAR_M] from the car are in [idx] (§8.4a: reload when within 60 m of a tile not in memory). */
    private fun covers(idx: LimitIndex, lat: Double, lon: Double): Boolean {
        val dLat = NEAR_M / 111_000.0
        val dLon = NEAR_M / (111_000.0 * cos(Math.toRadians(lat)))
        for (la in doubleArrayOf(lat - dLat, lat + dLat)) for (lo in doubleArrayOf(lon - dLon, lon + dLon)) {
            if (OsmSpeedLimits.tileKey(la, lo) !in idx.tiles) return false
        }
        return true
    }

    /** Builds a fresh index of the tiles within [LOAD_M] of the car (§8.4a: usually one, up to four at a corner) and swaps it in, unless the file was replaced meanwhile. */
    private fun load(lat: Double, lon: Double) {
        val gen = generation
        val r = openReader() ?: run { index = LimitIndex.EMPTY; return }
        // The tiles within a few hundred metres: one in open country, up to four at a corner.
        val d = LOAD_M / 111_000.0
        val dl = LOAD_M / (111_000.0 * cos(Math.toRadians(lat)))
        val tiles = linkedSetOf<Long>()
        for (la in doubleArrayOf(lat - d, lat + d)) for (lo in doubleArrayOf(lon - dl, lon + dl)) tiles += OsmSpeedLimits.tileKey(la, lo)
        val built = runCatching { LimitIndex.build(r, tiles) }
            .onFailure { Log.w(TAG, "Reading limits: ${it.message}") }.getOrDefault(LimitIndex.EMPTY)
        synchronized(this) { if (gen == generation) index = built }
    }

    /** The file's reader, opened once and kept; remembers "no file" so lookups don't stat the disk per fix. */
    @Synchronized
    private fun openReader(): OsmLimitFile.Reader? {
        reader?.let { return it }
        if (!file.exists()) { noFile = true; return null }
        return runCatching { OsmLimitFile.Reader(file) }.onFailure { Log.w(TAG, "Bad limits file: ${it.message}") }.getOrNull()
            ?.also { reader = it }
    }

    /**
     * Called by the download before and after it renames the new file over the old one, and by [delete]: closes
     * the reader, forgets "no file", empties the index and bumps the generation so a load in flight can't put
     * the old file's tiles back. The next lookup reopens the file and reloads around the car.
     */
    @Synchronized
    internal fun replaced() {
        generation++
        runCatching { reader?.close() }
        reader = null
        noFile = false
        index = LimitIndex.EMPTY
    }

    // --- Download (runs in SpeedLimitWorker) -------------------------------------------------

    /**
     * Starts (or restarts) the UK download as a WorkManager job (§8.4a "When"): Wi-Fi only (UNMETERED) by default,
     * or any network when the user turned Wi-Fi only off. REPLACE so pressing Update restarts a stalled job.
     * The old file keeps working until the new one is complete.
     */
    fun download(wifiOnly: Boolean) {
        _progress.value = Progress("", if (wifiOnly) "Waiting for Wi-Fi" else "Waiting for a connection", 0, 0, false)
        val req = OneTimeWorkRequestBuilder<SpeedLimitWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    /** The Cancel button and notification action (§8.4a: stops within a fraction of a second; the old file is kept). */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        _progress.value = null
    }

    /** The open file, for checks (debug bench). */
    fun reader(): OsmLimitFile.Reader? = openReader()

    /** The Settings card's status line, or null with no file. */
    fun stats(): Stats? = openReader()?.let { Stats(it.dataDate, it.roads, it.tiles, file.length()) }

    /** Settings → Delete: stops any download, drops the index and removes the file. */
    fun delete() {
        cancel()
        replaced()
        file.delete()
    }

    companion object {
        private const val TAG = "OsmLimits"
        /** Unique WorkManager name: one download at a time. */
        const val WORK = "osm-speed-limits"

        /** Tiles are reloaded when the car comes within this of one not in memory. */
        private const val NEAR_M = 60.0
        /** …and those within this are read, so the car isn't reloading at every tile edge. */
        private const val LOAD_M = 400.0
    }
}

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
 * OpenStreetMap speed limits for the UK, from one bulk download of Geofabrik's extracts
 * (DESIGN.md §8.4a). Nothing is looked up online while driving: lookups read the file on the phone.
 */
@Singleton
class SpeedLimitRepository @Inject constructor(@ApplicationContext private val context: Context) {
    /** Download progress: which region and step, and compressed bytes done of the total. */
    data class Progress(val region: String, val step: String, val done: Long, val total: Long, val finished: Boolean, val error: String? = null)
    data class Stats(val dataDate: String, val roads: Long, val tiles: Int, val bytes: Long)

    private val dir get() = File(context.filesDir, "osm").apply { mkdirs() }
    val file: File get() = File(dir, "osm_limits.bin")

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    internal fun report(p: Progress?) { _progress.value = p }

    @Volatile private var reader: OsmLimitFile.Reader? = null
    @Volatile private var index: LimitIndex = LimitIndex.EMPTY
    /** The lookahead over the current [index]; rebuilt when the index is replaced. */
    @Volatile private var lookahead: Pair<LimitIndex, LimitLookahead>? = null
    private var loading: Job? = null

    /**
     * The OSM limit (mph) of the road at this fix, or null. Never waits and allocates nothing in
     * the usual case: it uses the small index in memory, and reads the tiles within [NEAR_M] of
     * the car (usually one) from disk in the background when the car comes near a new one.
     */
    fun limitAt(lat: Double, lon: Double, heading: Double?): Double? {
        val idx = index
        if (!covers(idx, lat, lon) && loading?.isActive != true) {
            loading = App.appScope.launch { load(lat, lon) }
        }
        return idx.limitAt(lat, lon, heading)
    }

    /**
     * The next change of limit on the road the car is on (DESIGN.md §8.4a, "Limit changes ahead"),
     * from the tiles already in memory: along [routeAhead] when there is one (first point at the
     * car, at least two points), else along the road by connectivity. Null with no data, no
     * heading or no change within 600 m. Called once a second by `NavSession`, never per fix.
     */
    fun nextChange(lat: Double, lon: Double, headingDeg: Double, routeAhead: List<LatLngLike>?): LimitLookahead.Change? {
        val idx = index
        if (idx === LimitIndex.EMPTY) return null
        if (!covers(idx, lat, lon) && loading?.isActive != true) loading = App.appScope.launch { load(lat, lon) }
        val la = lookahead?.takeIf { it.first === idx }?.second ?: LimitLookahead(idx).also { lookahead = idx to it }
        return runCatching { la.nextChange(lat, lon, headingDeg, routeAhead?.takeIf { it.size >= 2 }) }
            .onFailure { Log.w(TAG, "lookahead: ${it.message}") }.getOrNull()
    }

    private fun covers(idx: LimitIndex, lat: Double, lon: Double): Boolean {
        val dLat = NEAR_M / 111_000.0
        val dLon = NEAR_M / (111_000.0 * cos(Math.toRadians(lat)))
        for (la in doubleArrayOf(lat - dLat, lat + dLat)) for (lo in doubleArrayOf(lon - dLon, lon + dLon)) {
            if (OsmSpeedLimits.tileKey(la, lo) !in idx.tiles) return false
        }
        return true
    }

    private fun load(lat: Double, lon: Double) {
        val r = openReader() ?: run { index = LimitIndex.EMPTY; return }
        // The tiles within a few hundred metres: one in open country, up to four at a corner.
        val d = LOAD_M / 111_000.0
        val dl = LOAD_M / (111_000.0 * cos(Math.toRadians(lat)))
        val tiles = linkedSetOf<Long>()
        for (la in doubleArrayOf(lat - d, lat + d)) for (lo in doubleArrayOf(lon - dl, lon + dl)) tiles += OsmSpeedLimits.tileKey(la, lo)
        index = runCatching { LimitIndex.build(r, tiles) }
            .onFailure { Log.w(TAG, "Reading limits: ${it.message}") }.getOrDefault(LimitIndex.EMPTY)
    }

    @Synchronized
    private fun openReader(): OsmLimitFile.Reader? {
        reader?.let { return it }
        if (!file.exists()) return null
        return runCatching { OsmLimitFile.Reader(file) }.onFailure { Log.w(TAG, "Bad limits file: ${it.message}") }.getOrNull()
            ?.also { reader = it }
    }

    /** Called by the download when a new file is in place. */
    @Synchronized
    internal fun replaced() {
        runCatching { reader?.close() }
        reader = null
        index = LimitIndex.EMPTY
    }

    // --- Download (runs in SpeedLimitWorker) -------------------------------------------------

    /** Starts (or restarts) the UK download in the background: on Wi-Fi only, or any network. */
    fun download(wifiOnly: Boolean) {
        _progress.value = Progress("", if (wifiOnly) "Waiting for Wi-Fi" else "Waiting for a connection", 0, 0, false)
        val req = OneTimeWorkRequestBuilder<SpeedLimitWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        _progress.value = null
    }

    /** The open file, for checks (debug bench). */
    fun reader(): OsmLimitFile.Reader? = openReader()

    fun stats(): Stats? = openReader()?.let { Stats(it.dataDate, it.roads, it.tiles, file.length()) }

    fun delete() {
        cancel()
        replaced()
        file.delete()
    }

    companion object {
        private const val TAG = "OsmLimits"
        const val WORK = "osm-speed-limits"

        /** Tiles are reloaded when the car comes within this of one not in memory. */
        private const val NEAR_M = 60.0
        /** …and those within this are read, so the car isn't reloading at every tile edge. */
        private const val LOAD_M = 400.0
    }
}

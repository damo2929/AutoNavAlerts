// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.osm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.autonav.alerts.R
import io.autonav.alerts.core.osm.NorthernIreland
import io.autonav.alerts.core.osm.OsmLimitFile
import io.autonav.alerts.core.osm.ShapefileRoads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Downloads the UK's speed limits from Geofabrik (DESIGN.md §8.4a): for England, Scotland, Wales
 * and Northern Ireland (from the Ireland file, kept to its border), only the roads layer is fetched out of each zip (range requests) and streamed into
 * one compact file. The old file is replaced only when the new one is complete.
 */
@HiltWorker
class SpeedLimitWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repo: SpeedLimitRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Android doesn't let a background app start a foreground service (e.g. when the job
        // starts later, once Wi-Fi is back); it then runs as ordinary background work, and if the
        // system stops it, WorkManager runs it again.
        try { setForeground(foreground("Starting…", 0, 0)) } catch (e: IllegalStateException) {
            Log.i(TAG, "Running in the background without a foreground service: ${e.message}")
        }
        val writer = OsmLimitFile.Writer(File(repo.file.parentFile, "osm_limits.spill"))
        val limitsTmp = File(repo.file.parentFile, "osm_limits.road_limits")
        var dataDate = ""
        try {
            // Find every entry first, so progress can show a total.
            val zips = REGIONS.map { r -> r to RemoteZip.open(r.url) }
            val total = zips.sumOf { (_, z) -> (z.entry(DBF)?.compressed ?: 0) + (z.entry(SHP)?.compressed ?: 0) }
            var before = 0L
            for ((region, zip) in zips) {
                val name = region.name
                dataDate = maxOf(dataDate, dateOf(zip.url))
                val dbf = zip.entry(DBF) ?: error("$name: no roads layer in the download")
                val shp = zip.entry(SHP) ?: error("$name: no roads layer in the download")
                // One byte per road, kept on disk and read back alongside the shapes.
                zip.open(dbf) { show(name, "road details", before + it, total) }.use { d ->
                    limitsTmp.outputStream().buffered(1 shl 16).use { ShapefileRoads.readLimits(d, it) }
                }
                before += dbf.compressed
                zip.open(shp) { show(name, "roads", before + it, total) }.use { s ->
                    limitsTmp.inputStream().use { limits -> ShapefileRoads.readRoads(s, limits) { mph, xy ->
                        if (region.keeps(mph, xy)) writer.add(mph, ShapefileRoads.simplify(xy, SIMPLIFY_M))
                    } }
                }
                before += shp.compressed
                coroutineContext.ensureActive()
                Log.i(TAG, "$name done: ${writer.roads} roads so far")
            }
            val tmp = File(repo.file.parentFile, "osm_limits.tmp")
            show("", "Writing the file", total, total)
            writer.write(tmp, dataDate)
            repo.replaced()
            if (!tmp.renameTo(repo.file)) { tmp.copyTo(repo.file, overwrite = true); tmp.delete() }
            repo.replaced()
            repo.report(SpeedLimitRepository.Progress("", "Done", total, total, true))
            Log.i(TAG, "Speed limits ready: ${writer.roads} roads, ${repo.file.length() / 1_000_000} MB, data from $dataDate")
            Result.success()
        } catch (e: Exception) {
            writer.close()
            if (e is kotlinx.coroutines.CancellationException || isStopped) {
                Log.i(TAG, "Speed limits download cancelled; the old file is unchanged")
                repo.report(null)
                throw e
            }
            Log.w(TAG, "Speed limits download failed", e)
            repo.report(SpeedLimitRepository.Progress("", "Failed", 0, 0, true, e.message ?: e.javaClass.simpleName))
            Result.failure()
        } finally {
            limitsTmp.delete()
        }
    }

    private var lastShown = 0L

    private fun show(region: String, step: String, done: Long, total: Long) {
        // The reads block, so coroutine cancellation alone wouldn't stop them: stop here on Cancel.
        if (isStopped) throw kotlinx.coroutines.CancellationException("Speed limit download cancelled")
        repo.report(SpeedLimitRepository.Progress(region, step, done, total, false))
        val now = System.currentTimeMillis()
        if (now - lastShown < 2_000) return
        lastShown = now
        val text = "$region: $step · ${done / 1_000_000} of ${total / 1_000_000} MB"
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text, done, total))
    }

    private fun foreground(text: String, done: Long, total: Long): ForegroundInfo {
        val n = notification(text, done, total)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    private fun notification(text: String, done: Long, total: Long): android.app.Notification {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Speed limit download", NotificationManager.IMPORTANCE_LOW))
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_poi_camera_fixed)
            .setContentTitle("Downloading speed limits (OpenStreetMap)")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(1000, if (total > 0) (done * 1000 / total).toInt() else 0, total == 0L)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    companion object {
        private const val TAG = "OsmLimits"
        private const val CHANNEL = "osm_limits"
        private const val NOTIFICATION_ID = 30_200
        private const val DBF = "gis_osm_roads_free_1.dbf"
        private const val SHP = "gis_osm_roads_free_1.shp"
        private const val SIMPLIFY_M = 3.0
        private const val BASE = "https://download.geofabrik.de/europe/united-kingdom"

        /** One Geofabrik extract, and which of its roads to keep. */
        class Region(val name: String, val url: String, val keeps: (mph: Int, xy: DoubleArray) -> Boolean = { _, _ -> true })

        /**
         * The UK: Geofabrik has no whole-UK shapefile and none for Northern Ireland alone, so
         * Northern Ireland comes from the Ireland file, kept to its border.
         */
        val REGIONS = listOf(
            Region("Wales", "$BASE/wales-latest-free.shp.zip"),
            Region("Scotland", "$BASE/scotland-latest-free.shp.zip"),
            Region("Northern Ireland", "https://download.geofabrik.de/europe/ireland-and-northern-ireland-latest-free.shp.zip", NorthernIreland::keeps),
            Region("England", "$BASE/england-latest-free.shp.zip"),
        )

        /** "…/england-261011-free.shp.zip" → "2026-10-11". */
        fun dateOf(url: String): String =
            Regex("""-(\d{2})(\d{2})(\d{2})-free""").find(url)?.groupValues?.let { "20${it[1]}-${it[2]}-${it[3]}" } ?: ""
    }
}

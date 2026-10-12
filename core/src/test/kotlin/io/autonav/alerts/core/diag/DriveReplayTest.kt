// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.diag

import io.autonav.alerts.core.alerts.AlertEngine
import io.autonav.alerts.core.alerts.AlertPoi
import io.autonav.alerts.core.alerts.AlertStage
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.location.FixEstimator
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ZoneRole
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays a real drive recording through [FixEstimator] and [AlertEngine] and prints the stage
 * timeline (DESIGN.md §15.5). Skipped unless both files are given; never commit them:
 *
 *   DRIVE_CSV=drive-20261012-1804.csv DRIVE_POIS=pois.csv ./gradlew :core:test --tests '*DriveReplayTest*'
 *
 * `DRIVE_POIS` is a CSV with a header row and the columns `id,lat,lon,direction,bearing,limit_mph`
 * (direction ANY, ONE_WAY or BOTH; bearing in degrees and the limit may be empty). Each POI gets
 * the default TIME stages (15 s / 7 s) with sound on.
 */
class DriveReplayTest {
    @Test
    fun replayIfProvided() {
        val csv = System.getenv("DRIVE_CSV")
        val poisPath = System.getenv("DRIVE_POIS")
        assumeTrue("DRIVE_CSV and DRIVE_POIS not set", !csv.isNullOrBlank() && !poisPath.isNullOrBlank())
        val pois = File(poisPath!!).readLines().mapNotNull(::poi)
        val est = FixEstimator()
        val engine = AlertEngine()
        var fixes = 0
        var t0 = -1L
        var headingCompared = 0
        var headingOff = 0
        val stages = ArrayList<String>()
        File(csv!!).bufferedReader().use { r ->
            for (rec in DriveLog.parse(r)) {
                if (rec.kind != DriveLog.KIND_FIX || !rec.used || rec.lat == null || rec.lon == null) continue
                fixes++
                val e = est.onFix(rec.toInput())
                if (t0 < 0) t0 = e.timeMs
                val t = (e.timeMs - t0) / 1000.0
                if (e.resetReason != null) println("t=+%.1fs TRACK_RESET %s".format(t, e.resetReason))
                val recHdg = rec.engHdgDeg
                if (recHdg != null && e.headingDeg != null) {
                    headingCompared++
                    if (Geo.absAngleDiffDeg(recHdg, e.headingDeg) > 10.0) headingOff++
                }
                val fix = Fix(lat = rec.lat, lon = rec.lon, speedMps = e.speedMps, heading = e.headingDeg, timeMs = e.timeMs)
                val near = pois.filter { Geo.distanceM(fix.lat, fix.lon, it.lat, it.lon) <= 3_000.0 }
                for (ev in engine.onFix(fix, near)) {
                    val line = "t=+%.1fs %s poi=%d d=%.0f spd=%.1f(%s) hdg=%s±%s(%s) recorded spd=%s hdg=%s %s".format(
                        t, ev.kind, ev.poiId, ev.distanceM, e.speedMps, e.speedSource,
                        e.headingDeg?.let { "%.0f".format(it) } ?: "-", e.headingAccuracyDeg?.let { "%.0f".format(it) } ?: "-", e.headingSource,
                        rec.engSpdMps?.let { "%.1f".format(it) } ?: "-", recHdg?.let { "%.0f".format(it) } ?: "-", rec.hdgSrc ?: "",
                    )
                    println(line)
                    stages += line
                }
            }
        }
        println("Replayed $fixes fixes over ${pois.size} POIs: ${stages.size} stage events; " +
            "estimator heading within 10° of the recorded one for ${headingCompared - headingOff} of $headingCompared fixes; " +
            "GPS bearing stale=${est.gpsBearingStale}")
        assertTrue("no used fixes in $csv", fixes > 0)
    }

    private fun poi(line: String): AlertPoi? {
        val f = line.split(',').map { it.trim() }
        if (f.size < 3 || f[0].equals("id", ignoreCase = true)) return null
        val id = f[0].toLongOrNull() ?: return null
        val lat = f[1].toDoubleOrNull() ?: return null
        val lon = f[2].toDoubleOrNull() ?: return null
        val dir = f.getOrNull(3)?.uppercase()?.let { d -> DirectionType.entries.firstOrNull { it.name == d } } ?: DirectionType.ANY
        val bearing = f.getOrNull(4)?.toFloatOrNull()
        val limit = f.getOrNull(5)?.toDoubleOrNull()?.let { it * Geo.MPS_PER_MPH }
        return AlertPoi(id, lat, lon, dir, bearing, limit, ZoneRole.NONE, AlertStage.DEFAULT_FIRST, AlertStage.DEFAULT_SECOND, soundOn = true)
    }
}

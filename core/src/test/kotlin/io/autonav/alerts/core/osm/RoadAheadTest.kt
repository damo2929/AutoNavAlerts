// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.alerts.AlertEngine
import io.autonav.alerts.core.alerts.AlertEvent
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.alerts.AlertPoi
import io.autonav.alerts.core.alerts.AlertStage
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ZoneRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos

/**
 * Free drive follows the road ahead (DESIGN.md §11 step 4, owner 2026-10-23): a camera on a road
 * running beside ours, facing our way, must not alert. Synthetic limit file; positions are metres
 * north/east of 53.05°N, 0.05°E, inside one 0.1° tile.
 */
class RoadAheadTest {
    private val baseLat = 53.05
    private val baseLon = 0.05
    private val metresPerDegLat = 111_195.0
    private fun latAt(northM: Double) = baseLat + northM / metresPerDegLat
    private fun lonAt(eastM: Double) = baseLon + eastM / (metresPerDegLat * cos(Math.toRadians(baseLat)))
    private fun eastOf(lon: Double) = (lon - baseLon) * metresPerDegLat * cos(Math.toRadians(baseLat))

    private fun index(vararg roads: List<Pair<Double, Double>>): LimitIndex {
        val dir = Files.createTempDirectory("roadahead").toFile()
        val file = File(dir, "limits.bin")
        OsmLimitFile.Writer(File(dir, "spill")).use { w ->
            for (r in roads) {
                val xy = DoubleArray(r.size * 2)
                r.forEachIndexed { i, (n, e) -> xy[2 * i] = lonAt(e); xy[2 * i + 1] = latAt(n) }
                w.add(30, xy)
            }
            w.write(file, "2026-10-23")
        }
        return OsmLimitFile.Reader(file).use { r -> LimitIndex.build(r, (0 until r.tiles).map { r.tileKeyAt(it) }) }
            .also { dir.deleteRecursively() }
    }

    /** Ours due north on east = 0, and a parallel road 120 m east, both 1.5 km in 100 m pieces. */
    private fun twoParallelRoads() = index(
        (0..15).map { it * 100.0 to 0.0 },
        (0..15).map { it * 100.0 to 120.0 },
    )

    private fun matcherAt(idx: LimitIndex, northM: Double, maxM: Double = 2_000.0) =
        LimitLookahead(idx).roadAhead(latAt(northM), lonAt(0.0), 0.0, maxM)?.let(::RoadAheadMatcher)

    @Test
    fun roadAheadStaysOnOurRoad() {
        val road = LimitLookahead(twoParallelRoads()).roadAhead(latAt(50.0), lonAt(0.0), 0.0, 2_000.0)
        assertNotNull(road)
        assertTrue("never jumps to the parallel road", road!!.all { abs(eastOf(it.lon)) < 1.0 })
        assertEquals(1_500.0, (road.last().lat - baseLat) * metresPerDegLat, 1.0)
    }

    @Test
    fun noRoadUnderTheCarGivesNoMatcher() {
        assertNull(LimitLookahead(twoParallelRoads()).roadAhead(latAt(50.0), lonAt(400.0), 0.0, 2_000.0))
    }

    @Test
    fun matcherMeasuresFromTheRoadAndSaysNothingPastIt() {
        val m = matcherAt(twoParallelRoads(), 50.0)!!
        assertEquals(0.0, m.distanceToRouteM(latAt(800.0), lonAt(0.0))!!, 1.0)
        assertEquals(120.0, m.distanceToRouteM(latAt(800.0), lonAt(120.0))!!, 1.0)
        // The road ends 1450 m from the car: beyond it there's no answer, so the corridor applies.
        assertNull(m.distanceToRouteM(latAt(1_800.0), lonAt(0.0)))
    }

    private fun mobile(northM: Double, eastM: Double) = AlertPoi(
        1, latAt(northM), lonAt(eastM), DirectionType.ONE_WAY, 0f, 30 * Geo.MPS_PER_MPH, ZoneRole.NONE,
        AlertStage(true, DistanceMode.FIXED, 800), AlertStage(true, DistanceMode.FIXED, 300), true,
        sideToleranceM = 100.0,
    )

    /** Drives north on our road at 30 mph from 0 to [toM], with or without the road-ahead matcher. */
    private fun drive(idx: LimitIndex?, poi: AlertPoi, toM: Double = 1_200.0): List<AlertEvent> {
        val engine = AlertEngine()
        val out = ArrayList<AlertEvent>()
        val mps = 30 * Geo.MPS_PER_MPH
        var pos = 0.0
        var t = 0L
        while (pos <= toM) {
            val matcher = idx?.let { matcherAt(it, pos) }
            out += engine.onFix(Fix(latAt(pos), lonAt(0.0), mps, 0.0, t), listOf(poi), matcher)
            pos += mps * 0.2; t += 200
        }
        return out
    }

    @Test
    fun mobileCameraOnAParallelRoadIsSilentInFreeDrive() {
        val idx = twoParallelRoads()
        val beside = mobile(northM = 1_000.0, eastM = 120.0)
        // The bug (bench, 2026-10-23): with the straight corridor alone it alerted.
        assertTrue(drive(null, beside).isNotEmpty())
        // Following the road ahead, 120 m off it is over the mobile 100 m tolerance: silent.
        assertTrue(drive(idx, beside).isEmpty())
    }

    @Test
    fun mobileCameraOnOurRoadStillGivesAllThreeStages() {
        val events = drive(twoParallelRoads(), mobile(northM = 1_000.0, eastM = 30.0))
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), events.map { it.kind })
    }
}

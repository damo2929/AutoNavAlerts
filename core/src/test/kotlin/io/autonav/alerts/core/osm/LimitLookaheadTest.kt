// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.geo.LatLngPoint
import io.autonav.alerts.core.osm.LimitLookahead.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin

/**
 * The next limit change on this road (DESIGN.md §8.4a "Limit changes ahead"), over a synthetic
 * limit file. Positions are metres north/east of 53.05°N, 0.05°E, all inside one 0.1° tile.
 */
class LimitLookaheadTest {
    private val baseLat = 53.05
    private val baseLon = 0.05
    private val metresPerDegLat = 111_195.0
    private fun latAt(northM: Double) = baseLat + northM / metresPerDegLat
    private fun lonAt(eastM: Double) = baseLon + eastM / (metresPerDegLat * cos(Math.toRadians(baseLat)))
    private fun pt(northM: Double, eastM: Double) = LatLngPoint(latAt(northM), lonAt(eastM))

    /** A road: its limit and its points as (north, east) metres. */
    private class Road(val mph: Int, val points: List<Pair<Double, Double>>)

    private fun road(mph: Int, vararg points: Pair<Double, Double>) = Road(mph, points.toList())

    private fun index(vararg roads: Road): LimitIndex {
        val dir = Files.createTempDirectory("limits").toFile()
        val file = File(dir, "limits.bin")
        OsmLimitFile.Writer(File(dir, "spill")).use { w ->
            for (r in roads) {
                val xy = DoubleArray(r.points.size * 2)
                r.points.forEachIndexed { i, (n, e) -> xy[2 * i] = lonAt(e); xy[2 * i + 1] = latAt(n) }
                w.add(r.mph, xy)
            }
            w.write(file, "2026-10-12")
        }
        return OsmLimitFile.Reader(file).use { r -> LimitIndex.build(r, (0 until r.tiles).map { r.tileKeyAt(it) }) }
            .also { dir.deleteRecursively() }
    }

    /** Due north on east = 0: 50 mph in 100 m pieces to 400 m, then 30 mph to 1000 m. */
    private fun straightRoad() = index(
        road(50, 0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 300.0 to 0.0, 400.0 to 0.0),
        road(30, 400.0 to 0.0, 600.0 to 0.0, 1_000.0 to 0.0),
    )

    private fun next(idx: LimitIndex, northM: Double, eastM: Double, heading: Double, route: List<LatLngPoint>? = null) =
        LimitLookahead(idx).nextChange(latAt(northM), lonAt(eastM), heading, route)

    @Test
    fun straightRoadFiftyToThirtyAtFourHundredMetres() {
        val idx = straightRoad()
        assertEquals(50.0, idx.limitAt(latAt(50.0), lonAt(0.0), 0.0)!!, 0.0)
        val c = next(idx, 50.0, 0.0, 0.0)
        assertNotNull(c)
        assertEquals(30, c!!.newMph)
        assertEquals(350.0, c.distanceM, 2.0)
        assertTrue(!c.viaRoute)
        // Later along the road the distance shrinks; past the change there is none.
        assertEquals(100.0, next(idx, 300.0, 0.0, 0.0)!!.distanceM, 2.0)
        assertNull(next(idx, 500.0, 0.0, 0.0))
        // Heading the other way, the road just ends.
        assertNull(next(idx, 50.0, 0.0, 180.0))
        // Off the road: nothing.
        assertNull(next(idx, 50.0, 80.0, 0.0))
    }

    @Test
    fun changeBeyondSixHundredMetresIsNotReported() {
        val idx = index(
            road(50, 0.0 to 0.0, 300.0 to 0.0, 700.0 to 0.0),
            road(30, 700.0 to 0.0, 1_000.0 to 0.0),
        )
        assertNull(next(idx, 50.0, 0.0, 0.0))
        assertEquals(550.0, next(idx, 150.0, 0.0, 0.0)!!.distanceM, 2.0)
    }

    @Test
    fun curveOfSixPiecesIsFollowed() {
        // An arc of 60° (six 10° pieces) on a 300 m radius, then a 30 road straight on along the tangent.
        val r = 300.0
        val arc = (0..6).map { k ->
            val a = Math.toRadians(10.0 * k)
            (r * sin(a)) to (r * (1 - cos(a)))
        }
        val endN = arc.last().first; val endE = arc.last().second
        val t = Math.toRadians(60.0)
        val idx = index(
            Road(50, arc),
            road(30, endN to endE, (endN + 200 * cos(t)) to (endE + 200 * sin(t))),
        )
        val c = next(idx, 0.0, 0.0, 0.0)
        assertNotNull(c)
        assertEquals(30, c!!.newMph)
        val chord = 2 * r * sin(Math.toRadians(5.0))
        assertEquals(6 * chord, c.distanceM, 3.0)
    }

    /** Main road north: 50 to 500 m, 40 beyond; a 30 side road east from the junction at 300 m. */
    private fun junction() = index(
        road(50, 0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 300.0 to 0.0, 400.0 to 0.0, 500.0 to 0.0),
        road(40, 500.0 to 0.0, 750.0 to 0.0, 1_000.0 to 0.0),
        road(30, 300.0 to 0.0, 300.0 to 300.0, 300.0 to 600.0),
    )

    @Test
    fun junctionReportsTheContinuationNotTheSideRoad() {
        val idx = junction()
        assertEquals(Change(40, 450.0, viaRoute = false), next(idx, 50.0, 0.0, 0.0)!!.let { it.copy(distanceM = Math.round(it.distanceM).toDouble()) })
        assertEquals(40, next(idx, 250.0, 0.0, 0.0)!!.newMph)
        // On the side road, just past the junction, heading east: a 30 road with no change ahead.
        assertNull(next(idx, 300.0, 50.0, 90.0))
        // connectedFrom at the junction picks the straight-on piece.
        val straightOn = idx.connectedFrom(latAt(300.0), lonAt(0.0), 0.0)!!
        assertEquals(50, idx.mphOf(straightOn))
        assertEquals(0.0, idx.bearingOf(straightOn), 1.0)
        val side = idx.connectedFrom(latAt(300.0), lonAt(0.0), 90.0)!!
        assertEquals(30, idx.mphOf(side))
    }

    @Test
    fun routeBasedLookaheadAcrossATurn() {
        val idx = junction()
        // Guidance turns right at the junction onto the 30 road.
        val route = listOf(pt(50.0, 0.0), pt(300.0, 0.0), pt(300.0, 600.0))
        val c = next(idx, 50.0, 0.0, 0.0, route)
        assertNotNull(c)
        assertEquals(30, c!!.newMph)
        assertTrue(c.viaRoute)
        assertTrue("d=${c.distanceM}", c.distanceM in 250.0..300.0)
        // Straight on along the route: the 40 at 450 m, as in free drive.
        val ahead = listOf(pt(50.0, 0.0), pt(1_000.0, 0.0))
        val straight = next(idx, 50.0, 0.0, 0.0, ahead)!!
        assertEquals(40, straight.newMph)
        assertTrue(straight.viaRoute)
        assertTrue("d=${straight.distanceM}", straight.distanceM in 450.0..475.0) // 25 m samples
        // No change within 600 m of route: null. A one-point route falls back to the road walk.
        assertNull(next(idx, 50.0, 0.0, 0.0, listOf(pt(50.0, 0.0), pt(400.0, 0.0))))
        assertEquals(40, next(idx, 50.0, 0.0, 0.0, listOf(pt(50.0, 0.0)))!!.newMph)
    }

    @Test
    fun pieceAccessorsAgreeWithTheFile() {
        val idx = straightRoad()
        val p = idx.pieceAt(latAt(150.0), lonAt(0.0), 0.0)!!
        assertEquals(50, idx.mphOf(p))
        assertEquals(100.0, idx.lengthOf(p), 1.0)
        assertEquals(latAt(100.0), idx.startOf(p).lat, 1e-5)
        assertEquals(latAt(200.0), idx.endOf(p, true).lat, 1e-5)
        assertEquals(latAt(100.0), idx.endOf(p, false).lat, 1e-5)
        assertNull(idx.pieceAt(latAt(150.0), lonAt(0.0), 90.0))
        assertNull(LimitIndex.EMPTY.pieceAt(latAt(150.0), lonAt(0.0), 0.0))
        assertNull(LimitIndex.EMPTY.connectedFrom(latAt(150.0), lonAt(0.0), 0.0))
    }
}

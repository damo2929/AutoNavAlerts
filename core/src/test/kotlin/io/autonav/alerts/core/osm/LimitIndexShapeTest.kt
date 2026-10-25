// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin

/**
 * The road index at its edges (DESIGN.md §8.4a "Finding the limit", "Same road without names"):
 * wide pieces that bypass the cell index, the 20 m / 30° match tolerances, the 40 m / 45° joins,
 * the 3–25° fork window and the "walked the same way round" rule, and the free-drive road walk
 * carrying on past a fork up to its cap. Synthetic roads; positions are metres north/east of
 * 53.05°N, 0.05°E inside one 0.1° tile unless said otherwise.
 */
class LimitIndexShapeTest {
    private val baseLat = 53.05
    private val baseLon = 0.05
    private val metresPerDegLat = 111_195.0
    private fun latAt(northM: Double) = baseLat + northM / metresPerDegLat
    private fun lonAt(eastM: Double) = baseLon + eastM / (metresPerDegLat * cos(Math.toRadians(baseLat)))

    private class Road(val mph: Int, val points: List<Pair<Double, Double>>)
    private fun road(mph: Int, vararg points: Pair<Double, Double>) = Road(mph, points.toList())

    /** Writes [roads] (north/east metres) to a limit file and indexes every tile of it. */
    private fun index(vararg roads: Road, raw: Boolean = false): LimitIndex {
        val dir = Files.createTempDirectory("shape").toFile()
        val file = File(dir, "limits.bin")
        OsmLimitFile.Writer(File(dir, "spill")).use { w ->
            for (r in roads) {
                val xy = DoubleArray(r.points.size * 2)
                r.points.forEachIndexed { i, (n, e) -> if (raw) { xy[2 * i] = e; xy[2 * i + 1] = n } else { xy[2 * i] = lonAt(e); xy[2 * i + 1] = latAt(n) } }
                w.add(r.mph, xy)
            }
            w.write(file, "2026-10-25")
        }
        return OsmLimitFile.Reader(file).use { r -> LimitIndex.build(r, (0 until r.tiles).map { r.tileKeyAt(it) }) }
            .also { dir.deleteRecursively() }
    }

    private fun branch(from: Pair<Double, Double>, deg: Double, lengthM: Double): Pair<Double, Double> =
        (from.first + lengthM * cos(Math.toRadians(deg))) to (from.second + lengthM * sin(Math.toRadians(deg)))

    /** 50 to 300 m north, then a 30 road straight on and a 40 branch leaving at [deg]. */
    private fun fork(deg: Double, branchMph: Int = 40) = index(
        road(50, 0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 300.0 to 0.0),
        road(30, 300.0 to 0.0, 500.0 to 0.0),
        road(branchMph, 300.0 to 0.0, branch(300.0 to 0.0, deg, 200.0)),
    )

    @Test
    fun aPieceSpanningTooManyCellsIsAlwaysChecked() {
        // A 55 km piece (one step of 0.5°, stored "wide" in the file) spans far more than 64 cells, so it
        // isn't indexed per cell; it is found from the middle, in a tile neither end is in.
        val idx = index(Road(70, listOf(53.0 to -1.0, 53.5 to -1.0)), raw = true)
        assertEquals(2, idx.pieces) // one copy per tile the ends are in
        assertEquals(2, idx.tiles.size)
        assertEquals(70.0, idx.limitAt(53.25, -1.0, 0.0)!!, 0.0)
        assertEquals(70.0, idx.limitAt(53.25, -1.0, 180.0)!!, 0.0)
        assertNull(idx.limitAt(53.25, -1.0, 90.0))
        val p = idx.pieceAt(53.25, -1.0, null)!!
        assertEquals(70, idx.mphOf(p))
        assertTrue(idx.lengthOf(p) in 55_000.0..56_000.0)
        assertEquals(0.0, idx.bearingOf(p), 0.01)
    }

    @Test
    fun matchIsWithin20mAnd30DegreesEitherWay() {
        val idx = index(road(50, 0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 300.0 to 0.0, 400.0 to 0.0))
        assertEquals(50.0, idx.limitAt(latAt(150.0), lonAt(19.0), 0.0)!!, 0.0)
        assertNull(idx.limitAt(latAt(150.0), lonAt(21.0), 0.0))
        assertEquals(50.0, idx.limitAt(latAt(150.0), lonAt(0.0), 29.0)!!, 0.0)
        assertNull(idx.limitAt(latAt(150.0), lonAt(0.0), 31.0))
        assertEquals(50.0, idx.limitAt(latAt(150.0), lonAt(0.0), 209.0)!!, 0.0) // 29° off the reverse
        assertNull(idx.limitAt(latAt(150.0), lonAt(0.0), 211.0))
        // Standing still: the nearest within 20 m, whatever way it runs.
        assertEquals(50.0, idx.limitAt(latAt(150.0), lonAt(19.0), null)!!, 0.0)
        assertNull(idx.limitAt(latAt(150.0), lonAt(21.0), null))
        assertEquals(0, LimitIndex.EMPTY.pieces)
        assertNull(LimitIndex.EMPTY.limitAt(latAt(150.0), lonAt(0.0), null))
    }

    @Test
    fun joinsWithin40mAnd45Degrees() {
        val near = index(road(50, 0.0 to 0.0, 200.0 to 0.0), road(50, 239.0 to 0.0, 400.0 to 0.0))
        assertNotNull(near.connectedFrom(latAt(200.0), lonAt(0.0), 0.0))
        val far = index(road(50, 0.0 to 0.0, 200.0 to 0.0), road(50, 241.0 to 0.0, 400.0 to 0.0))
        assertNull(far.connectedFrom(latAt(200.0), lonAt(0.0), 0.0))
        val bend44 = index(road(50, 0.0 to 0.0, 200.0 to 0.0), road(50, 200.0 to 0.0, branch(200.0 to 0.0, 44.0, 200.0)))
        assertNotNull(bend44.connectedFrom(latAt(200.0), lonAt(0.0), 0.0))
        val bend46 = index(road(50, 0.0 to 0.0, 200.0 to 0.0), road(50, 200.0 to 0.0, branch(200.0 to 0.0, 46.0, 200.0)))
        assertNull(bend46.connectedFrom(latAt(200.0), lonAt(0.0), 0.0))
        // The piece we came along is excluded, so a dead end doesn't join back onto itself.
        val self = near.pieceAt(latAt(150.0), lonAt(0.0), 0.0)!!
        assertNull(near.connectedFrom(latAt(200.0), lonAt(0.0), 180.0, exclude = self))
    }

    @Test
    fun forkIsAnotherBranchWithin3To25DegreesOfTheChosenOne() {
        fun forkAt(deg: Double): Boolean = fork(deg).joinFrom(latAt(300.0), lonAt(0.0), 0.0)!!.fork
        assertFalse(forkAt(2.5)) // the same line drawn twice
        assertTrue(forkAt(3.5))
        assertTrue(forkAt(24.5))
        assertFalse(forkAt(25.5)) // a side road
        assertFalse(forkAt(40.0))
        // The straightest branch is the one chosen whether or not it is a fork.
        val j = fork(10.0).joinFrom(latAt(300.0), lonAt(0.0), 0.0)!!
        assertEquals(30, fork(10.0).mphOf(j.piece))
        assertTrue(j.forward)
    }

    @Test
    fun limitChangeIsAnnouncedOnlyWhenNoForkPrecedesIt() {
        fun change(deg: Double) = LimitLookahead(fork(deg)).nextChange(latAt(50.0), lonAt(0.0), 0.0)
        assertNull(change(10.0))
        for (deg in listOf(2.5, 25.5, 40.0)) {
            val c = change(deg)
            assertNotNull("branch at $deg°", c)
            assertEquals(30, c!!.newMph)
            assertEquals(250.0, c.distanceM, 2.0)
            assertFalse(c.viaRoute)
            assertEquals(latAt(300.0), c.lat, 1e-6)
        }
        // A route tells the branch: the same fork is no obstacle along it.
        val alongRoute = LimitLookahead(fork(10.0)).nextChange(latAt(50.0), lonAt(0.0), 0.0, listOf(io.autonav.alerts.core.geo.LatLngPoint(latAt(50.0), lonAt(0.0)), io.autonav.alerts.core.geo.LatLngPoint(latAt(500.0), lonAt(0.0))))
        assertNotNull(alongRoute)
        assertEquals(30, alongRoute!!.newMph)
        assertTrue(alongRoute.viaRoute)
    }

    @Test
    fun aBranchDrawnTowardsTheNodeIsNotCountedAsAFork() {
        // §16 row 28, as built: the other branch is walked backwards (its end touches the node), so it is
        // not "the same way round" and the walk goes on as if there were no fork.
        val towards = index(
            road(50, 0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 300.0 to 0.0),
            road(30, 300.0 to 0.0, 500.0 to 0.0),
            road(40, branch(300.0 to 0.0, 10.0, 200.0), 300.0 to 0.0),
        )
        val j = towards.joinFrom(latAt(300.0), lonAt(0.0), 0.0)!!
        assertFalse(j.fork)
        assertEquals(30, towards.mphOf(j.piece))
        assertEquals(30, LimitLookahead(towards).nextChange(latAt(50.0), lonAt(0.0), 0.0)?.newMph)
        // Heading up that branch, it is the join, walked against its drawing direction.
        val up = towards.joinFrom(latAt(300.0), lonAt(0.0), 10.0)!!
        assertEquals(40, towards.mphOf(up.piece))
        assertFalse(up.forward)
        assertEquals(latAt(300.0), towards.endOf(up.piece, true).lat, 1e-6)
    }

    @Test
    fun roadAheadCarriesOnPastAForkByTheStraightestBranchUpToItsCap() {
        val idx = fork(10.0)
        val la = LimitLookahead(idx)
        val road = la.roadAhead(latAt(50.0), lonAt(0.0), 0.0, 2_000.0)!!
        assertEquals(5, road.size)
        assertTrue("stays on the straight branch", road.all { kotlin.math.abs(it.lon - lonAt(0.0)) < 1e-7 })
        assertEquals(latAt(500.0), road.last().lat, 1e-6)
        // The cap: the walk stops once the end reached is at least maxM from the car.
        val capped = la.roadAhead(latAt(50.0), lonAt(0.0), 0.0, 250.0)!!
        assertEquals(4, capped.size)
        assertEquals(latAt(300.0), capped.last().lat, 1e-6)
        assertEquals(latAt(50.0), capped.first().lat, 1e-9)
        // Walking the other way the road ends at the origin.
        val back = la.roadAhead(latAt(250.0), lonAt(0.0), 180.0, 2_000.0)!!
        assertEquals(latAt(0.0), back.last().lat, 1e-6)
        assertEquals(4, back.size)
    }

    @Test
    fun lookaheadRangeComesFromTheConfig() {
        val idx = index(
            road(50, 0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 300.0 to 0.0, 400.0 to 0.0),
            road(30, 400.0 to 0.0, 600.0 to 0.0),
        )
        assertEquals(30, LimitLookahead(idx).nextChange(latAt(50.0), lonAt(0.0), 0.0)?.newMph)
        assertNull(LimitLookahead(idx, LimitLookahead.Config(maxM = 300.0)).nextChange(latAt(50.0), lonAt(0.0), 0.0))
        assertEquals(30, LimitLookahead(idx, LimitLookahead.Config(maxM = 350.0)).nextChange(latAt(50.0), lonAt(0.0), 0.0)?.newMph)
        assertEquals(600.0, LimitLookahead.Config().maxM, 0.0)
        assertEquals(25.0, LimitLookahead.Config().sampleM, 0.0)
    }
}

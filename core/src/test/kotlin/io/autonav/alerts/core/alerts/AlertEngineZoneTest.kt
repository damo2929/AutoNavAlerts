// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.alerts.StraightRoad.camera
import io.autonav.alerts.core.alerts.StraightRoad.drive
import io.autonav.alerts.core.alerts.StraightRoad.fix
import io.autonav.alerts.core.alerts.StraightRoad.kinds
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.ZoneRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Average-speed zones (DESIGN.md §11 step 8) and which limit a zone judges by (step 9): a marked
 * start opens a zone on its own, a PGW point only with a same-limit point ahead (within 5 km, 60°
 * and facing our way), the limit carried through a variable point, the exits (last camera, the gap,
 * the 30 km / 30 min caps, a jump), the 15 s overspeed cadence, and the zone events' shape.
 */
class AlertEngineZoneTest {
    private val mps45 = 45.0 * Geo.MPS_PER_MPH

    private fun point(id: Long, northM: Double, limitMph: Int? = 50, eastM: Double = 0.0, bearing: Float? = 0f, sound: Boolean = true) =
        camera(id = id, northM = northM, eastM = eastM, bearing = bearing, zone = ZoneRole.ZONE_POINT, limitMph = limitMph, sound = sound)

    /** Drives at [mps] with 500 ms fixes, keeping each event with the fix's position and time. */
    private fun timed(engine: AlertEngine, pois: List<AlertPoi>, toM: Double, mps: Double = mps45): List<Triple<AlertEvent, Double, Long>> {
        val out = ArrayList<Triple<AlertEvent, Double, Long>>()
        drive(engine, pois, 0.0, toM, mps, stepMs = 500) { f, events ->
            val pos = (f.lat - StraightRoad.BASE_LAT) * StraightRoad.METRES_PER_DEG_LAT
            events.forEach { out += Triple(it, pos, f.timeMs) }
        }
        return out
    }

    private fun List<Triple<AlertEvent, Double, Long>>.of(kind: AlertKind) = filter { it.first.kind == kind }

    @Test
    fun markedStartOpensAloneAndMarkedEndCloses() {
        val start = camera(id = 10, northM = 1_000.0, zone = ZoneRole.ZONE_START, limitMph = 50)
        val end = camera(id = 11, northM = 3_000.0, zone = ZoneRole.ZONE_END, limitMph = 50)
        val engine = AlertEngine()
        val events = timed(engine, listOf(start, end), 3_200.0)
        val enter = events.of(AlertKind.ZONE_ENTER).single()
        val exit = events.of(AlertKind.ZONE_EXIT).single()
        assertEquals(10L, enter.first.poiId)
        assertEquals(11L, exit.first.poiId)
        assertTrue("entered at ${enter.second}", enter.second in 975.0..1_000.0)
        assertTrue("left at ${exit.second}", exit.second in 2_975.0..3_000.0)
        // Zone events carry no distance and the 60 priority; enter and exit are not "over".
        for (e in listOf(enter.first, exit.first)) { assertEquals(0.0, e.distanceM, 0.0); assertEquals(60, e.priority); assertFalse(e.overspeed) }
        assertFalse(engine.isInZone)
        // An end marker met outside a zone says nothing.
        assertTrue(timed(AlertEngine(), listOf(end), 3_200.0).of(AlertKind.ZONE_EXIT).isEmpty())
    }

    @Test
    fun startWithNothingAfterItEndsByTheFiveKilometreGap() {
        val start = camera(id = 10, northM = 1_000.0, zone = ZoneRole.ZONE_START, limitMph = 50)
        val events = timed(AlertEngine(), listOf(start), 6_300.0)
        val exit = events.of(AlertKind.ZONE_EXIT).single()
        assertEquals(10L, exit.first.poiId)
        assertTrue("left at ${exit.second}", exit.second in 6_000.0..6_020.0)
    }

    @Test
    fun aPointStartsAZoneOnlyWithASameLimitPointAheadFacingOurWay() {
        fun enters(vararg pois: AlertPoi): Boolean = timed(AlertEngine(), pois.toList(), 1_300.0).of(AlertKind.ZONE_ENTER).isNotEmpty()
        val a = point(10, 1_000.0)
        assertTrue(enters(a, point(11, 4_900.0)))
        // A point for the other direction, another limit, or beyond the gap doesn't count.
        assertFalse(enters(a, point(11, 4_900.0, bearing = 180f)))
        assertFalse(enters(a, point(11, 3_000.0, limitMph = 40)))
        assertFalse(enters(a, point(11, 6_100.0)))
        // A variable (unknown) limit matches anything.
        assertTrue(enters(a, point(11, 3_000.0, limitMph = null)))
        // "Ahead" for the chain is within 60°: a point 50° off still opens the zone, one at 70° doesn't.
        fun off(deg: Double) = point(11, 1_000.0 + 1_500.0 * cos(Math.toRadians(deg)), eastM = 1_500.0 * sin(Math.toRadians(deg)))
        assertTrue(enters(a, off(50.0)))
        assertFalse(enters(a, off(70.0)))
        // A lone point, or an end marker ahead, isn't a zone.
        assertFalse(enters(a))
        assertFalse(enters(a, camera(id = 11, northM = 3_000.0, zone = ZoneRole.ZONE_END, limitMph = 50)))
    }

    @Test
    fun limitCarriesThroughAVariablePointAndAlertsFollowTheLastCamera() {
        val pois = listOf(point(10, 1_000.0), point(11, 3_000.0, limitMph = null), point(12, 5_000.0))
        val engine = AlertEngine()
        val events = timed(engine, pois, 5_200.0, mps = 55.0 * Geo.MPS_PER_MPH)
        assertEquals(listOf(10L), events.of(AlertKind.ZONE_ENTER).map { it.first.poiId })
        assertEquals(listOf(12L), events.of(AlertKind.ZONE_EXIT).map { it.first.poiId })
        // Over the 50 limit throughout; the chime belongs to the camera last passed, never to the one ahead.
        val over = events.of(AlertKind.OVERSPEED)
        assertEquals(10L, over.first().first.poiId)
        assertTrue(over.any { it.first.poiId == 11L })
        assertTrue(over.none { it.first.poiId == 12L })
        assertTrue(over.all { it.first.overspeed && it.first.priority == 50 && it.first.distanceM == 0.0 })
        assertTrue(over.filter { it.second > 3_000.0 }.all { it.first.poiId == 11L })
        // The variable point's own stages are judged against the zone's limit (step 9): over, though it has none.
        val variableFirst = events.of(AlertKind.FIRST).single { it.first.poiId == 11L }
        assertTrue(variableFirst.first.overspeed)
        assertFalse(engine.isInZone)
    }

    @Test
    fun overspeedRepeatsEveryFifteenSecondsAndStartsJustBelowTheLimit() {
        val pois = listOf(point(10, 1_000.0), point(11, 4_000.0))
        val over = timed(AlertEngine(), pois, 4_100.0, mps = 60.0 * Geo.MPS_PER_MPH).of(AlertKind.OVERSPEED)
        assertEquals(8, over.size)
        val gaps = over.map { it.third }.zipWithNext { a, b -> b - a }
        assertTrue("gaps $gaps", gaps.all { it == 15_000L })
        // Exactly the limit is "over" with the default −0.1% threshold, and not with 0%.
        val atLimit = timed(AlertEngine(), pois, 4_100.0, mps = 50.0 * Geo.MPS_PER_MPH).of(AlertKind.OVERSPEED)
        assertTrue(atLimit.isNotEmpty())
        val exact = AlertEngine().apply { overspeedPct = 0.0 }
        assertTrue(timed(exact, pois, 4_100.0, mps = 50.0 * Geo.MPS_PER_MPH).of(AlertKind.OVERSPEED).isEmpty())
    }

    @Test
    fun zoneEndsAfterItsDistanceOrTimeCapEvenWithACameraAhead() {
        assertEquals(30_000.0, AlertEngine.Config().zoneMaxM, 0.0)
        assertEquals(30 * 60_000L, AlertEngine.Config().zoneMaxMs)
        assertEquals(5_000.0, AlertEngine.Config().zoneGapM, 0.0)
        assertEquals(15_000L, AlertEngine.Config().zoneOverspeedRepeatMs)
        assertEquals(1_000.0, AlertEngine.Config().zoneJumpM, 0.0)
        // Distance cap at 3 km (the config is honoured): out at ~4 km, in again at the next pair.
        val chain = listOf(point(10, 1_000.0), point(11, 2_500.0), point(12, 4_500.0), point(13, 6_000.0))
        val byDistance = timed(AlertEngine(AlertEngine.Config(zoneMaxM = 3_000.0)), chain, 6_200.0)
        assertEquals(listOf(10L, 12L), byDistance.of(AlertKind.ZONE_ENTER).map { it.first.poiId })
        val exits = byDistance.of(AlertKind.ZONE_EXIT)
        assertEquals(listOf(11L, 13L), exits.map { it.first.poiId })
        assertTrue("first exit at ${exits[0].second}", exits[0].second in 3_950.0..4_050.0)
        // Time cap at 60 s: out after a minute, well before the next camera, which then has nothing ahead.
        val byTime = timed(AlertEngine(AlertEngine.Config(zoneMaxMs = 60_000)), listOf(point(10, 1_000.0), point(11, 4_000.0)), 4_200.0)
        assertEquals(listOf(AlertKind.ZONE_ENTER, AlertKind.ZONE_EXIT), byTime.map { it.first.kind }.filter { it == AlertKind.ZONE_ENTER || it == AlertKind.ZONE_EXIT })
        val exit = byTime.of(AlertKind.ZONE_EXIT).single()
        assertEquals(10L, exit.first.poiId)
        assertTrue("left at ${exit.second}", exit.second < 2_300.0)
        assertTrue(exit.third - byTime.of(AlertKind.ZONE_ENTER).single().third in 60_000L..60_500L)
    }

    @Test
    fun otherCamerasInsideAZoneKeepTheirOwnLimit() {
        val zone = listOf(point(10, 1_000.0), point(11, 4_000.0))
        val redLight30 = camera(id = 20, northM = 2_500.0, limitMph = 30)
        val noLimit = camera(id = 21, northM = 3_000.0, limitMph = null)
        val events = timed(AlertEngine(), zone + redLight30 + noLimit, 3_100.0, mps = 40.0 * Geo.MPS_PER_MPH)
        assertTrue(events.of(AlertKind.ZONE_ENTER).single().second < 1_700.0)
        // 40 mph in the 50 zone: no zone chime, but the 30 camera inside it is over its own limit…
        assertTrue(events.of(AlertKind.OVERSPEED).isEmpty())
        assertTrue(events.of(AlertKind.FIRST).single { it.first.poiId == 20L }.first.overspeed)
        // …and a camera with no limit of its own borrows the zone's.
        assertFalse(events.of(AlertKind.FIRST).single { it.first.poiId == 21L }.first.overspeed)
        val faster = timed(AlertEngine(), zone + noLimit, 3_100.0, mps = 55.0 * Geo.MPS_PER_MPH)
        assertTrue(faster.of(AlertKind.FIRST).single { it.first.poiId == 21L }.first.overspeed)
    }

    @Test
    fun soundOffStillMovesTheChainAlong() {
        val pois = listOf(point(10, 1_000.0, sound = false), point(11, 3_000.0, sound = false))
        val kinds = timed(AlertEngine(), pois, 3_200.0).map { it.first.kind }
        assertTrue(AlertKind.PASSING !in kinds)
        assertEquals(listOf(AlertKind.ZONE_ENTER, AlertKind.ZONE_EXIT), kinds.filter { it == AlertKind.ZONE_ENTER || it == AlertKind.ZONE_EXIT })
    }

    @Test
    fun resetAndAJumpDropTheZoneWithoutAnExitAlert() {
        val pois = listOf(point(10, 1_000.0), point(11, 3_000.0))
        val engine = AlertEngine()
        assertEquals(1, timed(engine, pois, 1_200.0).of(AlertKind.ZONE_ENTER).size)
        assertTrue(engine.isInZone)
        engine.reset()
        assertFalse(engine.isInZone)
        // In again, then a 2 km jump between fixes: dropped silently.
        val again = AlertEngine()
        timed(again, pois, 1_200.0)
        assertTrue(again.isInZone)
        val after = again.onFix(fix(3_200.0, mps45, t = 1_000_000), pois)
        assertFalse(again.isInZone)
        assertTrue(after.none { it.kind == AlertKind.ZONE_EXIT || it.kind == AlertKind.OVERSPEED })
    }

    @Test
    fun zoneEventsComeAfterTheStagesOfTheSameFix() {
        val pois = listOf(point(10, 1_000.0), point(11, 3_000.0))
        val fixEvents = ArrayList<List<AlertEvent>>()
        drive(AlertEngine(), pois, 0.0, 1_200.0, mps45, stepMs = 500) { _, events -> if (events.size > 1) fixEvents += events }
        val enterFix = fixEvents.single { e -> e.any { it.kind == AlertKind.ZONE_ENTER } }
        assertEquals(listOf(AlertKind.PASSING, AlertKind.ZONE_ENTER), enterFix.kinds())
    }
}

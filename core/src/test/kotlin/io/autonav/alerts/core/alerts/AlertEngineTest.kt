// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ZoneRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** Synthetic tracks: a car driving due north along lon = 0 near 53°N. */
class AlertEngineTest {
    private val baseLat = 53.0
    private val metresPerDegLat = 111_195.0
    private fun latAt(northM: Double) = baseLat + northM / metresPerDegLat
    private fun lonAt(eastM: Double) = eastM / (metresPerDegLat * cos(Math.toRadians(baseLat)))

    private fun camera(
        id: Long = 1, northM: Double = 2_000.0, eastM: Double = 0.0,
        dir: DirectionType = DirectionType.ONE_WAY, bearing: Float? = 0f,
        first: AlertStage = AlertStage(true, DistanceMode.FIXED, 800),
        second: AlertStage = AlertStage(true, DistanceMode.FIXED, 300),
        sound: Boolean = true, zone: ZoneRole = ZoneRole.NONE, limitMph: Int? = 30,
    ) = AlertPoi(id, latAt(northM), lonAt(eastM), dir, bearing, limitMph?.let { it * Geo.MPS_PER_MPH }, zone, first, second, sound)

    /** Drives from 0 to [toM] north at [mph], one fix every [stepMs], heading [heading]. */
    private fun drive(
        engine: AlertEngine, pois: List<AlertPoi>, toM: Double = 3_000.0, mph: Double = 30.0,
        stepMs: Long = 200, heading: Double = 0.0, startM: Double = 0.0, startMs: Long = 0,
    ): List<AlertEvent> {
        val mps = mph * Geo.MPS_PER_MPH
        val out = ArrayList<AlertEvent>()
        var t = startMs
        var pos = startM
        val dir = if (heading == 180.0) -1 else 1
        while (if (dir > 0) pos <= toM else pos >= toM) {
            out += engine.onFix(Fix(latAt(pos), 0.0, mps, heading, t), pois)
            pos += dir * mps * stepMs / 1000.0
            t += stepMs
        }
        return out
    }

    @Test
    fun threeStagesFireInOrderOnce() {
        val events = drive(AlertEngine(), listOf(camera()))
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), events.map { it.kind })
        assertEquals(800.0, events[0].distanceM, 10.0)
        assertEquals(300.0, events[1].distanceM, 10.0)
        assertTrue(events[2].distanceM <= AlertEngine.PASSING_METRES)
    }

    @Test
    fun timeModeScalesWithSpeed() {
        val first = AlertStage(true, DistanceMode.TIME, metres = 457, seconds = 30)
        val at70 = drive(AlertEngine(), listOf(camera(first = first)), mph = 70.0).first()
        assertEquals(939.0, at70.distanceM, 15.0) // 30 s × 31.3 m/s
        val at30 = drive(AlertEngine(), listOf(camera(first = first)), mph = 30.0).first()
        assertEquals(457.0, at30.distanceM, 10.0) // minimum distance wins
    }

    @Test
    fun secondStageCanBeTurnedOff() {
        val events = drive(AlertEngine(), listOf(camera(second = AlertStage(false, DistanceMode.FIXED, 300))))
        assertEquals(listOf(AlertKind.FIRST, AlertKind.PASSING), events.map { it.kind })
    }

    @Test
    fun noPassingAlertWhenSoundIsOff() {
        val events = drive(AlertEngine(), listOf(camera(sound = false)))
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND), events.map { it.kind })
    }

    @Test
    fun oneWayCameraFacingTheOtherWayDoesNotAlert() {
        val events = drive(AlertEngine(), listOf(camera(bearing = 180f)))
        assertTrue(events.isEmpty())
    }

    @Test
    fun bothAndAnyDirectionsAlertEitherWay() {
        assertEquals(3, drive(AlertEngine(), listOf(camera(dir = DirectionType.BOTH, bearing = 180f))).size)
        assertEquals(3, drive(AlertEngine(), listOf(camera(dir = DirectionType.ANY, bearing = null))).size)
    }

    @Test
    fun parallelRoadIsIgnoredInFreeDrive() {
        val events = drive(AlertEngine(), listOf(camera(eastM = 60.0)))
        assertTrue(events.isEmpty())
    }

    @Test
    fun parallelRoadIsIgnoredWithRouteToo() {
        val engine = AlertEngine()
        val route = RouteMatcher { _, lon -> if (kotlin.math.abs(lon) < 1e-9) 0.0 else 60.0 }
        val mps = 30 * Geo.MPS_PER_MPH
        var events = 0
        var pos = 0.0; var t = 0L
        val poi = camera(eastM = 60.0)
        while (pos < 3_000) {
            events += engine.onFix(Fix(latAt(pos), 0.0, mps, 0.0, t), listOf(poi), route).size
            pos += mps * 0.2; t += 200
        }
        assertEquals(0, events)
    }

    @Test
    fun missedPassingStillFiresOnGpsJump() {
        val engine = AlertEngine()
        val poi = camera(first = AlertStage(true, DistanceMode.FIXED, 500), second = AlertStage(false, DistanceMode.FIXED, 300))
        val mps = 30 * Geo.MPS_PER_MPH
        val out = ArrayList<AlertEvent>()
        out += engine.onFix(Fix(latAt(1_600.0), 0.0, mps, 0.0, 0), listOf(poi)) // 400 m ahead → FIRST
        out += engine.onFix(Fix(latAt(1_880.0), 0.0, mps, 0.0, 1_000), listOf(poi)) // 120 m ahead
        out += engine.onFix(Fix(latAt(2_030.0), 0.0, mps, 0.0, 2_000), listOf(poi)) // jump: 30 m behind
        assertEquals(listOf(AlertKind.FIRST, AlertKind.PASSING), out.map { it.kind })
    }

    @Test
    fun passingIsNeverRateLimited() {
        // Two cameras 150 m apart: their alerts fall within the 3 s rate limit, but both PASSING alerts fire.
        val pois = listOf(camera(id = 1, northM = 2_000.0), camera(id = 2, northM = 2_150.0))
        val passings = drive(AlertEngine(), pois, mph = 70.0).count { it.kind == AlertKind.PASSING }
        assertEquals(2, passings)
    }

    @Test
    fun secondPassReAlerts() {
        val engine = AlertEngine()
        val poi = camera(dir = DirectionType.BOTH)
        val north = drive(engine, listOf(poi), toM = 3_000.0)
        val south = drive(engine, listOf(poi), toM = 0.0, heading = 180.0, startM = 3_000.0, startMs = 1_000_000)
        assertEquals(3, north.size)
        assertEquals(3, south.size)
    }

    @Test
    fun crossingSeveralStagesAtOnceSpeaksOnlyTheClosest() {
        val engine = AlertEngine()
        val poi = camera()
        val mps = 30 * Geo.MPS_PER_MPH
        // First fix already at 250 m: inside both the 800 m and 300 m stages.
        val events = engine.onFix(Fix(latAt(1_750.0), 0.0, mps, 0.0, 0), listOf(poi))
        assertEquals(listOf(AlertKind.SECOND), events.map { it.kind })
    }

    @Test
    fun averageSpeedChainEntersAndLeaves() {
        val pois = (0 until 4).map { i -> camera(id = 10L + i, northM = 1_000.0 + i * 2_000.0, zone = ZoneRole.ZONE_POINT, limitMph = 50) }
        val events = drive(AlertEngine(), pois, toM = 16_000.0, mph = 50.0, stepMs = 500)
        val kinds = events.map { it.kind }
        assertEquals(1, kinds.count { it == AlertKind.ZONE_ENTER })
        assertEquals(1, kinds.count { it == AlertKind.ZONE_EXIT })
        assertTrue(kinds.indexOf(AlertKind.ZONE_ENTER) < kinds.indexOf(AlertKind.ZONE_EXIT))
    }

    @Test
    fun overspeedFlaggedOnAlert() {
        val first = drive(AlertEngine(), listOf(camera(limitMph = 30)), mph = 40.0).first()
        assertTrue(first.overspeed)
    }
}

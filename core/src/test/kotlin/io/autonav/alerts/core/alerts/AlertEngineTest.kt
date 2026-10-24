// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ZoneRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** Synthetic tracks: a car driving due north along lon = 0 near 53°N, plus the [NoisyTrack] cases of DESIGN.md §15.1. */
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

    private fun List<AlertEvent>.kinds() = map { it.kind }
    private fun List<AlertEvent>.first(kind: AlertKind) = first { it.kind == kind }
    private fun straight(mph: Double, sigma: Double = 0.0, heading: Double = 0.0, acc: Double? = null, seed: Long = 1) =
        NoisyTrack(initialHeadingDeg = heading, sigmaM = sigma, headingAccuracyDeg = acc, seed = seed) { NoisyTrack.mph(mph) }

    /** Records every trace call. */
    private class RecordingTrace : AlertTrace {
        val verdicts = ArrayList<Pair<Long, Verdict>>()
        val stages = ArrayList<AlertEvent>()
        val cleared = ArrayList<Pair<Long, ClearReason>>()
        val dims = ArrayList<Triple<Long, Boolean, Double>>()
        override fun verdict(poiId: Long, verdict: Verdict, d: Double, offAxisDeg: Double, headingDeg: Double?, poiBearing: Float?, dirType: DirectionType, lateralM: Double, allowedM: Double) {
            verdicts += poiId to verdict
        }
        override fun stage(event: AlertEvent) { stages += event }
        override fun cleared(poiId: Long, reason: ClearReason) { cleared += poiId to reason }
        override fun dimmed(poiId: Long, dimmed: Boolean, mismatchDeg: Double, headingAccuracyDeg: Double) { dims += Triple(poiId, dimmed, mismatchDeg) }
    }

    // ---- The original straight-track cases ----

    @Test
    fun threeStagesFireInOrderOnce() {
        val events = drive(AlertEngine(), listOf(camera()))
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), events.kinds())
        assertEquals(800.0, events[0].distanceM, 10.0)
        assertEquals(300.0, events[1].distanceM, 10.0)
        assertTrue(events[2].distanceM <= AlertEngine.PASSING_METRES)
        // Each event says what it was triggered by and when speech of it would be stale (§12.3).
        assertEquals(800.0, events[0].triggerM, 0.01)
        assertEquals(300.0, events[0].staleBelowM!!, 0.01)
        assertEquals(AlertEngine.PASSING_METRES, events[1].staleBelowM!!, 0.01)
        assertNull(events[2].staleBelowM)
    }

    @Test
    fun secondStageCanBeTurnedOff() {
        val events = drive(AlertEngine(), listOf(camera(second = AlertStage(false, DistanceMode.FIXED, 300))))
        assertEquals(listOf(AlertKind.FIRST, AlertKind.PASSING), events.kinds())
        // With no 2nd stage, the 1st goes stale at the passing distance.
        assertEquals(AlertEngine.PASSING_METRES, events[0].staleBelowM!!, 0.01)
    }

    @Test
    fun passingIsAnnouncedDrawingLevelWithACameraBesideTheRoad() {
        // 30 m to the side: never within 20 yd in a straight line, so the trigger is along the track (§11.1).
        val engine = AlertEngine()
        val pois = listOf(camera(eastM = 30.0))
        val kinds = ArrayList<AlertKind>()
        var passedAtM = Double.NaN
        val mps = 30.0 * Geo.MPS_PER_MPH
        var pos = 0.0; var t = 0L
        while (pos <= 3_000.0) {
            for (e in engine.onFix(Fix(latAt(pos), 0.0, mps, 0.0, t), pois)) { kinds += e.kind; if (e.kind == AlertKind.PASSING) passedAtM = pos }
            pos += mps * 0.2; t += 200
        }
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), kinds)
        // Level with the camera (within 20 yd along the track), not from the missed-passing net once behind.
        assertTrue("passing at $passedAtM m north", passedAtM >= 2_000.0 - AlertEngine.PASSING_METRES && passedAtM <= 2_000.0 + 1.0)
    }

    @Test
    fun passingComesOneSecondAheadAtSpeed() {
        // 70 mph: 31.3 m a second, so the trigger is 1 s ahead, well outside the 20 yd floor (§11.1).
        val engine = AlertEngine()
        val events = drive(engine, listOf(camera()), mph = 70.0, stepMs = 100)
        val passing = events.first(AlertKind.PASSING)
        val lead = 70.0 * Geo.MPS_PER_MPH
        assertTrue("fired at ${passing.distanceM} m", passing.distanceM <= lead + 0.01 && passing.distanceM > lead - 4.0)
        assertEquals(lead, passing.triggerM, 0.01)
        // At 30 mph the floor rules: 1 s is 13.4 m, under 20 yd.
        val slow = drive(AlertEngine(), listOf(camera()))
        assertEquals(AlertEngine.PASSING_METRES, slow.first(AlertKind.PASSING).triggerM, 0.01)
    }

    @Test
    fun noPassingAlertWhenSoundIsOff() {
        val events = drive(AlertEngine(), listOf(camera(sound = false)))
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND), events.kinds())
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
    fun parallelRoadNeverGetsPassingInFreeDrive() {
        // Updated 2026-10-12: the free-drive corridor is max(40 m, d·sin 12°) (§11 step 4), so a camera on a
        // parallel road 60 m away is inside the corridor beyond 289 m and may get a far stage; it is never
        // inside the 40 m near corridor, so nothing fires within 192 m and PASSING never fires.
        val events = drive(AlertEngine(), listOf(camera(eastM = 60.0)))
        assertTrue(AlertKind.PASSING !in events.kinds())
        assertTrue(events.all { it.distanceM > 192.0 })
    }

    @Test
    fun mobileSiteBesideTheRoadStillAlerts() {
        // A mobile van site 62 m to the side (bridge / lay-by), like the M40 site found on the bench.
        val mobile = camera(eastM = 62.0).copy(sideToleranceM = 100.0)
        val kinds = drive(AlertEngine(), listOf(mobile)).kinds()
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND), kinds.take(2))
        assertTrue(AlertKind.PASSING in kinds)
        // The direction rule still applies to it.
        assertTrue(drive(AlertEngine(), listOf(camera(eastM = 62.0, bearing = 180f).copy(sideToleranceM = 100.0))).isEmpty())
        // A fixed camera at the same offset is still treated as a parallel road where it matters: no passing alert.
        assertTrue(AlertKind.PASSING !in drive(AlertEngine(), listOf(camera(eastM = 62.0))).kinds())
    }

    @Test
    fun cameraOnABendWithin45DegreesOfTheHeadingAlerts() {
        // Guiding along a route that bends right: the camera is on the route, 40° off our heading.
        val onRoute = RouteMatcher { _, _ -> 0.0 }
        val cam = camera(northM = 383.0, eastM = 321.0, bearing = 0f) // ~500 m away at 40°
        val fix = Fix(latAt(0.0), 0.0, 13.4, 0.0, 0)
        assertEquals(AlertKind.FIRST, AlertEngine().onFix(fix, listOf(cam), onRoute).single().kind)
        // At 50° it isn't ahead.
        val wide = camera(northM = 321.0, eastM = 383.0, bearing = 0f)
        assertTrue(AlertEngine().onFix(fix, listOf(wide), onRoute).isEmpty())
    }

    @Test
    fun camerasForTheOtherWayOnOurRoadAreFacingAway() {
        // Updated 2026-10-12 for the verdicts (§8.1): dimming needs a confident mismatch while moving, and
        // clears once the camera is behind; "our road" is the free-drive corridor max(40 m, d·sin 12°).
        val engine = AlertEngine()
        val fix = Fix(latAt(0.0), 0.0, 26.8, 0.0, 0)
        val away = camera(id = 1, northM = 500.0, bearing = 180f) // ahead on our road, for southbound traffic
        val behind = camera(id = 2, northM = -500.0, bearing = 180f) // just passed, other way: not dimmed any more
        val ours = camera(id = 3, northM = 500.0) // ahead, our way
        val both = camera(id = 4, northM = 500.0, dir = DirectionType.BOTH, bearing = 180f) // both ways: ours too
        val sideRoad = camera(id = 5, northM = 1_000.0, eastM = 600.0, bearing = 180f) // another road
        val parallel = camera(id = 6, northM = 300.0, eastM = 80.0, bearing = 180f) // a parallel road, 80 m off at 300 m
        val far = camera(id = 7, northM = 3_500.0, bearing = 180f)
        val van = parallel.copy(id = 8, sideToleranceM = 100.0) // a mobile site is allowed further to the side
        val pois = listOf(away, behind, ours, both, sideRoad, parallel, far, van)
        engine.onFix(fix, pois)
        assertEquals(setOf(1L, 8L), engine.facingAway())
        val verdicts = engine.classify()
        assertEquals(Verdict.WRONG_DIRECTION, verdicts[1L])
        assertEquals(Verdict.BEHIND, verdicts[2L])
        assertEquals(Verdict.AHEAD_OK, verdicts[3L])
        assertEquals(Verdict.AHEAD_OK, verdicts[4L])
        assertEquals(Verdict.OFF_ROAD, verdicts[5L])
        assertEquals(Verdict.OFF_ROAD, verdicts[6L])
        assertEquals(Verdict.OUT_OF_RANGE, verdicts[7L])
        assertEquals(Verdict.WRONG_DIRECTION, verdicts[8L])
        // On a route, "our road" is the route.
        val offRoute = RouteMatcher { _, _ -> 200.0 }
        val routed = AlertEngine()
        routed.onFix(fix, listOf(away), offRoute)
        assertTrue(routed.facingAway().isEmpty())
        assertEquals(Verdict.OFF_ROAD, routed.classify()[1L])
        // Heading not known yet: nothing is hidden.
        val blind = AlertEngine()
        blind.onFix(Fix(latAt(0.0), 0.0, 26.8, null, 0), pois)
        assertTrue(blind.facingAway().isEmpty())
        assertEquals(Verdict.NO_HEADING, blind.classify()[1L])
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
        assertEquals(listOf(AlertKind.FIRST, AlertKind.PASSING), out.kinds())
    }

    @Test
    fun passingIsNeverRateLimited() {
        // Two cameras 150 m apart: both PASSING alerts fire (and now every stage does, see noStageLost…).
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
        assertEquals(listOf(AlertKind.SECOND), events.kinds())
    }

    @Test
    fun averageSpeedChainEntersAndLeaves() {
        val pois = (0 until 4).map { i -> camera(id = 10L + i, northM = 1_000.0 + i * 2_000.0, zone = ZoneRole.ZONE_POINT, limitMph = 50) }
        val events = drive(AlertEngine(), pois, toM = 16_000.0, mph = 50.0, stepMs = 500)
        val kinds = events.kinds()
        assertEquals(1, kinds.count { it == AlertKind.ZONE_ENTER })
        assertEquals(1, kinds.count { it == AlertKind.ZONE_EXIT })
        assertTrue(kinds.indexOf(AlertKind.ZONE_ENTER) < kinds.indexOf(AlertKind.ZONE_EXIT))
    }

    @Test
    fun averageSpeedZoneEndsAtTheLastCamera() {
        val pois = (0 until 3).map { i -> camera(id = 10L + i, northM = 1_000.0 + i * 2_000.0, zone = ZoneRole.ZONE_POINT, limitMph = 50) }
        val events = drive(AlertEngine(), pois, toM = 6_000.0, mph = 50.0, stepMs = 500)
        val exit = events.single { it.kind == AlertKind.ZONE_EXIT }
        assertEquals(12L, exit.poiId)
    }

    @Test
    fun loneAverageSpeedCameraIsNotAZone() {
        val pois = listOf(camera(id = 10, northM = 1_000.0, zone = ZoneRole.ZONE_POINT, limitMph = 50))
        val kinds = drive(AlertEngine(), pois, toM = 3_000.0, mph = 70.0, stepMs = 500).kinds()
        assertTrue(AlertKind.ZONE_ENTER !in kinds && AlertKind.OVERSPEED !in kinds)
    }

    @Test
    fun zoneWithADifferentLimitAheadIsNotOurZone() {
        val pois = listOf(
            camera(id = 10, northM = 1_000.0, zone = ZoneRole.ZONE_POINT, limitMph = 50),
            camera(id = 11, northM = 3_000.0, zone = ZoneRole.ZONE_POINT, limitMph = 30),
        )
        val kinds = drive(AlertEngine(), pois, toM = 4_000.0, mph = 40.0, stepMs = 500).kinds()
        assertTrue(AlertKind.ZONE_ENTER !in kinds)
    }

    @Test
    fun zoneOverspeedCarriesTheZoneCameraAndAJumpEndsTheZone() {
        val engine = AlertEngine()
        val pois = (0 until 3).map { i -> camera(id = 10L + i, northM = 1_000.0 + i * 3_000.0, zone = ZoneRole.ZONE_POINT, limitMph = 30) }
        val events = drive(engine, pois, toM = 2_500.0, mph = 45.0, stepMs = 500)
        assertEquals(10L, events.first(AlertKind.OVERSPEED).poiId)
        assertTrue(engine.isInZone)
        // 20 km away in one fix: the zone is dropped and nothing more is said about it.
        val after = engine.onFix(Fix(latAt(22_500.0), 0.0, 45 * Geo.MPS_PER_MPH, 0.0, 1_000_000), pois)
        assertTrue(!engine.isInZone && after.none { it.kind == AlertKind.OVERSPEED })
    }

    @Test
    fun overspeedFlaggedOnAlert() {
        val first = drive(AlertEngine(), listOf(camera(limitMph = 30)), mph = 40.0).first()
        assertTrue(first.overspeed)
        // The threshold is updated in place (§11 step 9): +40% makes 40 mph fine for a 30 limit.
        val lenient = AlertEngine().apply { overspeedPct = 0.4 }
        assertFalse(drive(lenient, listOf(camera(limitMph = 30)), mph = 40.0).first().overspeed)
    }

    // ---- The 2026-10-12 cases (DESIGN.md §15.1), on noisy tracks ----

    @Test
    fun defaultsAt70And30Mph() {
        // 15 s and 7.5 s at the current speed, floors 242 m / 142 m (§11.1).
        val fast = straight(70.0)
        val at70 = fast.drive(AlertEngine(), listOf(fast.camera(1, 2_000.0)), 0.0, 2_300.0)
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), at70.kinds())
        assertEquals(469.0, at70[0].distanceM, 8.0)
        assertEquals(235.0, at70[1].distanceM, 8.0)
        val slow = straight(30.0)
        val at30 = slow.drive(AlertEngine(), listOf(slow.camera(1, 2_000.0)), 0.0, 2_300.0)
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), at30.kinds())
        assertEquals(242.0, at30[0].distanceM, 4.0) // 201 m lifted to the floor
        assertEquals(142.0, at30[1].distanceM, 4.0)
    }

    @Test
    fun firstFiresOnScheduleWithHeadingNoise() {
        val track = straight(70.0, sigma = 2.5, acc = 5.0)
        val events = track.drive(AlertEngine(), listOf(track.camera(1, 2_000.0)), 0.0, 2_300.0)
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), events.kinds())
        assertTrue("FIRST at ${events[0].distanceM}", events[0].distanceM >= 0.9 * 469.0)
    }

    @Test
    fun compassQuantisedBearingStillAlerts() {
        // PGW bearings are 8-point: a camera stored as 45° on a road heading 60° alerts…
        val road60 = straight(50.0, sigma = 2.0, heading = 60.0)
        assertEquals(3, road60.drive(AlertEngine(), listOf(road60.camera(1, 1_500.0, bearing = 45f)), 0.0, 1_800.0).size)
        // …a road heading 95° is more than 45° off a 45° ONE_WAY camera: silent (the hard rule). That is
        // 5° inside the edge, so it's checked with an exact heading; with 2 m of noise, 110° is silent too.
        val road95 = straight(50.0, heading = 95.0)
        assertTrue(road95.drive(AlertEngine(), listOf(road95.camera(1, 1_500.0, bearing = 45f)), 0.0, 1_800.0).isEmpty())
        val road110 = straight(50.0, sigma = 2.0, heading = 110.0)
        assertTrue(road110.drive(AlertEngine(), listOf(road110.camera(1, 1_500.0, bearing = 45f)), 0.0, 1_800.0).isEmpty())
        // BOTH covers the bearing and its opposite: 225° on the 60° road alerts.
        assertEquals(3, road60.drive(AlertEngine(), listOf(road60.camera(1, 1_500.0, dir = DirectionType.BOTH, bearing = 225f)), 0.0, 1_800.0).size)
    }

    @Test
    fun cameraOnGentleBendAlertsAllThreeStages() {
        val bend = NoisyTrack(radiusM = 800.0, sigmaM = 2.5, headingAccuracyDeg = 5.0) { NoisyTrack.mph(70.0) }
        val cam = bend.camera(1, 1_500.0, bearing = NoisyTrack.compass8(bend.road(1_500.0).headingDeg))
        val events = bend.drive(AlertEngine(), listOf(cam), 0.0, 1_700.0)
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), events.kinds())
        assertTrue(events[0].distanceM > events[1].distanceM && events[1].distanceM >= 142.0)
    }

    @Test
    fun cameraOnSharpBendFiresLateNotNever() {
        // R = 250 m at 30 mph: the corridor only sees the camera inside ~140 m, so the stages are late, not lost.
        val bend = NoisyTrack(radiusM = 250.0, sigmaM = 2.5, headingAccuracyDeg = 5.0) { NoisyTrack.mph(30.0) }
        val cam = bend.camera(1, 600.0)
        val events = bend.drive(AlertEngine(), listOf(cam), 0.0, 750.0)
        assertTrue(AlertKind.PASSING in events.kinds())
        assertTrue(events.any { it.kind == AlertKind.FIRST || it.kind == AlertKind.SECOND })
        assertTrue(events.filter { it.kind != AlertKind.PASSING }.all { it.distanceM < 242.0 })
    }

    @Test
    fun camera_30DegBend_directionPassesViaBearingToCamera() {
        // A road bending 30° right with the camera just past the bend, stored as 45° (8-point compass), on
        // the route. Our heading reads 350° (10° of error before the bend): 55° off the camera's bearing,
        // but the bearing from us to the camera (25°) is within 45° of it, so the direction rule passes
        // (owner's amendment, §11 step 3).
        val onRoute = RouteMatcher { _, _ -> 0.0 }
        val cam = camera(northM = 272.0, eastM = 127.0, bearing = 45f)
        val fix = Fix(latAt(0.0), 0.0, 13.4, 350.0, 0)
        assertEquals(AlertKind.FIRST, AlertEngine().onFix(fix, listOf(cam), onRoute).single().kind)
    }

    @Test
    fun oppositeCameraStillRejectedOnABend() {
        // HARD RULE regression: the bearing-to-camera alternative can't pass an opposite-facing camera.
        val onRoute = RouteMatcher { _, _ -> 0.0 }
        val cam = camera(northM = 272.0, eastM = 127.0, bearing = 225f)
        val fix = Fix(latAt(0.0), 0.0, 13.4, 350.0, 0)
        assertTrue(AlertEngine().onFix(fix, listOf(cam), onRoute).isEmpty())
        // The same on a real arc, driven with noise: a camera for the oncoming traffic round an R = 400 m bend.
        val bend = NoisyTrack(radiusM = 400.0, sigmaM = 2.5, headingAccuracyDeg = 5.0) { NoisyTrack.mph(40.0) }
        val oncoming = bend.camera(1, 800.0, bearing = Geo.normalizeDeg(bend.road(800.0).headingDeg + 180.0).toFloat())
        assertTrue(bend.drive(AlertEngine(), listOf(oncoming), 0.0, 950.0).isEmpty())
        val facing = bend.camera(2, 800.0)
        assertEquals(3, bend.drive(AlertEngine(), listOf(facing), 0.0, 950.0).size)
    }

    @Test
    fun oppositeCameraStillRejectedWithHeadingNoise() {
        // HARD RULE regression: heading noise never lets an opposite camera through, and it is dimmed.
        val track = straight(70.0, sigma = 2.5, acc = 5.0)
        val engine = AlertEngine()
        val cam = track.camera(1, 2_000.0, bearing = 180f)
        var dimmedSeen = false
        val events = track.drive(engine, listOf(cam), 0.0, 2_300.0) { _, _ -> if (1L in engine.facingAway()) dimmedSeen = true }
        assertTrue(events.isEmpty())
        assertTrue(dimmedSeen)
    }

    @Test
    fun parallelRoadStillNoPassingWithRealHeadingError() {
        val track = straight(30.0, sigma = 2.5, acc = 5.0)
        val cam = track.camera(1, 2_000.0, lateralM = 60.0)
        val events = track.drive(AlertEngine(), listOf(cam), 0.0, 2_300.0)
        assertTrue(AlertKind.PASSING !in events.kinds())
    }

    @Test
    fun noStageLostWithTwoCamerasCloseTogether() {
        // 150 m apart at 70 mph: the old 3 s rate limit lost one of them for good. Every stage is emitted now.
        val track = straight(70.0, sigma = 2.0, acc = 5.0)
        val pois = listOf(track.camera(1, 2_000.0), track.camera(2, 2_150.0))
        val events = track.drive(AlertEngine(), pois, 0.0, 2_400.0)
        for (kind in listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING)) {
            assertEquals("$kind", setOf(1L, 2L), events.filter { it.kind == kind }.map { it.poiId }.toSet())
        }
        assertEquals(6, events.size)
    }

    @Test
    fun parkedJitterDoesNotConsumeStages() {
        val track = straight(30.0, sigma = 3.0)
        val engine = AlertEngine()
        val cam = track.camera(1, 1_000.0)
        assertTrue(track.drive(engine, listOf(cam), 0.0, 700.0).isEmpty()) // 300 m short of it: no stage yet
        // Two minutes parked with 3 m of jitter: a reported 0 is a real 0, so nothing happens…
        assertTrue(track.hold(engine, listOf(cam), 700.0, 120.0, startMs = 60_000).isEmpty())
        // …and a few jitter fixes under the 2 m/s moving gate, with a heading, don't start a stage either.
        assertTrue(track.hold(engine, listOf(cam), 700.0, 2.0, startMs = 181_000, speedMps = 1.5, heading = 0.0).isEmpty())
        // Driving off, all three stages fire as if nothing happened.
        val events = track.drive(engine, listOf(cam), 700.0, 1_150.0, startMs = 184_000)
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), events.kinds())
    }

    @Test
    fun staleFiredStateExpires() {
        val track = straight(30.0)
        val trace = RecordingTrace()
        val engine = AlertEngine(trace = trace)
        val cam = track.camera(1, 1_000.0)
        val before = track.drive(engine, listOf(cam), 0.0, 770.0) // FIRST fires at 242 m, we stop at 230 m
        assertEquals(listOf(AlertKind.FIRST), before.kinds())
        // 100 s parked with no approach: the fired state expires (§11 step 7), so the real approach alerts.
        assertTrue(track.hold(engine, listOf(cam), 770.0, 100.0, startMs = 60_000).isEmpty())
        assertTrue(1L to ClearReason.STALE in trace.cleared)
        val after = track.drive(engine, listOf(cam), 770.0, 1_150.0, startMs = 170_000)
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), after.kinds())
    }

    @Test
    fun waitingAtTheCameraAfterPassingDoesNotSayPassingAgain() {
        // A red-light camera: PASSING fires, then 100 s at the stop line 10 m short of it (inside the 20 yd passing alert), then on through.
        val track = straight(30.0)
        val trace = RecordingTrace()
        val engine = AlertEngine(trace = trace)
        val cam = track.camera(1, 1_000.0)
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), track.drive(engine, listOf(cam), 0.0, 990.0).kinds())
        assertTrue(track.hold(engine, listOf(cam), 990.0, 100.0, startMs = 80_000).isEmpty())
        assertTrue(1L to ClearReason.STALE !in trace.cleared)
        assertTrue(track.drive(engine, listOf(cam), 990.0, 1_150.0, startMs = 190_000).isEmpty())
    }

    @Test
    fun passingWhileCrawling() {
        val track = straight(30.0)
        val engine = AlertEngine()
        val cam = track.camera(1, 1_000.0)
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND), track.drive(engine, listOf(cam), 0.0, 880.0).kinds())
        // Creeping past it at 0.8 m/s (a queue): below the 2 m/s moving gate, but PASSING still fires once tracked.
        val crawl = NoisyTrack { 0.8 }
        val events = crawl.drive(engine, listOf(cam), 880.0, 995.0, startMs = 20_000)
        assertEquals(listOf(AlertKind.PASSING), events.kinds())
        assertTrue(events[0].distanceM <= AlertEngine.PASSING_METRES)
    }

    @Test
    fun brakingDoesNotDelayFirst() {
        // 70 mph until 600 m before the camera, hard on the brakes down to 30 mph: the ratchet keeps the
        // 1st at the 70 mph distance (§11 step 5) instead of shrinking it to the 242 m floor.
        val track = NoisyTrack { s -> when { s < 1_400.0 -> NoisyTrack.mph(70.0); s < 1_520.0 -> NoisyTrack.mph(70.0 - (s - 1_400.0) / 3.0); else -> NoisyTrack.mph(30.0) } }
        val events = track.drive(AlertEngine(), listOf(track.camera(1, 2_000.0)), 0.0, 2_200.0)
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), events.kinds())
        assertTrue("FIRST at ${events[0].distanceM}", events[0].distanceM >= 0.9 * 469.0)
        assertEquals(469.0, events[0].triggerM, 2.0)
        // The 2nd keeps its ratcheted distance too (7.5 s at 70 mph), not the 142 m floor of 30 mph.
        assertEquals(235.0, events[1].distanceM, 8.0)
    }

    @Test
    fun mutedStillEmitsPassing() {
        // Mute is the app's concern (§12.3): the engine only sees soundOn, and with it on the passing
        // alert is compulsory, so a muted car still gets the passing card.
        val track = straight(30.0)
        val events = track.drive(AlertEngine(), listOf(track.camera(1, 1_000.0, sound = true)), 0.0, 1_100.0)
        assertTrue(AlertKind.PASSING in events.kinds())
    }

    @Test
    fun headingClearsAfterStationaryHold() {
        val track = straight(30.0, sigma = 3.0)
        val engine = AlertEngine()
        val cam = track.camera(1, 1_000.0)
        track.drive(engine, listOf(cam), 0.0, 700.0)
        // Stopped: the heading is held, so the camera is still judged (not moving)…
        track.hold(engine, listOf(cam), 700.0, 20.0, startMs = 60_000)
        assertEquals(Verdict.NOT_MOVING, engine.classify()[1L])
        // …until 30 s without movement, when it is dropped and nothing can be judged.
        track.hold(engine, listOf(cam), 700.0, 15.0, startMs = 80_000)
        assertEquals(Verdict.NO_HEADING, engine.classify()[1L])
        // A jitter fix with a heading under the moving gate doesn't bring it back.
        track.hold(engine, listOf(cam), 700.0, 1.0, startMs = 95_000, speedMps = 1.5, heading = 0.0)
        assertEquals(Verdict.NO_HEADING, engine.classify()[1L])
        // Moving again with a heading: judged again.
        track.drive(engine, listOf(cam), 700.0, 720.0, startMs = 97_000)
        assertEquals(Verdict.AHEAD_OK, engine.classify()[1L])
    }

    @Test
    fun facingAwayNeedsConfidenceAndHysteresis() {
        val engine = AlertEngine()
        val cam = camera(northM = 300.0, bearing = 52f) // straight ahead, 52° off our heading
        fun fix(heading: Double, acc: Double?, speed: Double = 13.4, t: Long) = Fix(latAt(0.0), 0.0, speed, heading, t, headingAccuracyDeg = acc)
        // 52° off with a 10° heading accuracy: wrong direction, but not confidently (needs > 55°).
        engine.onFix(fix(0.0, 10.0, t = 0), listOf(cam))
        assertEquals(Verdict.WRONG_DIRECTION, engine.classify()[1L])
        assertTrue(engine.facingAway().isEmpty())
        // With a 5° accuracy (> 50°) it is: dimmed.
        engine.onFix(fix(0.0, 5.0, t = 200), listOf(cam))
        assertEquals(setOf(1L), engine.facingAway())
        // Hysteresis: at 48° off it stays dimmed…
        engine.onFix(fix(4.0, 5.0, t = 400), listOf(cam))
        assertEquals(setOf(1L), engine.facingAway())
        // …and comes back once under 40° (here the direction rule passes too).
        engine.onFix(fix(15.0, 5.0, t = 600), listOf(cam))
        assertTrue(engine.facingAway().isEmpty())
        // Not moving: never dimmed, however far off the held heading is.
        val parked = AlertEngine()
        parked.onFix(fix(0.0, 10.0, t = 0), listOf(cam)) // moving, 52° off, not confident
        parked.onFix(fix(0.0, 5.0, speed = 0.0, t = 200), listOf(cam)) // stopped: the 0° heading is held
        assertEquals(Verdict.WRONG_DIRECTION, parked.classify()[1L])
        assertTrue(parked.facingAway().isEmpty())
    }

    @Test
    fun traceReportsWhyACameraWasSuppressed() {
        // The wrong-way track: the trace says WRONG_DIRECTION, dims it, never a stage, then PASSED.
        val wrongWay = RecordingTrace()
        assertTrue(drive(AlertEngine(trace = wrongWay), listOf(camera(bearing = 180f))).isEmpty())
        assertTrue(1L to Verdict.WRONG_DIRECTION in wrongWay.verdicts)
        assertTrue(wrongWay.verdicts.none { it.second == Verdict.AHEAD_OK })
        assertTrue(wrongWay.stages.isEmpty())
        assertTrue(wrongWay.dims.any { it.first == 1L && it.second && it.third > 170.0 })
        assertEquals(listOf(1L to ClearReason.PASSED), wrongWay.cleared)
        // The parallel road: OFF_ROAD once inside the 40 m near corridor, no PASSING stage, then PASSED.
        val parallel = RecordingTrace()
        val track = straight(30.0)
        track.drive(AlertEngine(trace = parallel), listOf(track.camera(1, 2_000.0, lateralM = 60.0)), 0.0, 2_300.0)
        assertTrue(1L to Verdict.OFF_ROAD in parallel.verdicts)
        assertTrue(parallel.stages.none { it.kind == AlertKind.PASSING })
        assertEquals(listOf(1L to ClearReason.PASSED), parallel.cleared)
        // Every emitted event reaches the trace, and verdicts are reported on change only.
        val good = RecordingTrace()
        val events = drive(AlertEngine(trace = good), listOf(camera()))
        assertEquals(events, good.stages)
        assertEquals(listOf(1L to Verdict.AHEAD_OK), good.verdicts.take(1))
        assertTrue(good.verdicts.size <= 3)
    }

    @Test
    fun classificationMatchesEvents() {
        // On every fix the verdict map (what the markers show) agrees with what fired: a stage only for
        // AHEAD_OK, PASSING also while crawling or from the safety net, never anything for the other verdicts.
        val bend = NoisyTrack(radiusM = 800.0, sigmaM = 2.5, headingAccuracyDeg = 5.0) { NoisyTrack.mph(60.0) }
        val engine = AlertEngine()
        val ours = bend.camera(1, 1_500.0)
        val theirs = bend.camera(2, 1_300.0, bearing = Geo.normalizeDeg(bend.road(1_300.0).headingDeg + 180.0).toFloat())
        val events = bend.drive(engine, listOf(ours, theirs), 0.0, 1_700.0) { _, fired ->
            val verdicts = engine.classify()
            assertTrue(verdicts[2L] != Verdict.AHEAD_OK)
            for (e in fired) {
                assertEquals(1L, e.poiId)
                val v = verdicts[1L]
                assertNotNull(v)
                when (e.kind) {
                    AlertKind.FIRST, AlertKind.SECOND -> assertEquals(Verdict.AHEAD_OK, v)
                    AlertKind.PASSING -> assertTrue(v == Verdict.AHEAD_OK || v == Verdict.NOT_MOVING || v == Verdict.BEHIND)
                    else -> Unit
                }
            }
        }
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), events.kinds())
    }

    @Test
    fun pruneAndForgetDropIdleState() {
        val trace = RecordingTrace()
        val engine = AlertEngine(trace = trace)
        val cam = camera(northM = 500.0)
        engine.onFix(Fix(latAt(0.0), 0.0, 13.4, 0.0, 0), listOf(cam)) // FIRST fires, state exists
        engine.onFix(Fix(latAt(2.0), 0.0, 13.4, 0.0, 61_000), emptyList()) // gone from the candidates for 61 s
        assertEquals(listOf(1L to ClearReason.PRUNED), trace.cleared)
        engine.onFix(Fix(latAt(4.0), 0.0, 13.4, 0.0, 62_000), listOf(cam))
        engine.forgetExcept(emptySet())
        assertEquals(2, trace.cleared.size)
        assertTrue(engine.classify().isEmpty() || engine.classify()[1L] == null)
    }
}

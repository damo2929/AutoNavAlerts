// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.alerts.StraightRoad.ON_ROUTE
import io.autonav.alerts.core.alerts.StraightRoad.camera
import io.autonav.alerts.core.alerts.StraightRoad.fix
import io.autonav.alerts.core.alerts.StraightRoad.kinds
import io.autonav.alerts.core.poi.DirectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The camera direction rule at its edges (DESIGN.md §11 step 3, the owner's hard rule; AGENTS.md
 * §3.1 rule 1): the 45° tolerance for ONE_WAY and BOTH on both sides of the boundary, the β leg
 * only while the camera is properly ahead, the tolerance never raised above 45°, the facing-away
 * dimming confidence and hysteresis (§8.1), and the side allowance of mobile sites (§11 step 4).
 * The pinned regressions `camera_30DegBend_directionPassesViaBearingToCamera` and
 * `oppositeCameraStillRejectedOnABend` live in AlertEngineTest and are not touched here.
 */
class AlertEngineDirectionTest {
    /** The verdict and events for one camera on one fix straight up the road. */
    private fun judge(cam: AlertPoi, f: Fix = fix(0.0), route: RouteMatcher? = ON_ROUTE, config: AlertEngine.Config = AlertEngine.Config()): Pair<Verdict?, List<AlertEvent>> {
        val engine = AlertEngine(config)
        val events = engine.onFix(f, listOf(cam), route)
        return engine.classify()[cam.id] to events
    }

    @Test
    fun oneWayPassesAtExactly45DegreesAndFailsAt46() {
        for (b in listOf(44f, 45f, 315f, 316f)) {
            val (verdict, events) = judge(camera(bearing = b))
            assertEquals("bearing $b", Verdict.AHEAD_OK, verdict)
            assertEquals("bearing $b", listOf(AlertKind.FIRST), events.kinds())
        }
        for (b in listOf(46f, 314f, 90f, 180f)) {
            val (verdict, events) = judge(camera(bearing = b))
            assertEquals("bearing $b", Verdict.WRONG_DIRECTION, verdict)
            assertTrue("bearing $b", events.isEmpty())
        }
    }

    @Test
    fun bothCoversTheBearingAndItsOppositeWithTheSame45Degrees() {
        // BOTH at 44° and 224° (its opposite) pass; 46° and 226° fail: the tolerance is the same both ways.
        for (b in listOf(44f, 224f, 45f, 225f, 135f, 315f)) {
            val (verdict, _) = judge(camera(dir = DirectionType.BOTH, bearing = b))
            assertEquals("BOTH bearing $b", Verdict.AHEAD_OK, verdict)
        }
        for (b in listOf(46f, 226f, 134f, 314f, 90f)) {
            val (verdict, events) = judge(camera(dir = DirectionType.BOTH, bearing = b))
            assertEquals("BOTH bearing $b", Verdict.WRONG_DIRECTION, verdict)
            assertTrue(events.isEmpty())
        }
        // The same bearings as ONE_WAY: only the side facing us passes.
        assertEquals(Verdict.WRONG_DIRECTION, judge(camera(bearing = 224f)).first)
        assertEquals(Verdict.WRONG_DIRECTION, judge(camera(bearing = 135f)).first)
    }

    @Test
    fun toleranceConfigCanBeLoweredButNeverRaisedAbove45() {
        // The hard rule: Config.directionToleranceDeg above 45° is applied as 45°.
        val loose = AlertEngine.Config(directionToleranceDeg = 80.0)
        assertEquals(Verdict.WRONG_DIRECTION, judge(camera(bearing = 60f), config = loose).first)
        assertEquals(Verdict.AHEAD_OK, judge(camera(bearing = 45f), config = loose).first)
        // Lowering it is allowed and applied.
        val tight = AlertEngine.Config(directionToleranceDeg = 30.0)
        assertEquals(Verdict.WRONG_DIRECTION, judge(camera(bearing = 40f), config = tight).first)
        assertEquals(Verdict.AHEAD_OK, judge(camera(bearing = 30f), config = tight).first)
    }

    @Test
    fun betaLegOnlyWhileTheCameraIsProperlyAhead() {
        // A camera 40° off our heading (properly ahead) stored as 80°: 80° off the heading but 40° off the
        // bearing to it, so the β leg passes (§11 step 3, owner's amendment).
        val d = 500.0
        val at40 = camera(northM = d * cos(Math.toRadians(40.0)), eastM = d * sin(Math.toRadians(40.0)), bearing = 80f)
        assertEquals(Verdict.AHEAD_OK, judge(at40).first)
        // 50° off our heading isn't ahead (step 2), whatever its bearing says.
        val at50 = camera(northM = d * cos(Math.toRadians(50.0)), eastM = d * sin(Math.toRadians(50.0)), bearing = 50f)
        assertEquals(Verdict.NOT_AHEAD, judge(at50).first)
        // Under 15 m the camera still counts as ahead (step 2), but the β leg is off there (as built,
        // 2026-10-13): 60° off the heading at 10 m, stored as 60° (equal to β), is judged against the heading alone.
        val close = camera(northM = 10.0 * cos(Math.toRadians(60.0)), eastM = 10.0 * sin(Math.toRadians(60.0)), bearing = 60f)
        assertEquals(Verdict.WRONG_DIRECTION, judge(close).first)
        // The same spot with a bearing along our heading: ahead, on the road, and so close that it is PASSING.
        val closeOurs = close.copy(bearing = 0f)
        val (verdict, events) = judge(closeOurs)
        assertEquals(Verdict.AHEAD_OK, verdict)
        assertEquals(listOf(AlertKind.PASSING), events.kinds())
    }

    @Test
    fun anyDirectionHasNoRuleToFailAndIsNeverDimmed() {
        // DirectionType.ANY with no bearing: the rule passes and nothing can dim it.
        val engine = AlertEngine()
        val any = camera(dir = DirectionType.ANY, bearing = null)
        assertEquals(listOf(AlertKind.FIRST), engine.onFix(fix(0.0, acc = 5.0), listOf(any), ON_ROUTE).kinds())
        assertTrue(engine.facingAway().isEmpty())
        // As built, the engine judges by directionType alone: an AlertPoi marked ANY that still carries a
        // bearing passes and is never dimmed. The invariant "a bearing is never ANY" is kept upstream, by
        // the importers' importRow (see the importer tests); nothing in the app builds ANY with a bearing.
        val anyWithBearing = camera(dir = DirectionType.ANY, bearing = 180f)
        val e2 = AlertEngine()
        assertEquals(listOf(AlertKind.FIRST), e2.onFix(fix(0.0, acc = 5.0), listOf(anyWithBearing), ON_ROUTE).kinds())
        assertTrue(e2.facingAway().isEmpty())
    }

    @Test
    fun facingAwayNeedsMoreThan45PlusTheClampedHeadingAccuracy() {
        // The confidence margin is the heading accuracy clamped to 5–20°, 10° when unknown (§8.1).
        fun dimmedWith(bearing: Float, acc: Double?): Boolean {
            val engine = AlertEngine()
            engine.onFix(fix(0.0, acc = acc), listOf(camera(bearing = bearing)), ON_ROUTE)
            assertEquals(Verdict.WRONG_DIRECTION, engine.classify()[1L])
            return 1L in engine.facingAway()
        }
        // Unknown accuracy: 10°, so dimming starts beyond 55°.
        assertTrue(!dimmedWith(55f, null))
        assertTrue(dimmedWith(56f, null))
        // 30° reported is clamped to 20°: beyond 65°.
        assertTrue(!dimmedWith(65f, 30.0))
        assertTrue(dimmedWith(66f, 30.0))
        // 1° reported is clamped to 5°: beyond 50°.
        assertTrue(!dimmedWith(50f, 1.0))
        assertTrue(dimmedWith(51f, 1.0))
    }

    @Test
    fun dimmedCameraComesBackOnceTheRulePassesOrItIsBehind() {
        val engine = AlertEngine()
        val cam = camera(bearing = 60f)
        engine.onFix(fix(0.0, heading = 0.0, acc = 5.0, t = 0), listOf(cam), ON_ROUTE)
        assertEquals(setOf(1L), engine.facingAway())
        // Heading 14°: the camera is 46° off, still outside the rule: stays dimmed (hysteresis).
        engine.onFix(fix(0.0, heading = 14.0, acc = 5.0, t = 200), listOf(cam), ON_ROUTE)
        assertEquals(setOf(1L), engine.facingAway())
        assertEquals(Verdict.WRONG_DIRECTION, engine.classify()[1L])
        // Heading 15°: 45° off, the rule passes again: undimmed and alerting.
        val events = engine.onFix(fix(0.0, heading = 15.0, acc = 5.0, t = 400), listOf(cam), ON_ROUTE)
        assertTrue(engine.facingAway().isEmpty())
        assertEquals(listOf(AlertKind.FIRST), events.kinds())
        // A dimmed camera that falls behind is undimmed too: dimming is for cameras ahead on our road.
        val passed = AlertEngine()
        passed.onFix(fix(0.0, acc = 5.0, t = 0), listOf(camera(bearing = 180f)), ON_ROUTE)
        assertEquals(setOf(1L), passed.facingAway())
        passed.onFix(fix(560.0, acc = 5.0, t = 200), listOf(camera(bearing = 180f)), ON_ROUTE)
        assertEquals(Verdict.BEHIND, passed.classify()[1L])
        assertTrue(passed.facingAway().isEmpty())
    }

    @Test
    fun dimmingIsNeverStartedOffOurRoadOrOutOfRange() {
        // The same opposite-facing camera 200 m off the route is OFF_ROAD, not dimmed (§8.1: on our road only).
        val engine = AlertEngine()
        engine.onFix(fix(0.0, acc = 5.0), listOf(camera(bearing = 180f)), RouteMatcher { _, _ -> 200.0 })
        assertEquals(Verdict.OFF_ROAD, engine.classify()[1L])
        assertTrue(engine.facingAway().isEmpty())
        // Beyond 3 km nothing is judged at all.
        val far = AlertEngine()
        far.onFix(fix(0.0, acc = 5.0), listOf(camera(northM = 3_100.0, bearing = 180f)), ON_ROUTE)
        assertEquals(Verdict.OUT_OF_RANGE, far.classify()[1L])
        assertTrue(far.facingAway().isEmpty())
    }

    @Test
    fun aDimmedCameraNeverProducesAStageOnTheWayPast() {
        val trace = object : AlertTrace {
            val verdicts = ArrayList<Verdict>()
            val stages = ArrayList<AlertEvent>()
            override fun verdict(poiId: Long, verdict: Verdict, d: Double, offAxisDeg: Double, headingDeg: Double?, poiBearing: Float?, dirType: DirectionType, lateralM: Double, allowedM: Double) { verdicts += verdict }
            override fun stage(event: AlertEvent) { stages += event }
            override fun cleared(poiId: Long, reason: ClearReason) {}
        }
        val engine = AlertEngine(trace = trace)
        val cam = camera(northM = 1_000.0, bearing = 200f) // 160° off: for the other carriageway
        var dimmedOnEveryApproachFix = true
        val events = StraightRoad.drive(engine, listOf(cam), 0.0, 1_400.0) { f, _ ->
            val d = NoisyTrack.distance(f, cam)
            if (d in 30.0..900.0 && engine.classify()[1L] == Verdict.WRONG_DIRECTION && 1L !in engine.facingAway()) dimmedOnEveryApproachFix = false
        }
        assertTrue(events.isEmpty())
        assertTrue(trace.stages.isEmpty())
        assertTrue(dimmedOnEveryApproachFix)
        assertTrue(Verdict.AHEAD_OK !in trace.verdicts)
        assertEquals(Verdict.BEHIND, engine.classify()[1L])
    }

    @Test
    fun mobileSitesGet100mOfSideAllowanceOnARouteAndInFreeDrive() {
        // On a route: a fixed camera 70 m from it is another road; a mobile site there is ours.
        val route70 = RouteMatcher { _, _ -> 70.0 }
        assertEquals(Verdict.OFF_ROAD, judge(camera(), route = route70).first)
        val (mobileVerdict, mobileEvents) = judge(camera(sideToleranceM = 100.0), route = route70)
        assertEquals(Verdict.AHEAD_OK, mobileVerdict)
        assertEquals(listOf(AlertKind.FIRST), mobileEvents.kinds())
        // 110 m is beyond even the mobile allowance.
        assertEquals(Verdict.OFF_ROAD, judge(camera(sideToleranceM = 100.0), route = RouteMatcher { _, _ -> 110.0 }).first)
        // Free drive at 300 m: the corridor is max(40, 300·sin 12°) = 62 m, so 70 m to the side is off road
        // for a fixed camera and inside the 100 m of a mobile site.
        assertEquals(Verdict.OFF_ROAD, judge(camera(northM = 300.0, eastM = 70.0), route = null).first)
        assertEquals(Verdict.AHEAD_OK, judge(camera(northM = 300.0, eastM = 70.0, sideToleranceM = 100.0), route = null).first)
        // A smaller tolerance than the engine's own never shrinks it: 10 m is still judged at 40/50 m.
        assertEquals(Verdict.AHEAD_OK, judge(camera(northM = 300.0, eastM = 35.0, sideToleranceM = 10.0), route = null).first)
        // The direction rule is untouched by the allowance.
        assertEquals(Verdict.WRONG_DIRECTION, judge(camera(bearing = 180f, sideToleranceM = 100.0), route = route70).first)
    }

    @Test
    fun trackedLatchKeepsTheCorridorButNotTheDirection() {
        val engine = AlertEngine()
        val cam = camera(northM = 400.0)
        assertEquals(listOf(AlertKind.FIRST), engine.onFix(fix(0.0, t = 0), listOf(cam), ON_ROUTE).kinds())
        // A 60° heading blip on a route: the camera is no longer "ahead" by step 2, but the latch keeps it on
        // the road; the direction rule is judged on every fix, so it reads WRONG_DIRECTION, never NOT_AHEAD.
        engine.onFix(fix(0.0, heading = 60.0, t = 200), listOf(cam), ON_ROUTE)
        assertEquals(Verdict.WRONG_DIRECTION, engine.classify()[1L])
        // Back on heading: still tracked, SECOND fires in due course.
        assertEquals(listOf(AlertKind.SECOND), engine.onFix(fix(150.0, heading = 0.0, t = 400), listOf(cam), ON_ROUTE).kinds())
        // A heading off by more than 90° releases the latch: the camera is then "behind".
        engine.onFix(fix(150.0, heading = 100.0, t = 600), listOf(cam), ON_ROUTE)
        assertEquals(Verdict.BEHIND, engine.classify()[1L])
    }

    @Test
    fun noHeadingUntilTheCarMovesAbove2mps() {
        // The heading is only taken while moving (> 2 m/s), so a parked first fix with a heading judges nothing.
        val engine = AlertEngine()
        val cam = camera()
        assertTrue(engine.onFix(fix(0.0, mps = 0.0, heading = 0.0, t = 0), listOf(cam)).isEmpty())
        assertEquals(Verdict.NO_HEADING, engine.classify()[1L])
        assertTrue(engine.onFix(fix(0.0, mps = 2.0, heading = 0.0, t = 200), listOf(cam)).isEmpty())
        assertEquals(Verdict.NO_HEADING, engine.classify()[1L])
        assertEquals(listOf(AlertKind.FIRST), engine.onFix(fix(0.0, mps = 2.1, heading = 0.0, t = 400), listOf(cam)).kinds())
        // Moving has hysteresis: 1.5 m/s is still moving once it was, 0.9 m/s is not (the heading is held).
        engine.onFix(fix(0.0, mps = 1.5, heading = 0.0, t = 600), listOf(cam))
        assertEquals(Verdict.AHEAD_OK, engine.classify()[1L])
        engine.onFix(fix(0.0, mps = 0.9, heading = 0.0, t = 800), listOf(cam))
        assertEquals(Verdict.NOT_MOVING, engine.classify()[1L])
        engine.onFix(fix(0.0, mps = 1.5, heading = 0.0, t = 1_000), listOf(cam))
        assertEquals(Verdict.NOT_MOVING, engine.classify()[1L])
    }
}

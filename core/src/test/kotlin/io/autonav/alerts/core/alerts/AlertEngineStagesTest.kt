// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.alerts.StraightRoad.MPS_30
import io.autonav.alerts.core.alerts.StraightRoad.camera
import io.autonav.alerts.core.alerts.StraightRoad.drive
import io.autonav.alerts.core.alerts.StraightRoad.fix
import io.autonav.alerts.core.alerts.StraightRoad.kinds
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three stages and their state (DESIGN.md §11 steps 5–7, §11.1, §12.3): the trigger maths of
 * [AlertStage], the ratchet rising with speed, the 2nd stage held 100 m inside the 1st and never
 * under 142 m, the passing alert at 1 s / 20 yd, the missed-passing net's limits, which stage is
 * spoken when several are crossed at once, the stale-fired rule, the 2 km clear, candidate order and
 * [AlertEngine.reset].
 */
class AlertEngineStagesTest {
    private val mps70 = 70.0 * Geo.MPS_PER_MPH

    private class Recording : AlertTrace {
        val cleared = ArrayList<Pair<Long, ClearReason>>()
        override fun verdict(poiId: Long, verdict: Verdict, d: Double, offAxisDeg: Double, headingDeg: Double?, poiBearing: Float?, dirType: DirectionType, lateralM: Double, allowedM: Double) {}
        override fun stage(event: AlertEvent) {}
        override fun cleared(poiId: Long, reason: ClearReason) { cleared += poiId to reason }
    }

    @Test
    fun stageTriggerIsTheFloorOrSecondsAtSpeed() {
        // §11.1 "Trigger distance": FIXED is the distance; TIME is max(floor, v × seconds) on every fix.
        assertEquals(469.39, AlertStage.DEFAULT_FIRST.triggerMetres(mps70), 0.01)
        assertEquals(234.70, AlertStage.DEFAULT_SECOND.triggerMetres(mps70), 0.01)
        assertEquals(242.0, AlertStage.DEFAULT_FIRST.triggerMetres(MPS_30), 0.0) // 201 m lifted to the floor
        assertEquals(142.0, AlertStage.DEFAULT_SECOND.triggerMetres(MPS_30), 0.0)
        assertEquals(15.0, AlertStage.DEFAULT_FIRST.seconds)
        assertEquals(7.5, AlertStage.DEFAULT_SECOND.seconds)
        assertEquals(800.0, AlertStage(true, DistanceMode.FIXED, 800, 60.0).triggerMetres(mps70), 0.0)
        // TIME with no seconds is just its floor; the Int constructor keeps whole seconds.
        assertEquals(300.0, AlertStage(true, DistanceMode.TIME, 300, null as Double?).triggerMetres(mps70), 0.0)
        assertEquals(AlertStage(true, DistanceMode.TIME, 242, 15.0), AlertStage(true, DistanceMode.TIME, 242, 15))
    }

    @Test
    fun ratchetRisesWithSpeedAndNeverFalls() {
        val engine = AlertEngine()
        val cam = camera(northM = 400.0, first = AlertStage.DEFAULT_FIRST, second = AlertStage.DEFAULT_SECOND)
        // 30 mph, 400 m out: inside the 442 m lookahead, the 1st trigger is the 242 m floor: nothing yet.
        assertTrue(engine.onFix(fix(0.0, MPS_30, t = 0), listOf(cam)).isEmpty())
        // Accelerating to 70 mph lifts the 1st to 469 m, and the car at 380 m is already inside it.
        val first = engine.onFix(fix(20.0, mps70, t = 1_000), listOf(cam)).single()
        assertEquals(AlertKind.FIRST, first.kind)
        assertEquals(469.39, first.triggerM, 0.01)
        assertEquals(234.70, first.staleBelowM!!, 0.01)
        // Braking back to 30 mph: the 2nd stays at its 70 mph distance, not the 142 m floor.
        val second = engine.onFix(fix(170.0, MPS_30, t = 2_000), listOf(cam)).single()
        assertEquals(AlertKind.SECOND, second.kind)
        assertEquals(234.70, second.triggerM, 0.01)
        assertEquals(230.0, second.distanceM, 0.5)
    }

    @Test
    fun secondStageIsHeld100mInsideTheFirstAndNeverUnder142m() {
        // FIXED 300 / 250: the 2nd is clamped to 200 m (§11.1 validation, applied at runtime).
        val close = camera(first = AlertStage(true, DistanceMode.FIXED, 300), second = AlertStage(true, DistanceMode.FIXED, 250))
        val events = drive(AlertEngine(), listOf(close), 0.0, 520.0)
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), events.kinds())
        assertEquals(300.0, events[0].triggerM, 0.0)
        assertEquals(200.0, events[0].staleBelowM!!, 0.0)
        assertEquals(200.0, events[1].triggerM, 0.0)
        assertTrue("SECOND at ${events[1].distanceM}", events[1].distanceM <= 200.0 && events[1].distanceM > 197.0)
        assertEquals(AlertEngine.PASSING_METRES, events[1].staleBelowM!!, 0.0)
        // FIXED 200 / 150: 100 m inside the 1st would be 100 m; the 142 m floor wins.
        val tight = camera(first = AlertStage(true, DistanceMode.FIXED, 200), second = AlertStage(true, DistanceMode.FIXED, 150))
        val tightEvents = drive(AlertEngine(), listOf(tight), 0.0, 520.0)
        assertEquals(listOf(AlertKind.FIRST, AlertKind.SECOND, AlertKind.PASSING), tightEvents.kinds())
        assertEquals(AlertEngine.SECOND_FLOOR_M, tightEvents[0].staleBelowM!!, 0.0)
        assertEquals(AlertEngine.SECOND_FLOOR_M, tightEvents[1].triggerM, 0.0)
        assertTrue(tightEvents[1].distanceM <= 142.0)
    }

    @Test
    fun passingIsOneSecondAheadNeverUnderTwentyYards() {
        fun passingAt(dM: Double, mps: Double): AlertEvent = AlertEngine().onFix(fix(500.0 - dM, mps), listOf(camera())).single()
        // 55 mph: 24.6 m a second, so 20 m out is already "now".
        val at55 = passingAt(20.0, 55.0 * Geo.MPS_PER_MPH)
        assertEquals(AlertKind.PASSING, at55.kind)
        assertEquals(55.0 * Geo.MPS_PER_MPH, at55.triggerM, 1e-9)
        assertEquals(100, at55.priority)
        assertNull(at55.staleBelowM)
        // 30 mph: 1 s is 13.4 m, under the floor, so 20 m out is still the 2nd stage.
        assertEquals(AlertKind.SECOND, passingAt(20.0, MPS_30).kind)
        // 41 mph is the first speed whose second beats the floor: 18.30 m out passes at 41, not at 40.
        assertEquals(AlertKind.PASSING, passingAt(18.3, 41.0 * Geo.MPS_PER_MPH).kind)
        assertEquals(41.0 * Geo.MPS_PER_MPH, passingAt(18.3, 41.0 * Geo.MPS_PER_MPH).triggerM, 1e-9)
        assertEquals(AlertKind.SECOND, passingAt(18.3, 40.0 * Geo.MPS_PER_MPH).kind)
        assertEquals(AlertEngine.PASSING_METRES, passingAt(18.0, 40.0 * Geo.MPS_PER_MPH).triggerM, 0.0)
    }

    @Test
    fun passingNeedsHalfAMetrePerSecondOnceTracked() {
        val engine = AlertEngine()
        val cam = camera()
        assertEquals(listOf(AlertKind.SECOND), engine.onFix(fix(300.0, t = 0), listOf(cam)).kinds())
        // 10 m short at 0.4 m/s (the heading is held): not moving, and too slow for the passing alert.
        assertTrue(engine.onFix(fix(490.0, mps = 0.4, heading = null, t = 200), listOf(cam)).isEmpty())
        assertEquals(Verdict.NOT_MOVING, engine.classify()[1L])
        // 0.5 m/s is enough (§11 step 6).
        val events = engine.onFix(fix(490.0, mps = 0.5, heading = null, t = 400), listOf(cam))
        assertEquals(listOf(AlertKind.PASSING), events.kinds())
        assertEquals(10.0, events[0].distanceM, 0.5)
    }

    @Test
    fun arrivingOnTopOfACameraSpeaksOnlyPassingAndMarksTheRest() {
        val engine = AlertEngine()
        val cam = camera()
        // First fix 10 m short: all three thresholds crossed at once, only PASSING is spoken.
        assertEquals(listOf(AlertKind.PASSING), engine.onFix(fix(490.0, t = 0), listOf(cam)).kinds())
        // The skipped stages are marked fired: nothing more on the way past.
        for ((i, pos) in listOf(495.0, 505.0, 560.0, 620.0).withIndex()) {
            assertTrue("at $pos", engine.onFix(fix(pos, t = 200L * (i + 1)), listOf(cam)).isEmpty())
        }
    }

    @Test
    fun missedPassingNetOnlyWithin150mAndOnlyWithSoundOn() {
        // Tracked, then a jump to 200 m behind: too far for the net; the approach is cleared as passed
        // (more than 100 m beyond its closest point, §11 step 7) and nothing is said.
        val far = AlertEngine()
        assertEquals(listOf(AlertKind.SECOND), far.onFix(fix(400.0, t = 0), listOf(camera())).kinds())
        assertTrue(far.onFix(fix(700.0, t = 1_000), listOf(camera())).isEmpty())
        // 30 m behind: the net fires PASSING at the fixed 20 yd trigger, with the distance behind.
        val near = AlertEngine()
        near.onFix(fix(400.0, t = 0), listOf(camera()))
        val net = near.onFix(fix(530.0, t = 1_000), listOf(camera())).single()
        assertEquals(AlertKind.PASSING, net.kind)
        assertEquals(30.0, net.distanceM, 0.5)
        assertEquals(AlertEngine.PASSING_METRES, net.triggerM, 0.0)
        assertEquals(Verdict.BEHIND, near.classify()[1L])
        // Sound off: no passing alert, from the net or otherwise.
        val silent = AlertEngine()
        val quiet = camera(sound = false)
        assertEquals(listOf(AlertKind.SECOND), silent.onFix(fix(400.0, t = 0), listOf(quiet)).kinds())
        assertTrue(silent.onFix(fix(530.0, t = 1_000), listOf(quiet)).isEmpty())
        // A camera never tracked (facing the other way) gets nothing from the net either.
        val wrong = AlertEngine()
        wrong.onFix(fix(400.0, t = 0), listOf(camera(bearing = 180f)))
        assertTrue(wrong.onFix(fix(530.0, t = 1_000), listOf(camera(bearing = 180f))).isEmpty())
    }

    @Test
    fun firstGoesStaleAtThePassingDistanceWhenTheSecondIsOff() {
        val noSecond = camera(second = AlertStage(false, DistanceMode.FIXED, 300))
        val first = AlertEngine().onFix(fix(100.0, mps70), listOf(noSecond)).single()
        assertEquals(AlertKind.FIRST, first.kind)
        assertEquals(mps70, first.staleBelowM!!, 1e-9) // 1 s at 70 mph, not the 20 yd floor
        assertEquals(10, first.priority)
        // A SECOND is stale at the passing distance too.
        val second = AlertEngine().onFix(fix(250.0, mps70), listOf(camera())).single()
        assertEquals(AlertKind.SECOND, second.kind)
        assertEquals(mps70, second.staleBelowM!!, 1e-9)
        assertEquals(50, second.priority)
    }

    @Test
    fun overspeedIsJudgedJustBelowTheLimitByDefault() {
        val limit = 30.0 * Geo.MPS_PER_MPH
        fun over(mps: Double, limitMph: Int? = 30) = AlertEngine().onFix(fix(100.0, mps), listOf(camera(limitMph = limitMph))).single().overspeed
        // −0.1%: exactly the limit is already over; 99.8% of it is not (§11 step 9).
        assertTrue(over(limit))
        assertFalse(over(limit * 0.998))
        // No limit: never over.
        assertFalse(over(limit * 2, limitMph = null))
        // The slider's +20% end: 35 mph in a 30 is fine, 36.1 is not.
        val lenient = AlertEngine().apply { overspeedPct = 0.2 }
        assertFalse(lenient.onFix(fix(100.0, 35.0 * Geo.MPS_PER_MPH), listOf(camera())).single().overspeed)
        assertTrue(AlertEngine().apply { overspeedPct = 0.2 }.onFix(fix(100.0, 36.1 * Geo.MPS_PER_MPH), listOf(camera())).single().overspeed)
    }

    @Test
    fun aFiredStageStillBeingApproachedIsNotForgottenAfter90s() {
        val trace = Recording()
        val engine = AlertEngine(trace = trace)
        val cam = camera(northM = 1_000.0)
        assertEquals(listOf(AlertKind.FIRST), engine.onFix(fix(300.0, t = 0), listOf(cam)).kinds()) // fired at 700 m
        // Stopped 50 m closer (inside firedAtD − 30 m): an approach, so the state is kept however long we wait.
        var t = 1_000L
        while (t <= 100_000L) { assertTrue(engine.onFix(fix(350.0, mps = 0.0, heading = null, t = t), listOf(cam)).isEmpty()); t += 1_000 }
        assertTrue(trace.cleared.isEmpty())
        val after = drive(engine, listOf(cam), 350.0, 1_100.0, startMs = 110_000)
        assertEquals(listOf(AlertKind.SECOND, AlertKind.PASSING), after.kinds())
    }

    @Test
    fun twoKilometresBehindClearsTheState() {
        val trace = Recording()
        val engine = AlertEngine(trace = trace)
        val cam = camera(northM = 2_500.0, first = AlertStage(true, DistanceMode.FIXED, 2_800))
        assertEquals(listOf(AlertKind.FIRST), engine.onFix(fix(0.0, t = 0), listOf(cam)).kinds())
        // A jump to 2.1 km behind without ever passing it: cleared as BEHIND (§11 step 7).
        assertTrue(engine.onFix(fix(4_600.0, t = 1_000), listOf(cam)).isEmpty())
        assertEquals(listOf(1L to ClearReason.BEHIND), trace.cleared)
    }

    @Test
    fun eventsComeInCandidateOrderAndForgetExceptKeepsTheListed() {
        val engine = AlertEngine()
        val cams = listOf(camera(id = 7, northM = 600.0), camera(id = 3, northM = 400.0))
        assertEquals(listOf(7L, 3L), engine.onFix(fix(0.0), cams).map { it.poiId })
        engine.forgetExcept(setOf(3L))
        assertNull(engine.classify()[7L])
        assertEquals(Verdict.AHEAD_OK, engine.classify()[3L])
        // The forgotten camera alerts again, the kept one doesn't.
        assertEquals(listOf(7L), engine.onFix(fix(0.0, t = 200), cams).map { it.poiId })
    }

    @Test
    fun resetForgetsApproachesAndTheHeading() {
        val engine = AlertEngine()
        val cam = camera()
        assertEquals(listOf(AlertKind.FIRST), engine.onFix(fix(0.0, t = 0), listOf(cam)).kinds())
        assertTrue(engine.onFix(fix(0.0, t = 200), listOf(cam)).isEmpty())
        engine.reset()
        assertEquals(listOf(AlertKind.FIRST), engine.onFix(fix(0.0, t = 400), listOf(cam)).kinds())
        // The held heading goes too: a stationary fix right after a reset judges nothing.
        engine.reset()
        engine.onFix(fix(0.0, mps = 0.0, heading = null, t = 600), listOf(cam))
        assertEquals(Verdict.NO_HEADING, engine.classify()[1L])
    }
}

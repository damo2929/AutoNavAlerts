// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.location

import io.autonav.alerts.core.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic feeds near 53°N: the car moves in metres north/east of the origin (DESIGN.md §10.2, §10.6a). */
class FixEstimatorTest {
    private val baseLat = 53.0
    private val metresPerDegLat = 111_195.0
    private fun latAt(northM: Double) = baseLat + northM / metresPerDegLat
    private fun lonAt(eastM: Double) = eastM / (metresPerDegLat * cos(Math.toRadians(baseLat)))

    /**
     * Position noise: gaussian with [sigma], and with [rho] > 0 an AR(1) wander (GPS jitter at 10 Hz
     * drifts slowly rather than jumping about; ρ = 0.95 is a correlation time of about 2 s).
     */
    private class Gen(seed: Long, val rho: Double = 0.0) {
        val rnd = Random(seed)
        private var n = 0.0
        private var e = 0.0
        fun next(sigma: Double): Pair<Double, Double> {
            if (sigma == 0.0) return 0.0 to 0.0
            val k = kotlin.math.sqrt(1 - rho * rho)
            n = rho * n + k * rnd.nextGaussian() * sigma
            e = rho * e + k * rnd.nextGaussian() * sigma
            return n to e
        }
    }

    /** Fixes at [hz] for [seconds], at [path](t) = (north, east) metres, with position noise from [gen]. */
    private fun feed(
        seconds: Double, hz: Int = 10, sigmaM: Double = 0.0, accM: Double = 5.0, startMs: Long = 0,
        reportedSpeed: ((Double) -> Double?)? = null, bearing: ((Double) -> Double?)? = null, bearingAcc: Double? = null,
        source: LocationSource = LocationSource.PHONE, gen: Gen = Gen(1),
        path: (Double) -> Pair<Double, Double>,
    ): List<FixInput> {
        val out = ArrayList<FixInput>()
        val n = (seconds * hz).toInt()
        for (i in 0..n) {
            val t = i.toDouble() / hz
            val (nM, eM) = path(t)
            val (nj, ej) = gen.next(sigmaM)
            out += FixInput(
                lat = latAt(nM + nj), lon = lonAt(eM + ej),
                timeMs = startMs + (t * 1000).toLong(), accuracyM = accM,
                speedMps = reportedSpeed?.invoke(t), bearingDeg = bearing?.invoke(t), bearingAccuracyDeg = bearingAcc, source = source,
            )
        }
        return out
    }

    private fun north(mps: Double): (Double) -> Pair<Double, Double> = { t -> (mps * t) to 0.0 }

    private fun run(est: FixEstimator, fixes: List<FixInput>) = fixes.map { est.onFix(it) }

    @Test
    fun parkedJitterAtTenHzGivesZeroSpeedAndNoHeading() {
        // Five minutes parked, slowly wandering jitter (σ 1.5 m, 5 m accuracy), no reported speed.
        // A GPS wander with a correlation time of 5 s or more (ρ ≥ 0.98 at 10 Hz) never reads as motion.
        for (rho in listOf(0.98, 0.99)) for (seed in 1L..5L) {
            val fixes = feed(300.0, sigmaM = 1.5, accM = 5.0, gen = Gen(seed, rho = rho)) { 0.0 to 0.0 }
            val out = run(FixEstimator(), fixes)
            assertTrue("rho $rho seed $seed: max speed ${out.maxOf { it.speedMps }}", out.all { it.speedMps < 0.5 })
            assertTrue(out.all { it.headingDeg == null && it.headingSource == HeadingSource.NONE })
        }
        // A wander as fast as 2 s against a 5 m accuracy is the rule's limit (max(3 m, accuracy) over
        // ≥ 1 s, DESIGN §10.2): at most a brief blip under 3 m/s, never a heading, and 0 for 95% of fixes.
        for (seed in 1L..5L) {
            val out = run(FixEstimator(), feed(300.0, sigmaM = 1.5, accM = 5.0, gen = Gen(seed, rho = 0.95)) { 0.0 to 0.0 })
            assertTrue("seed $seed: max speed ${out.maxOf { it.speedMps }}", out.all { it.speedMps < 3.0 })
            val still = out.count { it.speedMps < 0.5 }
            assertTrue("seed $seed: $still of ${out.size} fixes read as still", still >= out.size * 0.95)
            assertTrue(out.all { it.headingDeg == null })
        }
        // Even white noise at that level, which no GPS produces (the old failure: 1 m of jitter read
        // as 22 mph on every fix), is bounded the same way.
        val white = run(FixEstimator(), feed(60.0, sigmaM = 1.5, accM = 5.0) { 0.0 to 0.0 })
        assertTrue("max speed ${white.maxOf { it.speedMps }}", white.all { it.speedMps < 3.0 })
        assertTrue(white.all { it.headingDeg == null })
    }

    @Test
    fun reportedZeroStaysZero() {
        val fixes = feed(10.0, sigmaM = 2.0, reportedSpeed = { 0.0 }) { 0.0 to 0.0 }
        val out = run(FixEstimator(), fixes)
        assertTrue(out.all { it.speedMps == 0.0 && it.speedSource == SpeedSource.GPS })
        assertTrue(out.all { it.headingDeg == null })
    }

    @Test
    fun reportedSpeedIsTheMedianOfThree() {
        val est = FixEstimator()
        val f = { t: Long, v: Double -> FixInput(baseLat, 0.0, t, 5.0, speedMps = v) }
        assertEquals(10.0, est.onFix(f(0, 10.0)).speedMps, 0.0)
        assertEquals(10.0, est.onFix(f(100, 10.0)).speedMps, 0.0)
        assertEquals(10.0, est.onFix(f(200, 0.0)).speedMps, 0.0) // one dropout
        assertEquals(0.0, est.onFix(f(300, 0.0)).speedMps, 0.0) // a real stop
    }

    @Test
    fun displacementSpeedOnlyWithoutAReportedSpeed() {
        val fixes = feed(10.0, sigmaM = 0.5, accM = 3.0, path = north(10.0))
        val out = run(FixEstimator(), fixes)
        val settled = out.filter { it.timeMs >= 3_000 }
        assertTrue(settled.all { it.speedSource == SpeedSource.DISPLACEMENT })
        assertTrue(settled.all { abs(it.speedMps - 10.0) < 1.5 })
        // A crawl of 0.5 m/s is below the 3 m-per-second floor: it reads as (nearly) 0, never as jitter speed.
        val crawl = run(FixEstimator(), feed(20.0, accM = 3.0, path = north(0.5)))
        assertTrue(crawl.all { it.speedMps < 1.0 })
    }

    @Test
    fun chordErrorBoundOnAStraightRoad() {
        // 20 m/s → 40 m chord; 3 m fixes → atan2(6, 40) ≈ 8.5°.
        val fixes = feed(30.0, sigmaM = 1.5, accM = 3.0, reportedSpeed = { 20.0 }, path = north(20.0))
        val out = run(FixEstimator(), fixes)
        val settled = out.filter { it.timeMs >= 5_000 }
        assertTrue(settled.all { it.headingSource == HeadingSource.TRACK })
        val expectedAcc = Math.toDegrees(kotlin.math.atan2(6.0, 40.0))
        assertEquals(expectedAcc, settled.map { it.headingAccuracyDeg!! }.average(), 0.5)
        val within = settled.count { Geo.absAngleDiffDeg(it.headingDeg!!, 0.0) <= it.headingAccuracyDeg!! }
        assertTrue("$within of ${settled.size} within the accuracy", within >= settled.size * 0.95)
    }

    @Test
    fun gpsBearingBlendedOnlyWhenItAgreesWithTheTrack() {
        val est = FixEstimator()
        run(est, feed(10.0, accM = 3.0, reportedSpeed = { 20.0 }, path = north(20.0)))
        // Agreeing (5° off the track, 5° accuracy): blended, and pulled towards the GPS (it is the more accurate).
        val agree = run(est, feed(5.0, accM = 3.0, startMs = 10_100, reportedSpeed = { 20.0 }, bearing = { 5.0 + it }, bearingAcc = 5.0) { t -> (200.0 + 20.0 * t) to 0.0 })
        assertTrue(agree.all { it.headingSource == HeadingSource.BLEND })
        val h = agree.last().headingDeg!!
        assertTrue("blended heading $h", h > 2.0 && h < 10.0)
        assertTrue(agree.last().headingAccuracyDeg!! < 5.0)
        // Disagreeing (90° off): the track alone.
        val off = run(est, feed(3.0, accM = 3.0, startMs = 15_200, reportedSpeed = { 20.0 }, bearing = { 90.0 + it }, bearingAcc = 5.0) { t -> (300.0 + 20.0 * t) to 0.0 })
        assertTrue(off.all { it.headingSource == HeadingSource.TRACK && it.gpsBearingDeg == null })
        assertTrue(Geo.absAngleDiffDeg(off.last().headingDeg!!, 0.0) < 3.0)
        // Too slow (2 m/s) or too inaccurate (25°): not used either.
        val slow = FixEstimator().let { e -> run(e, feed(10.0, accM = 3.0, reportedSpeed = { 2.0 }, bearing = { 1.0 + it }, bearingAcc = 5.0, path = north(2.0))) }
        assertTrue(slow.none { it.headingSource == HeadingSource.BLEND || it.headingSource == HeadingSource.GPS })
        val rough = FixEstimator().let { e -> run(e, feed(10.0, accM = 3.0, reportedSpeed = { 20.0 }, bearing = { 1.0 + it }, bearingAcc = 25.0, path = north(20.0))) }
        assertTrue(rough.none { it.headingSource == HeadingSource.BLEND || it.headingSource == HeadingSource.GPS })
    }

    @Test
    fun stuckBearingIsIgnoredForTheRestOfTheSession() {
        // The emulator: bearing 0.0 on every fix while the car goes round a curve.
        val r = 200.0
        val v = 15.0
        val est = FixEstimator()
        val curve = feed(40.0, accM = 3.0, reportedSpeed = { v }, bearing = { 0.0 }) { t ->
            val a = v * t / r
            (r * sin(a)) to (r * (1 - cos(a)))
        }
        val out = run(est, curve)
        assertTrue(est.gpsBearingStale)
        val after = out.dropWhile { !it.gpsBearingStale }
        assertTrue(after.isNotEmpty())
        assertTrue(after.all { it.gpsBearingDeg == null && it.headingSource == HeadingSource.TRACK })
        // Stays stale through a reset and on a straight road, where the bearing would otherwise agree.
        est.resetTrack("test")
        val straight = run(est, feed(10.0, accM = 3.0, startMs = 60_000, reportedSpeed = { v }, bearing = { 0.0 }, path = north(v)))
        assertTrue(straight.none { it.headingSource == HeadingSource.BLEND || it.headingSource == HeadingSource.GPS })
    }

    @Test
    fun realBearingThatChangesIsNotStale() {
        val r = 200.0
        val v = 15.0
        val est = FixEstimator()
        val out = run(est, feed(40.0, accM = 3.0, reportedSpeed = { v }, bearing = { t -> Geo.normalizeDeg(Math.toDegrees(v * t / r)) }, bearingAcc = 5.0) { t ->
            val a = v * t / r
            (r * sin(a)) to (r * (1 - cos(a)))
        })
        assertFalse(est.gpsBearingStale)
        assertTrue(out.filter { it.timeMs > 5_000 }.all { it.headingSource == HeadingSource.BLEND })
    }

    @Test
    fun handoverResetsTheTrack() {
        val est = FixEstimator()
        val phone = run(est, feed(10.0, accM = 3.0, reportedSpeed = { 20.0 }, path = north(20.0)))
        assertNotNull(phone.last().headingDeg)
        val car = run(est, feed(5.0, accM = 3.0, startMs = 10_100, reportedSpeed = { 20.0 }, source = LocationSource.CAR) { t -> (200.0 + 20.0 * t) to 0.0 })
        assertTrue(car.first().resetReason!!.startsWith("handover"))
        assertNull(car.first().headingDeg)
        assertEquals(HeadingSource.NONE, car.first().headingSource)
        assertEquals(SpeedSource.CAR_RAW, car.first().speedSource)
        assertEquals(20.0, car.first().speedMps, 0.0)
        // Rebuilt from the car's feed within a couple of seconds.
        assertEquals(HeadingSource.TRACK, car.last().headingSource)
        assertTrue(Geo.absAngleDiffDeg(car.last().headingDeg!!, 0.0) < 3.0)
        assertTrue(car.drop(1).none { it.resetReason != null })
    }

    @Test
    fun gapAndJumpResetTheTrack() {
        val est = FixEstimator()
        run(est, feed(5.0, accM = 3.0, reportedSpeed = { 20.0 }, path = north(20.0)))
        val afterGap = est.onFix(FixInput(latAt(300.0), 0.0, 11_000, 3.0, speedMps = 20.0))
        assertTrue(afterGap.resetReason!!.startsWith("gap"))
        assertNull(afterGap.headingDeg)
        run(est, feed(5.0, accM = 3.0, startMs = 11_100, reportedSpeed = { 20.0 }) { t -> (302.0 + 20.0 * t) to 0.0 })
        val afterJump = est.onFix(FixInput(latAt(2_000.0), 0.0, 16_200, 3.0, speedMps = 20.0))
        assertTrue(afterJump.resetReason!!.startsWith("jump"))
        assertNull(afterJump.headingDeg)
    }

    @Test
    fun headingIsNulledAfterThirtySecondsStationary() {
        val est = FixEstimator()
        run(est, feed(10.0, accM = 3.0, reportedSpeed = { 15.0 }, path = north(15.0)))
        val parked = run(est, feed(40.0, sigmaM = 1.0, accM = 5.0, startMs = 10_100, reportedSpeed = { 0.0 }) { 150.0 to 0.0 })
        val at29 = parked.first { it.timeMs >= 10_100 + 29_000 }
        assertEquals(HeadingSource.HELD, at29.headingSource)
        assertTrue(Geo.absAngleDiffDeg(at29.headingDeg!!, 0.0) < 3.0)
        val at31 = parked.first { it.timeMs >= 10_100 + 31_000 }
        assertNull(at31.headingDeg)
        assertEquals(HeadingSource.NONE, at31.headingSource)
        assertTrue(parked.filter { it.timeMs >= 10_100 + 31_000 }.all { it.headingDeg == null })
        // Moving off eastwards builds a fresh heading; the old northbound one never comes back.
        val away = run(est, feed(6.0, accM = 3.0, startMs = 50_200, reportedSpeed = { 10.0 }) { t -> 150.0 to (10.0 * t) })
        assertTrue(away.none { it.headingDeg != null && Geo.absAngleDiffDeg(it.headingDeg, 0.0) < 30.0 })
        assertTrue(Geo.absAngleDiffDeg(away.last().headingDeg!!, 90.0) < 5.0)
    }

    @Test
    fun movingCarOnACurveTracksWithinTenDegrees() {
        val r = 200.0
        val v = 15.0
        val fixes = feed(60.0, sigmaM = 1.0, accM = 3.0, reportedSpeed = { v }) { t ->
            val a = v * t / r
            (r * sin(a)) to (r * (1 - cos(a)))
        }
        val out = run(FixEstimator(), fixes)
        val settled = out.filter { it.timeMs >= 5_000 }
        for (e in settled) {
            val tangent = Geo.normalizeDeg(Math.toDegrees(v * (e.timeMs / 1000.0) / r))
            val err = Geo.absAngleDiffDeg(e.headingDeg!!, tangent)
            assertTrue("t=${e.timeMs} err=$err", err <= 10.0)
        }
        assertTrue(settled.all { it.headingSource == HeadingSource.TRACK })
    }
}

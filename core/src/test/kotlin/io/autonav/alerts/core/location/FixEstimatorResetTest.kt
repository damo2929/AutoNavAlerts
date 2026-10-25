// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.location

import io.autonav.alerts.core.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos

/**
 * The estimator's edges (DESIGN.md §10.2, §10.6a): what a reset forgets and keeps, the exact gap
 * and jump thresholds and their reasons, accuracy 0 and accuracy over 30 m, the displacement speed
 * reading 0 again after a stop, the 30 s heading hold to the second, the inverse-variance weight of
 * the GPS bearing, the feed's source names, and [SpeedSmoother]'s window. FixEstimatorTest has the
 * main behaviours; nothing here repeats them.
 */
class FixEstimatorResetTest {
    private val baseLat = 53.0
    private val metresPerDegLat = 111_195.0
    private fun latAt(northM: Double) = baseLat + northM / metresPerDegLat
    private fun lonAt(eastM: Double) = eastM / (metresPerDegLat * cos(Math.toRadians(baseLat)))

    /** Noise-free fixes due north at [mps], 10 Hz for [seconds] from [startMs], starting [fromM] up the road. */
    private fun north(
        mps: Double, seconds: Double, accM: Double = 3.0, startMs: Long = 0, fromM: Double = 0.0, reported: Double? = mps,
        bearing: Double? = null, bearingAcc: Double? = null, source: LocationSource = LocationSource.PHONE,
    ): List<FixInput> = (0..(seconds * 10).toInt()).map { i ->
        FixInput(latAt(fromM + mps * i / 10.0), 0.0, startMs + i * 100L, accM, reported, bearing, bearingAcc, source)
    }

    private fun run(est: FixEstimator, fixes: List<FixInput>) = fixes.map { est.onFix(it) }

    @Test
    fun resetForgetsTheSpeedWindowAndThePreviousFix() {
        val est = FixEstimator()
        run(est, north(20.0, 1.0))
        est.resetTrack("test")
        // The median window is empty: the next reported speed stands on its own.
        val first = est.onFix(FixInput(latAt(5_000.0), 0.0, 5_000, 3.0, speedMps = 5.0))
        assertEquals(5.0, first.speedMps, 0.0)
        // No previous fix after a reset, so a far-off position isn't a "jump" and reports no reason.
        assertNull(first.resetReason)
        assertNull(first.headingDeg)
        assertEquals(HeadingSource.NONE, first.headingSource)
    }

    @Test
    fun gapAndJumpThresholdsAreExactAndNamed() {
        val est = FixEstimator()
        val last = run(est, north(20.0, 5.0)).last()
        assertEquals(5_000L, last.timeMs)
        // 5 000 ms is not a gap; 5 001 is.
        assertNull(est.onFix(FixInput(latAt(100.0), 0.0, 10_000, 3.0, speedMps = 20.0)).resetReason)
        assertEquals("gap 5001 ms", est.onFix(FixInput(latAt(100.0), 0.0, 15_001, 3.0, speedMps = 20.0)).resetReason)
        // 199 m is not a jump (the jump is over 200 m by Geo's haversine); 203 m is, and the reason carries the whole metres.
        assertNull(est.onFix(FixInput(latAt(299.0), 0.0, 15_101, 3.0, speedMps = 20.0)).resetReason)
        val jumped = est.onFix(FixInput(latAt(502.0), 0.0, 15_201, 3.0, speedMps = 20.0))
        assertTrue(jumped.resetReason!!, jumped.resetReason!!.startsWith("jump 20"))
        assertTrue(jumped.resetReason!!.endsWith(" m"))
        // A feed change is named with both sources.
        assertEquals("handover PHONE→CAR", est.onFix(FixInput(latAt(502.0), 0.0, 15_301, 3.0, speedMps = 20.0, source = LocationSource.CAR)).resetReason)
        assertEquals("handover CAR→SIM", est.onFix(FixInput(latAt(502.0), 0.0, 15_401, 3.0, speedMps = 20.0, source = LocationSource.SIM)).resetReason)
    }

    @Test
    fun accuracyZeroGivesAOneDegreeHeadingAccuracyNotNaN() {
        val out = run(FixEstimator(), north(20.0, 10.0, accM = 0.0))
        val settled = out.filter { it.timeMs >= 5_000 }
        assertTrue(settled.all { it.headingSource == HeadingSource.TRACK })
        assertTrue(settled.all { it.headingAccuracyDeg == 1.0 })
        assertTrue(settled.all { !it.headingDeg!!.isNaN() && Geo.absAngleDiffDeg(it.headingDeg!!, 0.0) < 0.01 })
        // Normal accuracy: atan2(2 × 3 m, 40 m chord) ≈ 8.5°.
        val normal = run(FixEstimator(), north(20.0, 10.0, accM = 3.0)).last()
        assertEquals(Math.toDegrees(atan2(6.0, 40.0)), normal.headingAccuracyDeg!!, 0.05)
        assertEquals(40.0, normal.chordM!!, 0.1)
    }

    @Test
    fun fixesWorseThan30mKeepTheirSpeedButNeverFeedTheTrack() {
        val poor = run(FixEstimator(), north(20.0, 10.0, accM = 30.1))
        assertTrue(poor.all { it.headingDeg == null && it.headingSource == HeadingSource.NONE && it.chordM == null })
        assertTrue(poor.all { it.speedMps == 20.0 && it.speedSource == SpeedSource.GPS })
        // Exactly 30 m is still good enough.
        assertEquals(HeadingSource.TRACK, run(FixEstimator(), north(20.0, 10.0, accM = 30.0)).last().headingSource)
        // Without a reported speed, poor fixes never even start the displacement reference.
        val blind = run(FixEstimator(), north(20.0, 10.0, accM = 50.0, reported = null))
        assertTrue(blind.all { it.speedMps == 0.0 && it.speedSource == SpeedSource.NONE })
        // A missing accuracy is taken as 10 m: good.
        assertEquals(HeadingSource.TRACK, run(FixEstimator(), (0..100).map { FixInput(latAt(2.0 * it), 0.0, it * 100L, null, 20.0) }).last().headingSource)
    }

    @Test
    fun displacementSpeedReadsZeroAgainAfterAStop() {
        val est = FixEstimator()
        val moving = run(est, north(10.0, 6.0, reported = null))
        assertTrue(moving.filter { it.timeMs >= 4_000 }.all { it.speedSource == SpeedSource.DISPLACEMENT && it.speedMps in 8.5..11.5 })
        // Parked at 60 m from 6.1 s on: the last window closes, then a zero window, then the re-anchor.
        val parked = (61..130).map { i -> FixInput(latAt(60.0), 0.0, i * 100L, 3.0, null) }.map { est.onFix(it) }
        assertTrue(parked.first().speedMps > 0.0) // held until a window says otherwise
        val zeroFrom = parked.first { it.speedMps == 0.0 }.timeMs
        // The first window after the stop closes at 1 s, the zero window and the re-anchor follow (§10.2).
        assertEquals("zero from $zeroFrom", 12_600L, zeroFrom)
        assertTrue(parked.filter { it.timeMs >= zeroFrom }.all { it.speedMps == 0.0 && it.speedSource == SpeedSource.DISPLACEMENT })
        assertTrue(parked.filter { it.timeMs >= zeroFrom }.all { it.headingSource == HeadingSource.HELD })
    }

    @Test
    fun headingIsHeldForExactlyThirtySeconds() {
        val est = FixEstimator()
        run(est, north(15.0, 10.0))
        // Reported 0 from 10.1 s; the median of three reads 0 from 10.2 s, so stillness starts there:
        // held at 40.1 s (29.9 s of stillness), gone at 40.2 s (30 s, §10.6a).
        val parked = (101..402).map { i -> est.onFix(FixInput(latAt(150.0), 0.0, i * 100L, 3.0, 0.0)) }
        val at40 = parked.single { it.timeMs == 40_100L }
        assertEquals(HeadingSource.HELD, at40.headingSource)
        assertEquals(0.0, at40.headingDeg!!, 0.01)
        assertNull(at40.chordM)
        val at401 = parked.single { it.timeMs == 40_200L }
        assertNull(at401.headingDeg)
        assertNull(at401.headingAccuracyDeg)
        assertNull(at401.trackDeg)
        assertEquals(HeadingSource.NONE, at401.headingSource)
    }

    @Test
    fun gpsBearingPullsByInverseVariance() {
        fun blended(bearingAcc: Double): Estimate {
            val est = FixEstimator()
            run(est, north(20.0, 10.0))
            return run(est, north(20.0, 5.0, startMs = 10_100, fromM = 202.0, bearing = 10.0, bearingAcc = bearingAcc)).last()
        }
        // A 1° bearing against an 8.5° track: the heading is nearly the GPS bearing, and more certain than either.
        val precise = blended(1.0)
        assertEquals(HeadingSource.BLEND, precise.headingSource)
        assertTrue("heading ${precise.headingDeg}", precise.headingDeg!! > 9.5)
        assertTrue(precise.headingAccuracyDeg!! < 1.0)
        assertEquals(10.0, precise.gpsBearingDeg!!, 0.0)
        assertEquals(0.0, precise.trackDeg!!, 0.01)
        // A 20° bearing (the most allowed) barely moves it: about 15% of the way.
        val rough = blended(20.0)
        assertEquals(HeadingSource.BLEND, rough.headingSource)
        assertTrue("heading ${rough.headingDeg}", rough.headingDeg!! in 0.5..3.0)
        assertTrue(rough.headingAccuracyDeg!! < Math.toDegrees(atan2(6.0, 40.0)))
        // 20.1° is over the limit: not used at all.
        val unused = blended(20.1)
        assertEquals(HeadingSource.TRACK, unused.headingSource)
        assertNull(unused.gpsBearingDeg)
        // A constant bearing on a straight road is never "stale": the track hasn't turned.
        assertFalse(FixEstimator().also { run(it, north(20.0, 30.0, bearing = 0.0, bearingAcc = 5.0)) }.gpsBearingStale)
    }

    @Test
    fun speedSourceFollowsTheFeedAndNegativeSpeedsReadAsZero() {
        val est = FixEstimator()
        assertEquals(SpeedSource.SIMULATED, est.onFix(FixInput(baseLat, 0.0, 0, 3.0, 12.0, source = LocationSource.SIM)).speedSource)
        assertEquals(LocationSource.SIM, est.onFix(FixInput(baseLat, 0.0, 100, 3.0, 12.0, source = LocationSource.SIM)).locationSource)
        val car = FixEstimator()
        assertEquals(SpeedSource.CAR_RAW, car.onFix(FixInput(baseLat, 0.0, 0, 3.0, 12.0, source = LocationSource.CAR)).speedSource)
        val neg = FixEstimator()
        assertEquals(0.0, neg.onFix(FixInput(baseLat, 0.0, 0, 3.0, -3.0)).speedMps, 0.0)
    }

    @Test
    fun smootherWindowAndReset() {
        val one = SpeedSmoother(window = 1)
        assertEquals(10.0, one.add(10.0), 0.0)
        assertEquals(30.0, one.add(30.0), 0.0)
        val five = SpeedSmoother(window = 5)
        listOf(1.0, 2.0, 3.0, 4.0).forEach { five.add(it) }
        assertEquals(3.0, five.add(100.0), 0.0)
        assertEquals(4.0, five.add(200.0), 0.0) // [2, 3, 4, 100, 200] → 4
        five.reset()
        assertEquals(7.0, five.add(7.0), 0.0)
    }
}

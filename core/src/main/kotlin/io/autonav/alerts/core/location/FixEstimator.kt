// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.location

import io.autonav.alerts.core.geo.Geo
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where a fix came from (DESIGN.md §10.6a, §10.7). The engine's `alerts.LocationSource` is an
 * alias of this one, so the app's `EstimateMapper` passes it through unchanged.
 */
enum class LocationSource { PHONE, CAR, SIM }

/**
 * How the speed was worked out (DESIGN.md §10.2): a reported GPS or car speed, displacement over
 * time when the fix carried none, a simulated feed, or nothing yet. (The engine's
 * `alerts.SpeedSource` calls DISPLACEMENT "DERIVED"; the app maps between them.)
 */
enum class SpeedSource { GPS, DISPLACEMENT, CAR_RAW, SIMULATED, NONE }

/**
 * How the heading was worked out (DESIGN.md §10.6a): the GPS bearing alone, the chord track alone,
 * the two blended, the last track held while the car is slow, or none.
 */
enum class HeadingSource { GPS, TRACK, BLEND, HELD, NONE }

/**
 * One fix as the feed gives it. A null [speedMps] means the fix carries no speed (`!hasSpeed()`);
 * a null [bearingDeg] means no bearing. A reported 0 is a real 0.
 */
data class FixInput(
    /** Position, degrees. */
    val lat: Double,
    val lon: Double,
    /** Milliseconds on one clock for every feed: the app uses the time since boot (§10.7), so gaps and jumps compare. */
    val timeMs: Long,
    /** Horizontal accuracy, metres (1σ); null means unknown and is taken as [FixEstimator.Config.defaultAccuracyM]. */
    val accuracyM: Double? = null,
    val speedMps: Double? = null,
    /** The feed's own bearing, degrees true, and its accuracy in degrees when the feed gives one. */
    val bearingDeg: Double? = null,
    val bearingAccuracyDeg: Double? = null,
    val source: LocationSource = LocationSource.PHONE,
)

/** What [FixEstimator] makes of a fix. */
data class Estimate(
    val timeMs: Long,
    val speedMps: Double,
    val speedSource: SpeedSource,
    /** Degrees true, or null when unknown (parked for 30 s, or nothing to go on yet). */
    val headingDeg: Double?,
    val headingSource: HeadingSource,
    /** About how far the heading may be out, degrees (1σ-ish); null with no heading. */
    val headingAccuracyDeg: Double?,
    /** The chord the track bearing was measured over this fix, or null when there was no new sample. */
    val chordM: Double?,
    /** The smoothed track bearing on its own, or null. */
    val trackDeg: Double?,
    /** The fix's GPS bearing when it was trusted enough to be used, else null. */
    val gpsBearingDeg: Double?,
    val gpsBearingStale: Boolean,
    val locationSource: LocationSource,
    /** Set on the fix that caused a [FixEstimator.resetTrack] (handover, gap, jump). */
    val resetReason: String?,
)

/**
 * Speed and heading from the live feed (DESIGN.md §10.2 and §10.6a). Pure and single-threaded.
 *
 * Speed: the median of the last 3 reported speeds when the fix carries one (a reported 0 is a real
 * 0); displacement ÷ time only when it doesn't, against a reference fix at least 1 s old, and only
 * when the displacement is more than max(3 m, accuracy) on two fixes in a row, so 10 Hz jitter
 * while parked reads as 0. The displacement speed is likewise the median of its last 3 windows,
 * so one window of jitter beyond the threshold can't become a speed either.
 *
 * Heading: the bearing over an adaptive chord of clamp(speed × 2 s, 10–60 m) back along the ring
 * of recent fixes, smoothed with a circular EMA, with `headingAccuracyDeg ≈ atan2(2·accuracy,
 * chord)`. The GPS bearing is blended in by inverse variance when the car is over 3 m/s, its
 * accuracy is ≤ 20° and it agrees with the track within 45°; a bearing that is bit-identical for
 * 10 fixes while the track turned more than 20° is stale for the rest of the session. After 30 s
 * under 1 m/s the heading is null.
 *
 * DESIGN.md §3 calls the two halves `HeadingEstimator` and `SpeedEstimator`; they share the ring
 * because the chord and the displacement speed both need the recent positions. No Android, no
 * threads of its own: the app's `AdaptiveLocationSource` keeps one instance and calls [onFix] on
 * its `adaptive-location` thread for every phone, car or simulated fix in turn (§10.7), and calls
 * [resetTrack] on a feed handover; [onFix] resets itself on a gap over 5 s or a jump over 200 m.
 * Units: degrees in and out for positions and bearings, metres, m/s, milliseconds on the fix clock.
 *
 * The heading this produces is what the camera direction rule (§11 step 3, the owner's hard rule)
 * is judged against, so it must stay a track heading with an honest accuracy: never a stale GPS
 * bearing, never a heading invented from parked jitter.
 */
class FixEstimator(private val config: Config = Config()) {
    data class Config(
        // The displacement speed needs more than max(3 m, accuracy) of movement over at least 1 s, on two
        // fixes in a row (§10.2), so jitter while parked reads as 0.
        val minDisplacementM: Double = 3.0,
        val displacementMinDtMs: Long = 1_000,
        /** Standing still this long re-anchors the displacement reference (drift can't build up). */
        val displacementReanchorMs: Long = 5_000,
        /** Fixes worse than this don't feed the displacement speed or the track. */
        val maxAccuracyM: Double = 30.0,
        val defaultAccuracyM: Double = 10.0,
        // The track chord is clamp(speed × 2 s, 10–60 m) back along the ring (§10.6a).
        val chordSeconds: Double = 2.0,
        val minChordM: Double = 10.0,
        val maxChordM: Double = 60.0,
        /** The circular EMA's weight on the newest chord bearing. */
        val emaAlpha: Double = 0.5,
        /** No new track samples below this speed. */
        val minTrackMps: Double = 1.0,
        // The GPS bearing is blended in only over 3 m/s, with accuracy ≤ 20° and within 45° of the track (§10.6a).
        val gpsMinMps: Double = 3.0,
        val gpsMaxAccuracyDeg: Double = 20.0,
        val gpsDefaultAccuracyDeg: Double = 10.0,
        val gpsMaxOffTrackDeg: Double = 45.0,
        // Stale-bearing guard (§10.6a): the same bits for 10 fixes while the track turned over 20°.
        val staleBearingFixes: Int = 10,
        val staleBearingTurnDeg: Double = 20.0,
        // Under 1 m/s is "still"; after 30 s of it the heading is null and the ring is kept empty (§10.6a).
        val stillMps: Double = 1.0,
        val stillHeadingMs: Long = 30_000,
        // A gap over 5 s or a jump over 200 m resets the track (§10.6a `resetTrack`).
        val gapMs: Long = 5_000,
        val jumpM: Double = 200.0,
        /** The ring of recent positions is bounded in time and count; 60 m of chord needs far less. */
        val ringMaxMs: Long = 60_000,
        val ringMax: Int = 1_000,
    )

    private class Sample(val lat: Double, val lon: Double, val timeMs: Long)

    private val ring = ArrayDeque<Sample>()
    private var last: FixInput? = null
    private val smoother = SpeedSmoother()
    private var disp = Displacement()
    private var ema: Double? = null
    private var trackAccDeg: Double? = null
    private var stillSinceMs: Long? = null
    private var lastBearingBits: Long? = null
    private var sameBearingCount = 0
    private var runStartTrack: Double? = null

    /** Once set, the feed's bearing is ignored for the rest of this estimator's life. */
    var gpsBearingStale: Boolean = false
        private set

    /**
     * Forgets the track, the speed window and the displacement reference. The stale-bearing flag is
     * kept. [reason] is the caller's own log line (handover, gap, jump); the automatic resets of
     * [onFix] report theirs in [Estimate.resetReason].
     */
    @Suppress("UNUSED_PARAMETER")
    fun resetTrack(reason: String) {
        ring.clear()
        smoother.reset()
        disp = Displacement()
        ema = null
        trackAccDeg = null
        stillSinceMs = null
        last = null
    }

    /**
     * One fix in, one [Estimate] out. Never throws and never returns NaN: a feed's accuracy of 0 is
     * floored, a missing accuracy defaulted, and a fix worse than [Config.maxAccuracyM] still gets a
     * reported speed but doesn't feed the track or the displacement speed.
     */
    fun onFix(f: FixInput): Estimate {
        val resetReason = last?.let { prev ->
            val jump = Geo.distanceM(prev.lat, prev.lon, f.lat, f.lon)
            when {
                f.source != prev.source -> "handover ${prev.source}→${f.source}"
                f.timeMs - prev.timeMs > config.gapMs -> "gap ${f.timeMs - prev.timeMs} ms"
                jump > config.jumpM -> "jump ${jump.toInt()} m"
                else -> null
            }
        }?.also(::resetTrack)
        val acc = f.accuracyM ?: config.defaultAccuracyM
        val good = acc <= config.maxAccuracyM

        // --- Speed: the reported one when the fix carries it, else displacement ÷ time ---
        val (speed, speedSource) = when (val reported = f.speedMps) {
            null -> disp.onFix(f, acc, good)
            else -> smoother.add(max(0.0, reported)) to when (f.source) {
                LocationSource.PHONE -> SpeedSource.GPS
                LocationSource.CAR -> SpeedSource.CAR_RAW
                LocationSource.SIM -> SpeedSource.SIMULATED
            }
        }

        // --- Standing still: the heading is held, then null after 30 s. A chord never spans a
        // stop (the ring is emptied while still), so moving off in a new direction can't read as
        // the old one through the parked jitter.
        val stillNow = speed < config.stillMps
        stillSinceMs = if (stillNow) stillSinceMs ?: f.timeMs else null
        if (stillNow) ring.clear()
        val still = stillSinceMs?.let { f.timeMs - it >= config.stillHeadingMs } ?: false
        if (still) { ema = null; trackAccDeg = null }

        // --- Track over an adaptive chord ---
        var chord: Double? = null
        if (good && !still && speed >= config.minTrackMps) {
            val target = (speed * config.chordSeconds).coerceIn(config.minChordM, config.maxChordM)
            var pick: Sample? = null
            var pickD = 0.0
            for (i in ring.lastIndex downTo 0) {
                val s = ring[i]
                val d = Geo.distanceM(s.lat, s.lon, f.lat, f.lon)
                if (d >= config.minChordM) { pick = s; pickD = d }
                if (d >= target) break
            }
            pick?.let { p ->
                val b = Geo.bearingDeg(p.lat, p.lon, f.lat, f.lon)
                ema = ema?.let { Geo.normalizeDeg(it + config.emaAlpha * Geo.angleDiffDeg(b, it)) } ?: b
                // headingAccuracyDeg ≈ atan2(2 × accuracy, chord) (§10.6a): 3 m fixes over 40 m give about 8.5°.
                trackAccDeg = Math.toDegrees(atan2(2 * acc, pickD))
                chord = pickD
            }
        }
        if (good) {
            ring.addLast(Sample(f.lat, f.lon, f.timeMs))
            while (ring.size > config.ringMax || f.timeMs - ring.first().timeMs > config.ringMaxMs) ring.removeFirst()
        }

        // --- GPS bearing and the stale guard ---
        val b = f.bearingDeg
        if (b != null) {
            val bits = b.toRawBits()
            if (bits == lastBearingBits) sameBearingCount++ else { lastBearingBits = bits; sameBearingCount = 1; runStartTrack = ema }
            if (runStartTrack == null) runStartTrack = ema
            if (!gpsBearingStale && sameBearingCount >= config.staleBearingFixes) {
                val turned = ema?.let { t -> runStartTrack?.let { r -> Geo.absAngleDiffDeg(t, r) } }
                if (turned != null && turned > config.staleBearingTurnDeg) gpsBearingStale = true
            }
        }
        // A reported accuracy of 0 (mock, simulated or car feeds) would weigh the bearing infinitely: NaN heading.
        val bAcc = (f.bearingAccuracyDeg ?: config.gpsDefaultAccuracyDeg).coerceAtLeast(1.0)
        val gpsUsable = b != null && !gpsBearingStale && !still && speed > config.gpsMinMps && bAcc <= config.gpsMaxAccuracyDeg

        // --- Combine: the heading, its accuracy and where it came from ---
        val track = ema
        val tAcc = trackAccDeg?.coerceAtLeast(1.0)
        val (heading, hAcc, source) = when {
            track != null && tAcc != null && gpsUsable && Geo.absAngleDiffDeg(b!!, track) <= config.gpsMaxOffTrackDeg -> {
                // Inverse-variance blend (§10.6a): the better-known of track and GPS bearing pulls harder.
                val wT = 1.0 / (tAcc * tAcc)
                val wG = 1.0 / (bAcc * bAcc)
                Triple(Geo.normalizeDeg(track + wG / (wT + wG) * Geo.angleDiffDeg(b, track)), sqrt(1.0 / (wT + wG)), HeadingSource.BLEND)
            }
            track != null -> Triple(track, tAcc, if (chord != null) HeadingSource.TRACK else HeadingSource.HELD)
            gpsUsable -> Triple(b, bAcc, HeadingSource.GPS)
            else -> Triple(null, null, HeadingSource.NONE)
        }
        // The feed's bearing counts as used when it shaped the heading.
        val gpsUsed = b.takeIf { source == HeadingSource.BLEND || source == HeadingSource.GPS }

        last = f
        return Estimate(
            timeMs = f.timeMs, speedMps = speed, speedSource = speedSource,
            headingDeg = heading, headingSource = source, headingAccuracyDeg = hAcc,
            chordM = chord, trackDeg = track, gpsBearingDeg = gpsUsed, gpsBearingStale = gpsBearingStale,
            locationSource = f.source, resetReason = resetReason,
        )
    }

    /**
     * The displacement speed (§10.2 "Speed", the fix carries none): the reference fix a window is
     * measured from, the median of the last three windows ([speed]), the last window's own speed
     * and whether one fix beyond the threshold is pending. Replaced as a unit by [resetTrack].
     */
    private inner class Displacement {
        private var ref: Sample? = null
        private var speed = 0.0
        private var windowSpeed = 0.0
        private var pending = false
        private val smoother = SpeedSmoother()

        /** The speed after this fix with its source: NONE until a reference exists; a poor fix ([good] false) holds the last. */
        fun onFix(f: FixInput, acc: Double, good: Boolean): Pair<Double, SpeedSource> {
            val r = ref
            when {
                !good -> Unit // hold the last displacement speed
                r == null -> {
                    ref = Sample(f.lat, f.lon, f.timeMs)
                    speed = 0.0
                }
                f.timeMs - r.timeMs < config.displacementMinDtMs -> Unit // too soon: hold
                else -> {
                    val dt = (f.timeMs - r.timeMs) / 1000.0
                    val disp = Geo.distanceM(r.lat, r.lon, f.lat, f.lon)
                    val threshold = max(config.minDisplacementM, acc)
                    if (disp > threshold) {
                        // Two fixes in a row beyond the threshold: one jitter spike can't become a speed.
                        if (pending) closeWindow(disp / dt, f) else pending = true
                    } else {
                        pending = false
                        // No displacement in a window: a stop, when the last speed would have shown
                        // in this long a window; otherwise (a crawl) only after the re-anchor time.
                        val stopped = windowSpeed * dt > threshold
                        if (stopped || f.timeMs - r.timeMs >= config.displacementReanchorMs) closeWindow(0.0, f)
                    }
                }
            }
            return speed to if (ref == null) SpeedSource.NONE else SpeedSource.DISPLACEMENT
        }

        /** One displacement window ends with speed [raw]; the reference moves to this fix. */
        private fun closeWindow(raw: Double, f: FixInput) {
            windowSpeed = raw
            speed = smoother.add(raw)
            ref = Sample(f.lat, f.lon, f.timeMs)
            pending = false
        }
    }
}

/**
 * Median of the last three speed samples (DESIGN.md §10.2 "Speed"): one bad reading can't show,
 * and a real stop still reads 0 within two fixes. Used for both the reported and the displacement
 * speed. m/s in, m/s out.
 */
class SpeedSmoother(private val window: Int = 3) {
    private val samples = ArrayDeque<Double>()

    /** Adds a sample and returns the median of the window so far. */
    fun add(mps: Double): Double {
        samples.addLast(mps)
        while (samples.size > window) samples.removeFirst()
        val sorted = samples.sorted()
        return sorted[sorted.size / 2]
    }

    fun reset() = samples.clear()
}

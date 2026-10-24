// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.location

import io.autonav.alerts.core.geo.Geo
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where a fix came from (DESIGN.md §10.6a, §10.7). The app maps it onto the engine's
 * `alerts.LocationSource` when it builds a `Fix`; the two enums are kept apart so `:core`'s
 * location and alert halves don't depend on each other.
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
    private var dispRef: Sample? = null
    /** The median of the last three displacement windows. */
    private var dispSpeed = 0.0
    /** The last window's own speed. */
    private var dispWindowSpeed = 0.0
    private var dispPending = false
    private val dispSmoother = SpeedSmoother()

    /** One displacement window ends with speed [raw]; the reference moves to this fix. */
    private fun closeWindow(raw: Double, f: FixInput) {
        dispWindowSpeed = raw
        dispSpeed = dispSmoother.add(raw)
        dispRef = Sample(f.lat, f.lon, f.timeMs)
        dispPending = false
    }
    private var ema: Double? = null
    private var trackAccDeg: Double? = null
    private var stillSinceMs: Long? = null
    private var lastBearingBits: Long? = null
    private var sameBearingCount = 0
    private var runStartTrack: Double? = null

    /** Once set, the feed's bearing is ignored for the rest of this estimator's life. */
    var gpsBearingStale: Boolean = false
        private set

    var lastResetReason: String? = null
        private set

    /** Forgets the track, the speed window and the displacement reference. The stale-bearing flag is kept. */
    fun resetTrack(reason: String) {
        ring.clear()
        smoother.reset()
        dispRef = null
        dispSpeed = 0.0
        dispWindowSpeed = 0.0
        dispPending = false
        dispSmoother.reset()
        ema = null
        trackAccDeg = null
        stillSinceMs = null
        last = null
        lastResetReason = reason
    }

    /**
     * One fix in, one [Estimate] out. Never throws and never returns NaN: a feed's accuracy of 0 is
     * floored, a missing accuracy defaulted, and a fix worse than [Config.maxAccuracyM] still gets a
     * reported speed but doesn't feed the track or the displacement speed.
     */
    fun onFix(f: FixInput): Estimate {
        var resetReason: String? = null
        last?.let { prev ->
            val reason = when {
                f.source != prev.source -> "handover ${prev.source}→${f.source}"
                f.timeMs - prev.timeMs > config.gapMs -> "gap ${f.timeMs - prev.timeMs} ms"
                Geo.distanceM(prev.lat, prev.lon, f.lat, f.lon) > config.jumpM ->
                    "jump ${Geo.distanceM(prev.lat, prev.lon, f.lat, f.lon).toInt()} m"
                else -> null
            }
            if (reason != null) { resetTrack(reason); resetReason = reason }
        }
        val acc = f.accuracyM ?: config.defaultAccuracyM
        val good = acc <= config.maxAccuracyM

        // --- Speed ---
        val speed: Double
        val speedSource: SpeedSource
        val reported = f.speedMps
        if (reported != null) {
            speed = smoother.add(max(0.0, reported))
            speedSource = when (f.source) {
                LocationSource.PHONE -> SpeedSource.GPS
                LocationSource.CAR -> SpeedSource.CAR_RAW
                LocationSource.SIM -> SpeedSource.SIMULATED
            }
        } else {
            val ref = dispRef
            when {
                !good -> Unit // hold the last displacement speed
                ref == null -> { dispRef = Sample(f.lat, f.lon, f.timeMs); dispSpeed = 0.0 }
                f.timeMs - ref.timeMs < config.displacementMinDtMs -> Unit // too soon: hold
                else -> {
                    val dt = (f.timeMs - ref.timeMs) / 1000.0
                    val disp = Geo.distanceM(ref.lat, ref.lon, f.lat, f.lon)
                    val threshold = max(config.minDisplacementM, acc)
                    if (disp > threshold) {
                        // Two fixes in a row beyond the threshold: one jitter spike can't become a speed.
                        if (dispPending) {
                            closeWindow(disp / dt, f)
                        } else {
                            dispPending = true
                        }
                    } else {
                        dispPending = false
                        // No displacement in a window: a stop, when the last speed would have shown
                        // in this long a window; otherwise (a crawl) only after the re-anchor time.
                        val stopped = dispWindowSpeed * dt > threshold
                        if (stopped || f.timeMs - ref.timeMs >= config.displacementReanchorMs) closeWindow(0.0, f)
                    }
                }
            }
            speed = dispSpeed
            speedSource = if (dispRef == null) SpeedSource.NONE else SpeedSource.DISPLACEMENT
        }

        // --- Standing still: the heading is held, then null after 30 s. A chord never spans a
        // stop (the ring is emptied while still), so moving off in a new direction can't read as
        // the old one through the parked jitter.
        if (speed < config.stillMps) {
            if (stillSinceMs == null) stillSinceMs = f.timeMs
            ring.clear()
        } else {
            stillSinceMs = null
        }
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
            if (pick != null) {
                val b = Geo.bearingDeg(pick.lat, pick.lon, f.lat, f.lon)
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
                val t = ema
                val r = runStartTrack
                if (t != null && r != null && Geo.absAngleDiffDeg(t, r) > config.staleBearingTurnDeg) gpsBearingStale = true
            }
        }
        // A reported accuracy of 0 (mock, simulated or car feeds) would weigh the bearing infinitely: NaN heading.
        val bAcc = (f.bearingAccuracyDeg ?: config.gpsDefaultAccuracyDeg).coerceAtLeast(1.0)
        val gpsUsable = b != null && !gpsBearingStale && !still && speed > config.gpsMinMps && bAcc <= config.gpsMaxAccuracyDeg

        // --- Combine ---
        val track = ema
        val tAcc = trackAccDeg?.coerceAtLeast(1.0)
        var heading: Double? = null
        var source = HeadingSource.NONE
        var hAcc: Double? = null
        var gpsUsed: Double? = null
        when {
            track != null && tAcc != null && gpsUsable && Geo.absAngleDiffDeg(b!!, track) <= config.gpsMaxOffTrackDeg -> {
                // Inverse-variance blend (§10.6a): the better-known of track and GPS bearing pulls harder.
                val wT = 1.0 / (tAcc * tAcc)
                val wG = 1.0 / (bAcc * bAcc)
                heading = Geo.normalizeDeg(track + wG / (wT + wG) * Geo.angleDiffDeg(b, track))
                hAcc = sqrt(1.0 / (wT + wG))
                source = HeadingSource.BLEND
                gpsUsed = b
            }
            track != null -> { heading = track; hAcc = tAcc; source = if (chord != null) HeadingSource.TRACK else HeadingSource.HELD }
            gpsUsable -> { heading = b; hAcc = bAcc; source = HeadingSource.GPS; gpsUsed = b }
        }

        last = f
        return Estimate(
            timeMs = f.timeMs, speedMps = speed, speedSource = speedSource,
            headingDeg = heading, headingSource = source, headingAccuracyDeg = hAcc,
            chordM = chord, trackDeg = track, gpsBearingDeg = gpsUsed, gpsBearingStale = gpsBearingStale,
            locationSource = f.source, resetReason = resetReason,
        )
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

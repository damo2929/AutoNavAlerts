// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ZoneRole
import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A synthetic road with a car on it, for the engine tests (DESIGN.md §15.1): a straight or an arc of
 * radius [radiusM] (positive = turning right, negative = left, null = straight) starting at the origin
 * heading [initialHeadingDeg]. Positions are in local metres (east, north) near 53°N and converted to
 * lat/lon with the same flat approximation the older tests use.
 *
 * Fixes get gaussian position noise ([sigmaM], seeded) and a heading derived from a chord behind the car
 * like `FixEstimator` (§10.6a): clamp(speed × 2 s, 10–60 m), so position noise shows up as heading
 * noise the way it does on the road. [headingAccuracyDeg] is what the fix claims about that heading.
 */
class NoisyTrack(
    val radiusM: Double? = null,
    val initialHeadingDeg: Double = 0.0,
    val sigmaM: Double = 0.0,
    val seed: Long = 1,
    val stepMs: Long = 200,
    val headingAccuracyDeg: Double? = null,
    /** Speed in m/s as a function of the distance along the road. */
    val speedMps: (Double) -> Double,
) {
    data class Point(val east: Double, val north: Double, val headingDeg: Double)

    private val random = Random(seed)

    /** The road's centreline at arc length [s], and its heading there. */
    fun road(s: Double): Point {
        val h0 = Math.toRadians(initialHeadingDeg)
        val r = radiusM ?: return Point(s * sin(h0), s * cos(h0), initialHeadingDeg)
        val h = h0 + s / r
        // Centre on the right for a right turn: c = p0 + R·n(h0), n(h) = (cos h, −sin h); p(s) = c − R·n(h).
        val cx = r * cos(h0)
        val cy = -r * sin(h0)
        return Point(cx - r * cos(h), cy + r * sin(h), Geo.normalizeDeg(Math.toDegrees(h)))
    }

    /** A point [lateralM] to the right of the road at arc length [s]. */
    fun beside(s: Double, lateralM: Double): Point {
        val p = road(s)
        val h = Math.toRadians(p.headingDeg)
        return Point(p.east + lateralM * cos(h), p.north - lateralM * sin(h), p.headingDeg)
    }

    fun camera(
        id: Long, s: Double, lateralM: Double = 0.0, dir: DirectionType = DirectionType.ONE_WAY,
        bearing: Float? = road(s).headingDeg.toFloat(), first: AlertStage = AlertStage.DEFAULT_FIRST,
        second: AlertStage = AlertStage.DEFAULT_SECOND, sound: Boolean = true, limitMph: Int? = 30,
        zone: ZoneRole = ZoneRole.NONE, sideToleranceM: Double? = null,
    ): AlertPoi {
        val p = beside(s, lateralM)
        return AlertPoi(id, latOf(p.north), lonOf(p.east), dir, bearing, limitMph?.let { it * Geo.MPS_PER_MPH }, zone, first, second, sound, sideToleranceM)
    }

    /** One fix per step from [fromS] to [toS] along the road, fed to [engine]; [onFix] sees each fix and its events. */
    fun drive(
        engine: AlertEngine, pois: List<AlertPoi>, fromS: Double, toS: Double, route: RouteMatcher? = null,
        startMs: Long = 0, onFix: (Fix, List<AlertEvent>) -> Unit = { _, _ -> },
    ): List<AlertEvent> {
        val out = ArrayList<AlertEvent>()
        var s = fromS
        var t = startMs
        val history = ArrayList<Pair<Double, Point>>() // arc length → noisy position
        while (s <= toS) {
            val v = speedMps(s)
            val truth = road(s)
            val noisy = Point(truth.east + random.nextGaussian() * sigmaM, truth.north + random.nextGaussian() * sigmaM, truth.headingDeg)
            val chord = (v * 2.0).coerceIn(10.0, 60.0)
            val back = history.lastOrNull { it.first <= s - chord }
            val heading = if (back == null) truth.headingDeg else
                Geo.normalizeDeg(Math.toDegrees(atan2(noisy.east - back.second.east, noisy.north - back.second.north)))
            history += s to noisy
            val fix = Fix(latOf(noisy.north), lonOf(noisy.east), v, heading, t, headingAccuracyDeg)
            val events = engine.onFix(fix, pois, route)
            onFix(fix, events)
            out += events
            s += v * stepMs / 1000.0
            t += stepMs
            if (v <= 0.0) break
        }
        return out
    }

    /** Stands still at arc length [s] for [seconds] with position jitter, feeding [engine]; speed 0, no heading. */
    fun hold(
        engine: AlertEngine, pois: List<AlertPoi>, s: Double, seconds: Double, startMs: Long, jitterM: Double = sigmaM,
        speedMps: Double = 0.0, heading: Double? = null,
    ): List<AlertEvent> {
        val out = ArrayList<AlertEvent>()
        val truth = road(s)
        var t = startMs
        val end = startMs + (seconds * 1000).toLong()
        while (t <= end) {
            val fix = Fix(latOf(truth.north + random.nextGaussian() * jitterM), lonOf(truth.east + random.nextGaussian() * jitterM), speedMps, heading, t, headingAccuracyDeg)
            out += engine.onFix(fix, pois)
            t += stepMs
        }
        return out
    }

    companion object {
        const val BASE_LAT = 53.0
        const val METRES_PER_DEG_LAT = 111_195.0
        fun latOf(northM: Double) = BASE_LAT + northM / METRES_PER_DEG_LAT
        fun lonOf(eastM: Double) = eastM / (METRES_PER_DEG_LAT * cos(Math.toRadians(BASE_LAT)))

        fun mph(v: Double) = v * Geo.MPS_PER_MPH

        /** PGW's 8-point compass bearings (§5.2a): the nearest multiple of 45°. */
        fun compass8(deg: Double): Float = Geo.normalizeDeg(((deg / 45.0).roundToInt() * 45).toDouble()).toFloat()

        /** Metres from a fix to a POI, for assertions. */
        fun distance(fix: Fix, poi: AlertPoi) = Geo.distanceM(fix.lat, fix.lon, poi.lat, poi.lon)

        fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)
    }
}

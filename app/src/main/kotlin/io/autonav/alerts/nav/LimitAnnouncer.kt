// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import io.autonav.alerts.core.geo.Geo
import kotlin.math.cos
import kotlin.math.sin

/**
 * When to say "Speed limit changes to thirty ahead" (DESIGN.md §8.4a, "Limit changes ahead").
 * Pure, so it's unit-tested: once per change, keyed by the new limit and where the change is
 * (its own position when the lookahead gives one, else the car's position moved [distanceM] along
 * its heading, matched within [sameChangeM]), and
 * only once the change is within max([minM], speed × [leadS]). Null speed or heading → never.
 *
 * Owned by `NavSession`, which evaluates the lookahead once a second (never per fix, §8.4a) and,
 * when this says yes, speaks [text] through `NavTts` as `AlertKind.LIMIT_CHANGE` at a camera
 * FIRST's priority: a limit change is never reported as a camera (owner, 2026-10-13). Not thread
 * safe; the session calls it from one collector.
 *
 * Defaults are the design's numbers: [minM] 300 m, [leadS] 12 s (§8.4a "distance ≤ max(300 m,
 * speed × 12 s)"); [sameChangeM] 200 m is how far apart two changes to the same limit must be
 * to count as different changes.
 */
class LimitAnnouncer(
    private val minM: Double = 300.0,
    private val leadS: Double = 12.0,
    private val sameChangeM: Double = 200.0,
) {
    /** The last announced limit (mph) and where its change was, so the same change isn't said twice. */
    private var lastMph: Int? = null
    private var lastLat = 0.0
    private var lastLon = 0.0

    /**
     * True when [newMph] at [distanceM] ahead should be announced now. Records it when true.
     *
     * [newMph] is the upcoming limit in mph (the limit file's unit); [distanceM] the distance to
     * the change along the road or route, metres; [lat]/[lon] the car's position, degrees;
     * [speedMps] the engine's speed, m/s; [headingDeg] the engine's heading, degrees true, or
     * null (then never: with no heading there is no "ahead"); [changeLat]/[changeLon] the change's
     * own position when the lookahead found one, else NaN and the position is projected.
     */
    fun shouldAnnounce(
        newMph: Int, distanceM: Double, lat: Double, lon: Double, speedMps: Double, headingDeg: Double?,
        changeLat: Double = Double.NaN, changeLon: Double = Double.NaN,
    ): Boolean {
        if (headingDeg == null) return false
        // Not yet: the change is further than 300 m and more than 12 s away at this speed (§8.4a).
        if (distanceM > maxOf(minM, speedMps * leadS)) return false
        // The change's own position: a projection along the heading moves when the road turns
        // (bench 2026-10-23: the same 50 said twice, 3 s apart, after a right turn).
        val (cLat, cLon) = if (!changeLat.isNaN() && !changeLon.isNaN()) changeLat to changeLon else project(lat, lon, headingDeg, distanceM)
        // The same limit within 200 m of the last announced change is the same change, already said.
        if (lastMph == newMph && Geo.distanceM(lastLat, lastLon, cLat, cLon) < sameChangeM) return false
        lastMph = newMph
        lastLat = cLat
        lastLon = cLon
        return true
    }

    /** Forget the last announcement (a new route, a stop). */
    fun reset() {
        lastMph = null
    }

    companion object {
        /**
         * A point [distanceM] from (lat, lon) along [bearingDeg]; flat-earth, fine under a few km.
         *
         * Parameters: degrees, degrees true, metres. 111,320 m is one degree of latitude; longitude shrinks by cos(lat).
         */
        fun project(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
            val b = Math.toRadians(bearingDeg)
            val dLat = distanceM * cos(b) / 111_320.0
            val dLon = distanceM * sin(b) / (111_320.0 * cos(Math.toRadians(lat)))
            return (lat + dLat) to (lon + dLon)
        }

        /** "Speed limit changes to thirty ahead": the usual UK limits as words, others as digits. */
        fun text(mph: Int): String = "Speed limit changes to ${WORDS[mph] ?: mph.toString()} ahead"

        private val WORDS = mapOf(
            10 to "ten", 15 to "fifteen", 20 to "twenty", 30 to "thirty", 40 to "forty", 50 to "fifty",
            60 to "sixty", 70 to "seventy",
        )
    }
}

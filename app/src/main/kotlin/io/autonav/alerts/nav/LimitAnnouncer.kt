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
 */
class LimitAnnouncer(
    private val minM: Double = 300.0,
    private val leadS: Double = 12.0,
    private val sameChangeM: Double = 200.0,
) {
    private var lastMph: Int? = null
    private var lastLat = 0.0
    private var lastLon = 0.0

    /** True when [newMph] at [distanceM] ahead should be announced now. Records it when true. */
    fun shouldAnnounce(
        newMph: Int, distanceM: Double, lat: Double, lon: Double, speedMps: Double, headingDeg: Double?,
        changeLat: Double = Double.NaN, changeLon: Double = Double.NaN,
    ): Boolean {
        if (headingDeg == null) return false
        if (distanceM > maxOf(minM, speedMps * leadS)) return false
        // The change's own position: a projection along the heading moves when the road turns
        // (bench 2026-10-23: the same 50 said twice, 3 s apart, after a right turn).
        val (cLat, cLon) = if (!changeLat.isNaN() && !changeLon.isNaN()) changeLat to changeLon else project(lat, lon, headingDeg, distanceM)
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
        /** A point [distanceM] from (lat, lon) along [bearingDeg]; flat-earth, fine under a few km. */
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

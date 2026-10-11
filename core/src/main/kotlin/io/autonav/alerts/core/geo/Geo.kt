// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical-earth helpers. Accurate to well under 0.5% over the distances alerts care about (< 5 km). */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8
    const val METRES_PER_YARD = 0.9144
    const val MPS_PER_MPH = 0.44704

    private fun Double.rad() = this * PI / 180.0
    private fun Double.deg() = this * 180.0 / PI

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1).rad()
        val dLon = (lon2 - lon1).rad()
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1.rad()) * cos(lat2.rad()) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from point 1 to point 2, degrees true in [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1.rad()
        val phi2 = lat2.rad()
        val dLon = (lon2 - lon1).rad()
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return normalizeDeg(atan2(y, x).deg())
    }

    /** Normalises any angle to [0, 360). */
    fun normalizeDeg(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Smallest signed difference a − b, in (−180, 180]. */
    fun angleDiffDeg(a: Double, b: Double): Double {
        var d = normalizeDeg(a) - normalizeDeg(b)
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    fun absAngleDiffDeg(a: Double, b: Double): Double = abs(angleDiffDeg(a, b))

    /**
     * Cross-track distance (metres, unsigned) from point P to the great circle through A→B,
     * clamped to the segment: beyond either end, the distance to that end is returned.
     */
    fun distanceToSegmentM(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double,
    ): Double {
        val dAP = distanceM(aLat, aLon, pLat, pLon)
        val dAB = distanceM(aLat, aLon, bLat, bLon)
        if (dAB < 0.01) return dAP
        val brgAP = bearingDeg(aLat, aLon, pLat, pLon).rad()
        val brgAB = bearingDeg(aLat, aLon, bLat, bLon).rad()
        val dXt = asin(sin(dAP / EARTH_RADIUS_M) * sin(brgAP - brgAB)) * EARTH_RADIUS_M
        val dAt = kotlin.math.acos((cos(dAP / EARTH_RADIUS_M) / cos(dXt / EARTH_RADIUS_M)).coerceIn(-1.0, 1.0)) * EARTH_RADIUS_M
        val alongSign = if (cos(brgAP - brgAB) < 0) -1.0 else 1.0
        val along = dAt * alongSign
        return when {
            along < 0 -> dAP
            along > dAB -> distanceM(bLat, bLon, pLat, pLon)
            else -> abs(dXt)
        }
    }
}

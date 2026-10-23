// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import kotlin.math.abs

/**
 * Google's limit worked out from the SDK's "% over" (DESIGN.md §8.4) snapped to a limit that
 * exists: 20–70 mph in tens, or 20–130 km/h in tens. A steady 45 mph with a stale or small %
 * gave "45" and "35" on the bench (2026-10-23).
 */
object LimitSnap {
    private val MPH = intArrayOf(20, 30, 40, 50, 60, 70)
    private val KMH = (20..130 step 10).toList().toIntArray()

    /** The real limit nearest [estimate] (in mph when [imperial], else km/h); ties go to the lower one. */
    fun snap(estimate: Double, imperial: Boolean): Int {
        val set = if (imperial) MPH else KMH
        var best = set[0]
        for (v in set) if (abs(v - estimate) < abs(best - estimate)) best = v
        return best
    }
}

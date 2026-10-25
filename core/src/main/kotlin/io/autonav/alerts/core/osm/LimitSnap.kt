// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import kotlin.math.abs

/**
 * Google's limit worked out from the SDK's "% over" (DESIGN.md §8.4) snapped to a limit that
 * exists: 20–70 mph in tens, or 20–130 km/h in tens. A steady 45 mph with a stale or small %
 * gave "45" and "35" on the bench (2026-10-23).
 *
 * Stateless. The app's `NavSession` calls it on the main thread from the SDK's `SpeedingListener`,
 * with the engine's speed ÷ (1 + percentage over), only for an update in the last 2 s (§8.4 item 1).
 */
object LimitSnap {
    private val MPH = intArrayOf(20, 30, 40, 50, 60, 70)
    private val KMH = (20..130 step 10).toList().toIntArray()

    /** The real limit nearest [estimate] (in mph when [imperial], else km/h); ties go to the lower one (the first found). */
    fun snap(estimate: Double, imperial: Boolean): Int = (if (imperial) MPH else KMH).minBy { abs(it - estimate) }
}

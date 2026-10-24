// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

/**
 * Decides when the map has stopped following the car (DESIGN.md §8, "Follow watchdog"). Pure:
 * `MapSurface` calls [check] on each of the SDK's road-snapped fixes with the distance from the
 * camera target to that fix. It never acts while guiding or on the route preview; on the first
 * miss it re-follows; a rebuild comes only after [missesBeforeRebuild] consecutive misses at least
 * [intervalMs] apart, and the count resets as soon as the map is back within [thresholdM].
 */
class FollowWatchdog(
    private val thresholdM: Double = 500.0,
    private val intervalMs: Long = 5_000,
    private val missesBeforeRebuild: Int = 3,
    /** An SDK fix older than this says nothing about where the car is now. */
    private val maxFixAgeMs: Long = 10_000,
) {
    sealed interface Decision {
        data class None(val reason: String) : Decision
        data class Refollow(val distanceM: Float) : Decision
        data class Rebuild(val distanceM: Float) : Decision
    }

    var misses: Int = 0
        private set
    private var lastMissMs = Long.MIN_VALUE / 2

    fun check(
        nowMs: Long,
        following: Boolean,
        guiding: Boolean,
        previewShowing: Boolean,
        sdkFixAgeMs: Long?,
        distanceM: Float?,
        inPlace: Boolean? = null,
    ): Decision {
        if (!following) return skip("not following")
        if (previewShowing) return skip("preview")
        if (sdkFixAgeMs == null || distanceM == null) return Decision.None("no SDK fix")
        if (sdkFixAgeMs > maxFixAgeMs) return Decision.None("SDK fix ${sdkFixAgeMs / 1000} s old")
        // The car is followed when it is drawn inside the padded map at the locked zoom (DESIGN.md §8).
        // The camera's target sits well ahead of the car (2 km at zoom 15), so the distance is only the
        // fallback before the map has a frame, and never in guidance.
        when (inPlace) {
            true -> return skip("in place")
            false -> {}
            null -> if (guiding) return Decision.None("guiding, no frame")
                else if (distanceM <= thresholdM) return skip("within ${thresholdM.toInt()} m")
        }
        if (misses > 0 && nowMs - lastMissMs < intervalMs) return Decision.None("miss $misses, waiting")
        misses++
        lastMissMs = nowMs
        if (misses >= missesBeforeRebuild) {
            misses = 0
            return Decision.Rebuild(distanceM)
        }
        return Decision.Refollow(distanceM)
    }

    private fun skip(reason: String): Decision {
        misses = 0
        return Decision.None(reason)
    }
}

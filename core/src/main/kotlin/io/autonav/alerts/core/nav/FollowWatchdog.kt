// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import kotlin.math.abs

/**
 * Decides when the map has stopped following the car (DESIGN.md §8, "Follow watchdog"). Pure, with
 * only the miss counter as state: `MapSurface` calls [check] on the main thread for each of the
 * SDK's road-snapped fixes (never our own fix, which may be the car's GPS while the SDK follows the
 * phone's), with whether the car is drawn in place and, as a fallback, the distance from the
 * camera target to that fix. It never acts on the route preview (the SDK's overview legitimately
 * moves the camera away, §16 row 22). In free drive and in guidance alike (guidance added
 * 2026-10-24), on the first miss it re-follows; a rebuild comes only after [missesBeforeRebuild]
 * consecutive misses at least [intervalMs] apart, and the count resets as soon as the car is back
 * in place (or, with no frame, within [thresholdM]). Metres and milliseconds. "In place" is the car
 * drawn near its designed spot ([carSpot], [nearSpot]; owner, 2026-11-01), not merely on screen.
 */
class FollowWatchdog(
    // 500 m camera-target test, free drive only and only before the map has a frame; 5 s between
    // misses; a rebuild on the third; an SDK fix older than 10 s says nothing (§8 "Follow watchdog").
    private val thresholdM: Double = 500.0,
    private val intervalMs: Long = 5_000,
    private val missesBeforeRebuild: Int = 3,
    /** An SDK fix older than this says nothing about where the car is now. */
    private val maxFixAgeMs: Long = 10_000,
) {
    /** [None] with the reason to log (skips included), [Refollow] at the chosen zoom, or [Rebuild] the map on its surface. */
    sealed interface Decision {
        data class None(val reason: String) : Decision
        data class Refollow(val distanceM: Float) : Decision
        data class Rebuild(val distanceM: Float) : Decision
    }

    /** Consecutive misses so far, for the log. */
    var misses: Int = 0
        private set
    private var lastMissMs = Long.MIN_VALUE / 2

    /**
     * @param following camera-follow is on (the user hasn't panned).
     * @param guiding a route is being guided (the distance test is then not used).
     * @param previewShowing the route preview is up: never act.
     * @param sdkFixAgeMs age of the SDK's last road-snapped fix, null if none yet.
     * @param distanceM camera target to the SDK fix, metres; the fallback test only.
     * @param inPlace the car projects near its spot ([nearSpot]) at the locked zoom (`MapSurface.carInPlace`),
     *   null before the map has a frame.
     */
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

    companion object {
        /**
         * Where a followed car is drawn, as surface pixels x to y (DESIGN.md §8 "Where the car sits"): across
         * the middle of the padded map, and down it at [guidedFraction] of the padded height in guidance (the
         * SDK puts it there) or at the padded centre in free drive (whose top padding is raised so the two
         * meet). [left], [top], [right] and [bottom] are the camera padding the map was given.
         */
        fun carSpot(w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int, guiding: Boolean, guidedFraction: Float): Pair<Float, Float> {
            val x = (left + (w - right)) / 2f
            val y = if (guiding) top + guidedFraction * (h - bottom - top) else (top + (h - bottom)) / 2f
            return x to y
        }

        /**
         * Whether the car drawn at [x], [y] is in its place: within a tenth of the surface's width and a tenth
         * of its height of the spot ([carSpot]; owner, 2026-11-01). A frozen camera lets the car drift that far
         * in a few seconds at road speed, long before it leaves the screen.
         */
        fun nearSpot(x: Float, y: Float, spot: Pair<Float, Float>, w: Int, h: Int): Boolean =
            abs(x - spot.first) <= w / 10f && abs(y - spot.second) <= h / 10f
    }
}

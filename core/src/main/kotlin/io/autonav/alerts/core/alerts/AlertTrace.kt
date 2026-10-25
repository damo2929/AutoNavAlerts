// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType

/**
 * Why a POI is, or isn't, being alerted on right now (DESIGN.md §11 step 10, §8.1). One enum for the
 * trace, the marker layer and the diagnostics screens: the engine gives every candidate within 3 km a
 * verdict on each fix ([AlertEngine.classify]).
 */
enum class Verdict(
    /** The `suppressed reason=` word of §15.5. */
    val reason: String,
) {
    /** Beyond 3 km, or beyond this POI's own lookahead (its 1st trigger + 200 m) for the trace. */
    OUT_OF_RANGE("outOfRange"),
    /** More than 45° off the direction of travel (to the side), and not already tracked. */
    NOT_AHEAD("notAhead"),
    /** On our road and ahead, but its stored bearing fails the direction rule (§11 step 3). */
    WRONG_DIRECTION("direction"),
    /** Ahead, but outside the corridor: a parallel or side road. */
    OFF_ROAD("offRoad"),
    /** Would alert, but the car isn't moving (speed ≤ 2 m/s, hysteresis off at 1 m/s). */
    NOT_MOVING("notMoving"),
    /** Heading unknown: nothing can be judged. */
    NO_HEADING("noHeading"),
    /** Ahead, on our road, facing our way, moving: stages fire from here. */
    AHEAD_OK("ok"),
    /** More than 90° behind the direction of travel. */
    BEHIND("behind"),
}

/**
 * The camera direction rule's measure (DESIGN.md §11 step 3, hard rule 1), the one place it is
 * written: the degrees by which a camera's stored [bearing] misses our [heading], taken over the
 * bearings its [dirType] covers (the bearing alone for ONE_WAY, both ways for BOTH, nothing for
 * ANY: 0) and, when [toPoi] (the bearing from us to the camera) is given because the camera is
 * properly ahead, the smaller of the miss to the heading and the miss to that bearing. The
 * engine applies it on every fix and the trace reports it; a change here must keep
 * `DirectionParityTest`, `camera_30DegBend_directionPassesViaBearingToCamera` and
 * `oppositeCameraStillRejectedOnABend` passing. Degrees in and out; pure.
 */
fun directionMismatchDeg(bearing: Double, dirType: DirectionType, heading: Double, toPoi: Double? = null): Double {
    fun miss(x: Double): Double {
        val h = Geo.absAngleDiffDeg(x, heading)
        return if (toPoi != null) minOf(h, Geo.absAngleDiffDeg(x, toPoi)) else h
    }
    return when (dirType) {
        DirectionType.ANY -> 0.0
        DirectionType.ONE_WAY -> miss(bearing)
        DirectionType.BOTH -> minOf(miss(bearing), miss(bearing + 180.0))
    }
}

/** Why a POI's per-approach state was dropped (§11 step 7). */
enum class ClearReason {
    /** 100 m past the closest point. */
    PASSED,
    /** A stage fired but there was no approach for 90 s. */
    STALE,
    /** More than 2 km behind. */
    BEHIND,
    /** Out of the candidate cache for 60 s, or dropped by [AlertEngine.forgetExcept]. */
    PRUNED,
}

/**
 * The engine's diagnostic hook (§11 step 10, §15.5). Optional and zero cost when absent: the engine
 * calls it only when a POI's verdict changes inside that POI's lookahead, when an event is emitted and
 * when a state clears, never per fix.
 *
 * The app's `AlertCoordinator` implements it and writes the lines to `alert-debug.log` (the
 * `Trace` and `PASSED` tags of §15.5). Called on the engine's thread, inside [AlertEngine.onFix],
 * so an implementation must be quick and must not call back into the engine. Distances are metres,
 * angles degrees.
 */
interface AlertTrace {
    /**
     * [verdict] replaced the previous one for [poiId] inside its lookahead (or became
     * [Verdict.OUT_OF_RANGE] on leaving it). [offAxisDeg], [lateralM] and [allowedM] are NaN when they
     * weren't computed (no heading, out of range); [headingDeg] is null without a heading.
     */
    fun verdict(
        poiId: Long, verdict: Verdict, d: Double, offAxisDeg: Double, headingDeg: Double?,
        poiBearing: Float?, dirType: DirectionType, lateralM: Double, allowedM: Double,
    )

    /** An event was emitted (every kind, zone events included). */
    fun stage(event: AlertEvent)

    /** The POI's state was dropped; the app writes its `PASSED` summary here. */
    fun cleared(poiId: Long, reason: ClearReason)

    /**
     * The facing-away dimming (§8.1) switched on or off for [poiId]: [mismatchDeg] is the camera's
     * bearing against our heading, [headingAccuracyDeg] the clamped accuracy the confidence used.
     */
    fun dimmed(poiId: Long, dimmed: Boolean, mismatchDeg: Double, headingAccuracyDeg: Double) {}
}

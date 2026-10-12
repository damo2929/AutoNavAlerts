// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.poi.DirectionType

/**
 * Why a POI is, or isn't, being alerted on right now (DESIGN.md §11 step 10, §8.1). One enum for the
 * trace, the marker layer and the diagnostics screens: the engine gives every candidate within 3 km a
 * verdict on each fix ([AlertEngine.classify]).
 */
enum class Verdict {
    /** Beyond 3 km, or beyond this POI's own lookahead (its 1st trigger + 200 m) for the trace. */
    OUT_OF_RANGE,
    /** More than 45° off the direction of travel (to the side), and not already tracked. */
    NOT_AHEAD,
    /** On our road and ahead, but its stored bearing fails the direction rule (§11 step 3). */
    WRONG_DIRECTION,
    /** Ahead, but outside the corridor: a parallel or side road. */
    OFF_ROAD,
    /** Would alert, but the car isn't moving (speed ≤ 2 m/s, hysteresis off at 1 m/s). */
    NOT_MOVING,
    /** Heading unknown: nothing can be judged. */
    NO_HEADING,
    /** Ahead, on our road, facing our way, moving: stages fire from here. */
    AHEAD_OK,
    /** More than 90° behind the direction of travel. */
    BEHIND,
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

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.location

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Settings for the speed-aware GPS rate (DESIGN.md §10.2). */
data class RateCurve(
    val minMph: Double = 10.0,
    val maxMph: Double = 50.0,
    val minHz: Int = 1,
    val maxHz: Int = 10,
) {
    init {
        require(maxMph > minMph) { "maxMph must be above minMph" }
        require(maxHz >= minHz && minHz >= 1) { "need 1 <= minHz <= maxHz" }
    }

    /** Continuous target rate: minHz below minMph, linear up to maxHz at maxMph, capped there. */
    fun targetHz(mph: Double): Double = when {
        mph.isNaN() || mph < minMph -> minHz.toDouble()
        mph >= maxMph -> maxHz.toDouble()
        else -> minHz + (maxHz - minHz) * (mph - minMph) / (maxMph - minMph)
    }

    /** Whole-Hz step actually requested. Halves round up (5.5 → 6). */
    fun stepHz(mph: Double): Int = targetHz(mph).roundToInt().coerceIn(minHz, maxHz)

    companion object {
        fun intervalMs(hz: Int): Long = 1000L / hz
    }
}

/**
 * Decides when to re-issue the location request (DESIGN.md §10.3–10.4).
 * Pure logic: the caller feeds smoothed speed and fix times, and applies [Decision.newHz] to FLP.
 */
class AdaptiveRateController(
    private val curve: RateCurve = RateCurve(),
    private val holdMs: Long = 2_000,
    private val chipCheckWindowMs: Long = 60_000,
) {
    data class Decision(val newHz: Int?)

    /** The rate currently requested from the location provider. */
    var requestedHz: Int = curve.minHz
        private set

    /** Upper limit learned from the chip (null = no limit observed). */
    var chipCapHz: Int? = null
        private set

    private var pendingStep: Int? = null
    private var pendingSinceMs: Long = 0
    private var lowRateSinceMs: Long? = null

    /**
     * Feed one smoothed speed sample. Returns a non-null [Decision.newHz] only when the request
     * should change: the new step must differ and must have held continuously for [holdMs].
     */
    fun onSpeed(mph: Double, nowMs: Long): Decision {
        val curveStep = curve.stepHz(mph)
        chipCapHz?.let { if (curveStep <= it) chipCapHz = null }
        val step = curveStep.coerceAtMost(chipCapHz ?: curve.maxHz)
        if (step == requestedHz) {
            pendingStep = null
            return Decision(null)
        }
        if (pendingStep != step) {
            pendingStep = step
            pendingSinceMs = nowMs
            return Decision(null)
        }
        if (nowMs - pendingSinceMs >= holdMs) {
            requestedHz = step
            pendingStep = null
            lowRateSinceMs = null
            return Decision(step)
        }
        return Decision(null)
    }

    /**
     * Feed the measured delivery rate (moving average over ~10 s). If the chip delivers under 50% of the
     * request for [chipCheckWindowMs], drop the request to ceil(actual) and remember it as the cap.
     * The cap is cleared (in [onSpeed]) once the curve's step falls to or below it, so a later speed-up probes again.
     */
    fun onMeasuredRate(actualHz: Double, nowMs: Long): Decision {
        if (requestedHz <= curve.minHz || actualHz >= requestedHz * 0.5) {
            lowRateSinceMs = null
            return Decision(null)
        }
        val since = lowRateSinceMs ?: nowMs.also { lowRateSinceMs = it }
        if (nowMs - since < chipCheckWindowMs) return Decision(null)
        val capped = ceil(actualHz).toInt().coerceIn(curve.minHz, curve.maxHz)
        chipCapHz = capped
        lowRateSinceMs = null
        if (capped == requestedHz) return Decision(null)
        requestedHz = capped
        return Decision(capped)
    }
}

/** Median of the last three speed samples (DESIGN.md §10.3). */
class SpeedSmoother(private val window: Int = 3) {
    private val samples = ArrayDeque<Double>()

    fun add(mps: Double): Double {
        samples.addLast(mps)
        while (samples.size > window) samples.removeFirst()
        val sorted = samples.sorted()
        return sorted[sorted.size / 2]
    }

    fun reset() = samples.clear()
}

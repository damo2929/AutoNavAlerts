// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi

import kotlin.math.roundToInt

/**
 * Low bridges (DESIGN.md §6.5, owner 2026-11-01): a bridge set's height from its name, and whether it alerts for
 * the vehicle. PocketGPSWorld ships one file per signed height, so the set name carries it ("Bridge - 13ft 6in");
 * "Unknown Height" has none. Only bridges at or below the vehicle height plus the user's safety height (a slider,
 * 0 to 1 m, default [DEFAULT_MARGIN_CM]; owner 2026-11-01) alert; an unknown height
 * alerts whenever a vehicle height is set; with no vehicle height nothing alerts (the markers still show). Pure;
 * called from `AlertCoordinator` when it resolves a set, on any thread.
 */
object BridgeHeight {
    /** The safety height's default, about 3 inches, and its slider's range in centimetres (DESIGN.md §6.5). */
    const val DEFAULT_MARGIN_CM = 8
    val MARGIN_RANGE_CM = 0..100

    private const val CM_PER_INCH = 2.54
    private val FEET_INCHES = Regex("""(\d+)\s*ft(?:\s*(\d+)\s*in)?""", RegexOption.IGNORE_CASE)

    /** The signed height in centimetres from a set or file name ("Bridge - 13ft 6in" → 411); null when it has none. */
    fun parseCm(name: String): Int? {
        val m = FEET_INCHES.find(name) ?: return null
        val inches = m.groupValues[1].toInt() * 12 + (m.groupValues[2].toIntOrNull() ?: 0)
        return (inches * CM_PER_INCH).roundToInt()
    }

    /**
     * Whether a bridge of [bridgeCm] (null: unknown) alerts for a vehicle of [vehicleCm] (null: not set) with
     * [marginCm] of safety height: at or below the vehicle plus the margin, with half a centimetre for the rounding
     * of heights to whole centimetres (13ft is 396.24 cm, stored as 396).
     */
    fun alerts(bridgeCm: Int?, vehicleCm: Int?, marginCm: Int = DEFAULT_MARGIN_CM): Boolean = when {
        vehicleCm == null -> false
        bridgeCm == null -> true
        else -> bridgeCm <= vehicleCm + marginCm + ROUNDING_CM
    }

    private const val ROUNDING_CM = 0.5
}

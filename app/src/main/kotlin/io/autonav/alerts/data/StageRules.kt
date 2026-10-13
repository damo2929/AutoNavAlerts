// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

/**
 * DESIGN.md §11.1 checks on a set's alert stages, shared by the set editor and the backup restore:
 * the 2nd at least 142 m (outside the 100 yd passing alert plus 50 m), the 1st at least 100 m
 * before the 2nd. The 1st alert is never off.
 */
object StageRules {
    const val SECOND_MIN_M = 142
    const val FIRST_GAP_M = 100

    fun problem(first: StageCols, second: StageCols): String? = when {
        !first.enabled -> "The 1st alert can't be turned off."
        second.enabled && second.metres < SECOND_MIN_M -> "The 2nd alert must be at least 155 yd (142 m), outside the 100 yd passing alert."
        second.enabled && first.metres < second.metres + FIRST_GAP_M -> "The 1st alert must be at least 110 yd (100 m) before the 2nd."
        else -> null
    }
}

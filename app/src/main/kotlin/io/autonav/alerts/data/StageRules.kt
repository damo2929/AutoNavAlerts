// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

/**
 * DESIGN.md §11.1 checks on a set's alert stages, shared by the set editor (`PhoneScreens`, as the user
 * types) and the backup restore (`SettingsBackup.applySet`, which keeps the phone's stages when a file's
 * fail them): the 2nd at least 142 m (a fixed floor, well outside the passing alert's 1 s / 20 yd), the
 * 1st at least 100 m before the 2nd. The 1st alert is never off. Pure logic, safe on any thread.
 * The engine applies the same floors again at run time with the current speed (§11.1 "Validation").
 */
object StageRules {
    /** Metres: the 2nd alert's floor, `AlertEngine.SECOND_FLOOR_M` (§11.1; 155 yd). */
    const val SECOND_MIN_M = 142
    /** Metres the 1st must sit before the 2nd (§11.1; 110 yd). */
    const val FIRST_GAP_M = 100

    /**
     * The first rule the stages break, as the sentence shown to the user, or null when they pass.
     * Distances are compared in metres as stored; the text gives yards first because imperial is the default.
     */
    fun problem(first: StageCols, second: StageCols): String? = when {
        !first.enabled -> "The 1st alert can't be turned off."
        second.enabled && second.metres < SECOND_MIN_M -> "The 2nd alert must be at least 155 yd (142 m), well before the 20 yd passing alert."
        second.enabled && first.metres < second.metres + FIRST_GAP_M -> "The 1st alert must be at least 110 yd (100 m) before the 2nd."
        else -> null
    }
}

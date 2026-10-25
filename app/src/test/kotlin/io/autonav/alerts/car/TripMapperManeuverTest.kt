// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import androidx.car.app.navigation.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as Sdk

/**
 * The rest of the Navigation SDK → Car App Library manoeuvre table (DESIGN.md §8 "Navigating", §12.4) that
 * `TripMapperTest` leaves out, side by side so a left/right swap shows, and the turn card's road spacing edges.
 */
class TripMapperManeuverTest {
    private fun check(expected: Map<Int, Int>) = expected.forEach { (sdk, host) -> assertEquals("SDK $sdk", host, TripMapper.maneuverType(sdk)) }

    @Test fun destinationsTurnsAndKeeps() = check(mapOf(
        Sdk.DESTINATION to Maneuver.TYPE_DESTINATION,
        Sdk.DESTINATION_RIGHT to Maneuver.TYPE_DESTINATION_RIGHT,
        Sdk.TURN_RIGHT to Maneuver.TYPE_TURN_NORMAL_RIGHT,
        Sdk.TURN_KEEP_LEFT to Maneuver.TYPE_KEEP_LEFT,
        Sdk.TURN_SLIGHT_LEFT to Maneuver.TYPE_TURN_SLIGHT_LEFT,
        Sdk.TURN_SLIGHT_RIGHT to Maneuver.TYPE_TURN_SLIGHT_RIGHT,
        Sdk.TURN_SHARP_RIGHT to Maneuver.TYPE_TURN_SHARP_RIGHT,
        // A counter-clockwise U-turn is a left U-turn (UK and US alike: the host draws the side).
        Sdk.TURN_U_TURN_COUNTERCLOCKWISE to Maneuver.TYPE_U_TURN_LEFT,
    ))

    @Test fun mergesAndForks() = check(mapOf(
        Sdk.MERGE_LEFT to Maneuver.TYPE_MERGE_LEFT,
        Sdk.MERGE_RIGHT to Maneuver.TYPE_MERGE_RIGHT,
        Sdk.FORK_LEFT to Maneuver.TYPE_FORK_LEFT,
        Sdk.FORK_RIGHT to Maneuver.TYPE_FORK_RIGHT,
    ))

    @Test fun onRampsKeepTheirSideAndSharpness() = check(mapOf(
        Sdk.ON_RAMP_LEFT to Maneuver.TYPE_ON_RAMP_NORMAL_LEFT,
        Sdk.ON_RAMP_RIGHT to Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT,
        Sdk.ON_RAMP_KEEP_RIGHT to Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT,
        Sdk.ON_RAMP_SLIGHT_LEFT to Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT,
        Sdk.ON_RAMP_SLIGHT_RIGHT to Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT,
        Sdk.ON_RAMP_SHARP_LEFT to Maneuver.TYPE_ON_RAMP_SHARP_LEFT,
        Sdk.ON_RAMP_SHARP_RIGHT to Maneuver.TYPE_ON_RAMP_SHARP_RIGHT,
        Sdk.ON_RAMP_U_TURN_CLOCKWISE to Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT,
    ))

    @Test fun offRampsFoldSharpAndUTurnIntoThePlainRampOfTheirSide() = check(mapOf(
        Sdk.OFF_RAMP_LEFT to Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT,
        Sdk.OFF_RAMP_RIGHT to Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
        Sdk.OFF_RAMP_KEEP_LEFT to Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT,
        Sdk.OFF_RAMP_UNSPECIFIED to Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
        Sdk.OFF_RAMP_SLIGHT_LEFT to Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT,
        Sdk.OFF_RAMP_SLIGHT_RIGHT to Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT,
        Sdk.OFF_RAMP_SHARP_RIGHT to Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
        Sdk.OFF_RAMP_U_TURN_COUNTERCLOCKWISE to Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT,
    ))

    @Test fun ferries() = check(mapOf(Sdk.FERRY_BOAT to Maneuver.TYPE_FERRY_BOAT))

    @Test fun everyLeftHasItsRightCounterpart() {
        val pairs = listOf(
            Sdk.TURN_LEFT to Sdk.TURN_RIGHT, Sdk.TURN_SLIGHT_LEFT to Sdk.TURN_SLIGHT_RIGHT, Sdk.TURN_SHARP_LEFT to Sdk.TURN_SHARP_RIGHT,
            Sdk.TURN_KEEP_LEFT to Sdk.TURN_KEEP_RIGHT, Sdk.MERGE_LEFT to Sdk.MERGE_RIGHT, Sdk.FORK_LEFT to Sdk.FORK_RIGHT,
            Sdk.ON_RAMP_LEFT to Sdk.ON_RAMP_RIGHT, Sdk.OFF_RAMP_LEFT to Sdk.OFF_RAMP_RIGHT, Sdk.DESTINATION_LEFT to Sdk.DESTINATION_RIGHT,
        )
        // A left and its right never map to the same host type.
        for ((l, r) in pairs) assertNotEquals("$l/$r", TripMapper.maneuverType(l), TripMapper.maneuverType(r))
    }

    @Test fun unrecognisedCodesAreUnknown() {
        assertEquals(Maneuver.TYPE_UNKNOWN, TripMapper.maneuverType(Int.MAX_VALUE))
        assertEquals(Maneuver.TYPE_UNKNOWN, TripMapper.maneuverType(Int.MIN_VALUE))
    }

    @Test fun roadSpacingEdges() {
        // Every slash in a road with three names, and tabs around it.
        assertEquals("A1 / A61 / Main Rd", TripMapper.spacedRoad("A1/A61 /Main Rd"))
        assertEquals("A1 / A61", TripMapper.spacedRoad("A1\t/\tA61"))
        // Already spaced: unchanged.
        assertEquals("Main Rd / A61", TripMapper.spacedRoad("Main Rd / A61"))
        // Inner spaces of a name are kept; outer ones trimmed.
        assertEquals("Old  Mill Lane", TripMapper.spacedRoad(" Old  Mill Lane "))
        assertEquals("", TripMapper.spacedRoad("   "))
    }
}

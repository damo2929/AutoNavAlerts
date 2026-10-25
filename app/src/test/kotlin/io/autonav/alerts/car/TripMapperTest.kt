// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import androidx.car.app.navigation.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Test
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as Sdk

/** Pins the Navigation SDK → Car App Library manoeuvre mapping and the road-name spacing of the turn card (DESIGN.md §8 "Looking like Google Maps", §12.4). */
class TripMapperTest {
    @Test fun everyRoundaboutEntryFoldsIntoEnterAndExitForItsDirection() {
        val cw = listOf(
            Sdk.ROUNDABOUT_CLOCKWISE, Sdk.ROUNDABOUT_STRAIGHT_CLOCKWISE, Sdk.ROUNDABOUT_LEFT_CLOCKWISE, Sdk.ROUNDABOUT_RIGHT_CLOCKWISE,
            Sdk.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE, Sdk.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE, Sdk.ROUNDABOUT_SHARP_LEFT_CLOCKWISE,
            Sdk.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE, Sdk.ROUNDABOUT_U_TURN_CLOCKWISE,
        )
        val ccw = listOf(
            Sdk.ROUNDABOUT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_LEFT_COUNTERCLOCKWISE,
            Sdk.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE,
            Sdk.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE,
        )
        cw.forEach { assertEquals("$it", Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, TripMapper.maneuverType(it)) }
        ccw.forEach { assertEquals("$it", Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, TripMapper.maneuverType(it)) }
        assertEquals(Maneuver.TYPE_ROUNDABOUT_EXIT_CW, TripMapper.maneuverType(Sdk.ROUNDABOUT_EXIT_CLOCKWISE))
        assertEquals(Maneuver.TYPE_ROUNDABOUT_EXIT_CCW, TripMapper.maneuverType(Sdk.ROUNDABOUT_EXIT_COUNTERCLOCKWISE))
    }

    @Test fun rampsTurnsAndTheRestMapAsBefore() {
        val expected = mapOf(
            Sdk.DEPART to Maneuver.TYPE_DEPART, Sdk.DESTINATION_LEFT to Maneuver.TYPE_DESTINATION_LEFT, Sdk.STRAIGHT to Maneuver.TYPE_STRAIGHT,
            Sdk.TURN_LEFT to Maneuver.TYPE_TURN_NORMAL_LEFT, Sdk.TURN_KEEP_RIGHT to Maneuver.TYPE_KEEP_RIGHT, Sdk.TURN_SHARP_LEFT to Maneuver.TYPE_TURN_SHARP_LEFT,
            Sdk.TURN_U_TURN_CLOCKWISE to Maneuver.TYPE_U_TURN_RIGHT, Sdk.MERGE_UNSPECIFIED to Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED,
            Sdk.ON_RAMP_KEEP_LEFT to Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, Sdk.ON_RAMP_UNSPECIFIED to Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT,
            Sdk.ON_RAMP_U_TURN_COUNTERCLOCKWISE to Maneuver.TYPE_ON_RAMP_U_TURN_LEFT, Sdk.OFF_RAMP_KEEP_RIGHT to Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
            Sdk.OFF_RAMP_SHARP_LEFT to Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT, Sdk.OFF_RAMP_U_TURN_CLOCKWISE to Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
            Sdk.FERRY_TRAIN to Maneuver.TYPE_FERRY_TRAIN, Sdk.NAME_CHANGE to Maneuver.TYPE_NAME_CHANGE, -1 to Maneuver.TYPE_UNKNOWN,
        )
        expected.forEach { (sdk, host) -> assertEquals("$sdk", host, TripMapper.maneuverType(sdk)) }
    }

    @Test fun roadWithTwoNamesIsSpacedLikeGoogleMaps() {
        assertEquals("Main Rd / A61", TripMapper.spacedRoad("Main Rd/A61"))
        assertEquals("Main Rd / A61", TripMapper.spacedRoad("  Main Rd  /  A61 "))
        assertEquals("A61", TripMapper.spacedRoad("A61"))
    }
}

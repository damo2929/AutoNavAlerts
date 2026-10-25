// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import io.autonav.alerts.R
import java.util.TimeZone
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as Sdk

/**
 * Navigation SDK turn-by-turn → Car App Library `RoutingInfo` / `TravelEstimate` / `Trip`
 * (DESIGN.md §8 "Navigating", §12.4). Pure mapping, no state: `HomeScreen` makes one per
 * template for the host's turn card and travel-estimate card, `TripPublisher` one per update for
 * the cluster trip, and `RoutePreviewScreen` one for its distance spans. All on the main thread,
 * though nothing here needs it. A new instance is cheap, so none is kept.
 *
 * The turn card's text is the road only, as Google Maps shows it ("Main Rd / A61"), and the full
 * instruction only when the step has no road (§8 "Looking like Google Maps"); the spoken
 * directions keep the full instruction (§12.3).
 *
 * @param imperial miles and yards when true, kilometres and metres otherwise (§13 units).
 */
class TripMapper(private val carContext: CarContext, private val imperial: Boolean) {

    /**
     * The host's turn card for [info]: the current step with the distance to it, and the next
     * step when the SDK has one. Null when there is no current step (before the first update).
     */
    fun routingInfo(info: NavInfo): RoutingInfo? {
        val current = info.currentStep ?: return null
        val b = RoutingInfo.Builder()
            .setCurrentStep(step(current), distance(info.distanceToCurrentStepMeters ?: 0))
        info.remainingSteps?.firstOrNull()?.let { b.setNextStep(step(it)) }
        return b.build()
    }

    /**
     * What the host's turn card and travel-estimate card show for [info], as text: the template is
     * re-sent only when this changes (DESIGN.md §8, no shake; a card rebuilt every second lost presses
     * on its X). Distances as displayed, time in whole minutes, arrival to the minute.
     */
    fun displayKey(info: NavInfo): String {
        // Compare the distance as the host will display it (rounded by `distance`), not the raw metres,
        // so a metre of GPS drift doesn't count as a change (hard rule 3, no shake).
        fun shown(m: Int?) = m?.let { distance(it).let { d -> "${d.displayDistance}${d.displayUnit}" } } ?: "-"
        val cur = info.currentStep
        val next = info.remainingSteps?.firstOrNull()
        val secs = info.timeToFinalDestinationSeconds
        // The arrival time to the minute: the card shows "HH:MM", so a second's change is no change.
        val arrivalMin = secs?.let { (System.currentTimeMillis() / 1000 + it) / 60 }
        return listOf(
            info.navState, cur?.maneuver, cur?.let { step(it).cue }, shown(info.distanceToCurrentStepMeters),
            next?.maneuver, next?.let { step(it).cue }, shown(info.distanceToFinalDestinationMeters), secs?.div(60), arrivalMin,
        ).joinToString("|")
    }

    /**
     * The trip's remaining distance, time and arrival for the host's travel-estimate card (the one
     * with Google's X, §8). Null until the SDK knows both the distance and the time to the
     * destination. The arrival is wall-clock time in the phone's zone.
     */
    fun travelEstimate(info: NavInfo): TravelEstimate? {
        val meters = info.distanceToFinalDestinationMeters ?: return null
        val seconds = info.timeToFinalDestinationSeconds ?: return null
        val arrival = DateTimeWithZone.create(System.currentTimeMillis() + seconds * 1000L, TimeZone.getDefault())
        return TravelEstimate.Builder(distance(meters), arrival).setRemainingTimeSeconds(seconds.toLong()).build()
    }

    /**
     * The trip for the host's cluster and rail (DESIGN.md §12.4): the current step with its
     * estimate, the next step as a second `Step` (clusters that show two manoeuvres can), the
     * destination with the trip's estimate, and the current road.
     */
    fun trip(info: NavInfo, destinationName: String?): Trip? {
        val current = info.currentStep ?: return null
        val estimate = travelEstimate(info) ?: return null
        val toStepM = info.distanceToCurrentStepMeters ?: 0
        val toStepS = info.timeToCurrentStepSeconds ?: 0
        val b = Trip.Builder().addStep(step(current), stepEstimate(toStepM, toStepS))
        // The next step's estimate is measured from the car, so it is the current step's plus the leg between them.
        info.remainingSteps?.firstOrNull()?.let { next ->
            b.addStep(step(next), stepEstimate(toStepM + (next.distanceFromPrevStepMeters ?: 0), toStepS + (next.timeFromPrevStepSeconds ?: 0)))
        }
        return b.addDestination(Destination.Builder().setName(destinationName ?: "Destination").build(), estimate)
            .apply { current.simpleRoadName?.let { setCurrentRoad(it) } }
            .build()
    }

    private fun stepEstimate(meters: Int, seconds: Int): TravelEstimate = TravelEstimate.Builder(
        distance(meters.coerceAtLeast(0)),
        DateTimeWithZone.create(System.currentTimeMillis() + seconds.coerceAtLeast(0) * 1000L, TimeZone.getDefault()),
    ).setRemainingTimeSeconds(seconds.coerceAtLeast(0).toLong()).build()

    // One host `Step` from one SDK step: the SDK's own manoeuvre bitmap when it gives one (so the
    // car's arrow matches the SDK's), else our generic navigate icon; the host's manoeuvre type for
    // clusters that draw their own arrows.
    private fun step(s: StepInfo): Step {
        val icon = s.maneuverBitmap?.let { CarIcon.Builder(IconCompat.createWithBitmap(it)).build() }
            ?: CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_action_navigate)).build()
        val type = maneuverType(s.maneuver)
        val m = Maneuver.Builder(type).setIcon(icon)
        // The host requires an exit number on the enter-and-exit roundabout types and rejects 0.
        if (type in ROUNDABOUT_WITH_EXIT) m.setRoundaboutExitNumber((s.roundaboutTurnNumber ?: 1).coerceAtLeast(1))
        // The road only, as Google Maps shows it ("Main Rd / A61"); the full instruction when there's no road (DESIGN.md §8).
        val cue = s.simpleRoadName?.takeIf { it.isNotBlank() }?.let(::spacedRoad) ?: s.fullInstructionText ?: ""
        return Step.Builder(cue).setManeuver(m.build()).apply { s.simpleRoadName?.let { setRoad(it) } }.build()
    }

    /**
     * [meters] as the host displays it: whole miles from 10 mi, tenths of a mile from 0.2 mi, else
     * yards to the nearest 10; metric the same way with kilometres and metres to the nearest 10.
     * The host formats the number and unit itself from the `Distance`.
     */
    fun distance(meters: Int): Distance = distance(meters, imperial)

    companion object {
        /** [TripMapper.distance] for either unit system; in the companion for `TripMapperDistanceTest`. */
        fun distance(meters: Int, imperial: Boolean): Distance = if (imperial) {
            val miles = meters / 1609.344
            when {
                miles >= 10 -> Distance.create(Math.round(miles).toDouble(), Distance.UNIT_MILES)
                miles >= 0.2 -> Distance.create(Math.round(miles * 10) / 10.0, Distance.UNIT_MILES_P1)
                else -> Distance.create((Math.round(meters / 0.9144 / 10) * 10).toDouble(), Distance.UNIT_YARDS)
            }
        } else {
            when {
                meters >= 10_000 -> Distance.create(Math.round(meters / 1000.0).toDouble(), Distance.UNIT_KILOMETERS)
                meters >= 1_000 -> Distance.create(Math.round(meters / 100.0) / 10.0, Distance.UNIT_KILOMETERS_P1)
                else -> Distance.create((Math.round(meters / 10.0) * 10).toDouble(), Distance.UNIT_METERS)
            }
        }

        /** "Main Rd/A61" → "Main Rd / A61", as Google Maps writes a road with two names. */
        fun spacedRoad(road: String): String = road.trim().replace(Regex("\\s*/\\s*"), " / ")

        private val ROUNDABOUT_WITH_EXIT = setOf(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW)

        /**
         * The Car App Library manoeuvre type for a Navigation SDK manoeuvre code [m]. The host's set
         * is smaller than the SDK's, so the sharp and U-turn off-ramps fold into the plain off-ramp
         * of their side, and every roundabout entry folds into enter-and-exit for its direction (the
         * exit number carries the detail). Anything unmapped is `TYPE_UNKNOWN`, which the host
         * still draws with the SDK's bitmap.
         */
        fun maneuverType(m: Int): Int = when (m) {
            Sdk.DEPART -> Maneuver.TYPE_DEPART
            Sdk.DESTINATION -> Maneuver.TYPE_DESTINATION
            Sdk.DESTINATION_LEFT -> Maneuver.TYPE_DESTINATION_LEFT
            Sdk.DESTINATION_RIGHT -> Maneuver.TYPE_DESTINATION_RIGHT
            Sdk.STRAIGHT -> Maneuver.TYPE_STRAIGHT
            Sdk.TURN_LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            Sdk.TURN_RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            Sdk.TURN_KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
            Sdk.TURN_KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
            Sdk.TURN_SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            Sdk.TURN_SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            Sdk.TURN_SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            Sdk.TURN_SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            Sdk.TURN_U_TURN_CLOCKWISE -> Maneuver.TYPE_U_TURN_RIGHT
            Sdk.TURN_U_TURN_COUNTERCLOCKWISE -> Maneuver.TYPE_U_TURN_LEFT
            Sdk.MERGE_LEFT -> Maneuver.TYPE_MERGE_LEFT
            Sdk.MERGE_RIGHT -> Maneuver.TYPE_MERGE_RIGHT
            Sdk.MERGE_UNSPECIFIED -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            Sdk.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
            Sdk.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
            Sdk.ON_RAMP_LEFT, Sdk.ON_RAMP_KEEP_LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
            Sdk.ON_RAMP_RIGHT, Sdk.ON_RAMP_KEEP_RIGHT, Sdk.ON_RAMP_UNSPECIFIED -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
            Sdk.ON_RAMP_SLIGHT_LEFT -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
            Sdk.ON_RAMP_SLIGHT_RIGHT -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
            Sdk.ON_RAMP_SHARP_LEFT -> Maneuver.TYPE_ON_RAMP_SHARP_LEFT
            Sdk.ON_RAMP_SHARP_RIGHT -> Maneuver.TYPE_ON_RAMP_SHARP_RIGHT
            Sdk.ON_RAMP_U_TURN_CLOCKWISE -> Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT
            Sdk.ON_RAMP_U_TURN_COUNTERCLOCKWISE -> Maneuver.TYPE_ON_RAMP_U_TURN_LEFT
            Sdk.OFF_RAMP_LEFT, Sdk.OFF_RAMP_KEEP_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
            Sdk.OFF_RAMP_RIGHT, Sdk.OFF_RAMP_KEEP_RIGHT, Sdk.OFF_RAMP_UNSPECIFIED -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            Sdk.OFF_RAMP_SLIGHT_LEFT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
            Sdk.OFF_RAMP_SLIGHT_RIGHT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
            Sdk.OFF_RAMP_SHARP_LEFT, Sdk.OFF_RAMP_U_TURN_COUNTERCLOCKWISE -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
            Sdk.OFF_RAMP_SHARP_RIGHT, Sdk.OFF_RAMP_U_TURN_CLOCKWISE -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            Sdk.ROUNDABOUT_CLOCKWISE, Sdk.ROUNDABOUT_STRAIGHT_CLOCKWISE, Sdk.ROUNDABOUT_LEFT_CLOCKWISE, Sdk.ROUNDABOUT_RIGHT_CLOCKWISE,
            Sdk.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE, Sdk.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE, Sdk.ROUNDABOUT_SHARP_LEFT_CLOCKWISE,
            Sdk.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE, Sdk.ROUNDABOUT_U_TURN_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
            Sdk.ROUNDABOUT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_LEFT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE,
            Sdk.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE,
            Sdk.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE, Sdk.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
            Sdk.ROUNDABOUT_EXIT_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_EXIT_CW
            Sdk.ROUNDABOUT_EXIT_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
            Sdk.FERRY_BOAT -> Maneuver.TYPE_FERRY_BOAT
            Sdk.FERRY_TRAIN -> Maneuver.TYPE_FERRY_TRAIN
            Sdk.NAME_CHANGE -> Maneuver.TYPE_NAME_CHANGE
            else -> Maneuver.TYPE_UNKNOWN
        }
    }
}

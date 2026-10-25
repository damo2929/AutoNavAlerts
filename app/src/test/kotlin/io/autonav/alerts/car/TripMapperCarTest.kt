// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import android.app.Application
import android.graphics.Bitmap
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.testing.TestCarContext
import androidx.core.graphics.drawable.IconCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import io.autonav.alerts.R
import io.autonav.alerts.testing.apiLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as Sdk

/**
 * `TripMapper` against a real `CarContext` (DESIGN.md §8 "Navigating" and "Looking like Google Maps", §12.4): the
 * distances the host's turn card, travel-estimate card and cluster show (whole miles from 10, tenths from 0.2,
 * yards to the nearest 10; the metric twin), the road-only cue, the roundabout exit the host insists on, the trip
 * with its second step for two-manoeuvre clusters, and the display key that decides a re-send (hard rule 3: a
 * metre of drift is no change). JVM test thread.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class TripMapperCarTest {
    private val car = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext()).apply { apiLevel(7) }
    private val miles = TripMapper(car, imperial = true)
    private val km = TripMapper(car, imperial = false)

    private fun step(road: String? = "Main Rd/A61", text: String? = "Turn left onto Main Rd", maneuver: Int = Sdk.TURN_LEFT, block: StepInfo.Builder.() -> Unit = {}) =
        StepInfo.builder().setManeuver(maneuver).setSimpleRoadName(road).setFullInstructionText(text).apply(block).build()

    private fun info(
        state: Int = NavState.ENROUTE, current: StepInfo? = step(), next: StepInfo? = null,
        toStep: Int? = 150, toStepS: Int? = 20, toDest: Int? = 12_000, toDestS: Int? = 900,
    ): NavInfo = NavInfo.builder().setNavState(state).setCurrentStep(current)
        .setRemainingSteps(next?.let { arrayOf(it) } ?: emptyArray())
        .setDistanceToCurrentStepMeters(toStep).setTimeToCurrentStepSeconds(toStepS)
        .setDistanceToFinalDestinationMeters(toDest).setTimeToFinalDestinationSeconds(toDestS).build()

    private fun Distance.shown() = displayDistance to displayUnit

    @Test fun imperialWholeMilesFromTen() {
        assertEquals(10.0 to Distance.UNIT_MILES, miles.distance(16_094).shown())
        assertEquals(15.0 to Distance.UNIT_MILES, miles.distance(24_140).shown())
    }

    @Test fun imperialTenthsFromAFifthOfAMileToTen() {
        assertEquals(9.9 to Distance.UNIT_MILES_P1, miles.distance(16_000).shown())
        assertEquals(0.2 to Distance.UNIT_MILES_P1, miles.distance(322).shown())
        assertEquals(3.2 to Distance.UNIT_MILES_P1, miles.distance(5_150).shown())
    }

    @Test fun imperialYardsRoundedToTheNearestTenBelowAFifth() {
        assertEquals(350.0 to Distance.UNIT_YARDS, miles.distance(321).shown())
        assertEquals(110.0 to Distance.UNIT_YARDS, miles.distance(100).shown())
        assertEquals(0.0 to Distance.UNIT_YARDS, miles.distance(0).shown())
    }

    @Test fun metricWholeKilometresFromTen() {
        assertEquals(10.0 to Distance.UNIT_KILOMETERS, km.distance(10_000).shown())
        assertEquals(124.0 to Distance.UNIT_KILOMETERS, km.distance(123_600).shown())
    }

    @Test fun metricTenthsFromOneKilometre() {
        assertEquals(1.0 to Distance.UNIT_KILOMETERS_P1, km.distance(1_000).shown())
        assertEquals(10.0 to Distance.UNIT_KILOMETERS_P1, km.distance(9_999).shown())
        assertEquals(2.5 to Distance.UNIT_KILOMETERS_P1, km.distance(2_460).shown())
    }

    @Test fun metricMetresToTheNearestTenBelowAKilometre() {
        assertEquals(990.0 to Distance.UNIT_METERS, km.distance(994).shown())
        assertEquals(10.0 to Distance.UNIT_METERS, km.distance(5).shown())
        assertEquals(0.0 to Distance.UNIT_METERS, km.distance(4).shown())
        // 995–999 m rounds up to "1000 m" rather than switching to "1.0 km" (pinned as built).
        assertEquals(1000.0 to Distance.UNIT_METERS, km.distance(999).shown())
    }

    @Test fun turnCardIsTheRoadOnlyWithTheSlashSpacedAndTheDistanceToTheStep() {
        val r = miles.routingInfo(info())!!
        assertEquals("Main Rd / A61", r.currentStep!!.cue.toString())
        assertEquals("Main Rd/A61", r.currentStep!!.road.toString())
        assertEquals(160.0 to Distance.UNIT_YARDS, r.currentDistance!!.shown())
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, r.currentStep!!.maneuver!!.type)
        assertNull("no next step from the SDK, none on the card", r.nextStep)
    }

    @Test fun turnCardFallsBackToTheFullInstructionWithoutARoad() {
        val dest = step(road = null, text = "Arrive at Tesco", maneuver = Sdk.DESTINATION)
        assertEquals("Arrive at Tesco", miles.routingInfo(info(current = dest))!!.currentStep!!.cue.toString())
        val blank = step(road = "  ", text = "Continue")
        assertEquals("Continue", miles.routingInfo(info(current = blank))!!.currentStep!!.cue.toString())
        val nothing = step(road = null, text = null, maneuver = Sdk.STRAIGHT)
        assertEquals("", miles.routingInfo(info(current = nothing))!!.currentStep!!.cue.toString())
    }

    @Test fun turnCardShowsTheNextStepWhenTheSdkHasOne() {
        val next = step(road = "High St", maneuver = Sdk.TURN_RIGHT)
        val r = miles.routingInfo(info(next = next))!!
        assertEquals("High St", r.nextStep!!.cue.toString())
        assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT, r.nextStep!!.maneuver!!.type)
    }

    @Test fun noTurnCardBeforeTheFirstStep() {
        assertNull(miles.routingInfo(info(current = null)))
    }

    @Test fun missingStepDistanceShowsAsZero() {
        assertEquals(0.0 to Distance.UNIT_YARDS, miles.routingInfo(info(toStep = null))!!.currentDistance!!.shown())
    }

    @Test fun roundaboutCarriesAnExitNumberNeverZero() {
        fun exit(n: Int?) = miles.routingInfo(info(current = step(maneuver = Sdk.ROUNDABOUT_LEFT_CLOCKWISE) { setRoundaboutTurnNumber(n) }))!!
            .currentStep!!.maneuver!!
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, exit(3).type)
        assertEquals(3, exit(3).roundaboutExitNumber)
        assertEquals(1, exit(null).roundaboutExitNumber)
        assertEquals(1, exit(0).roundaboutExitNumber)
    }

    @Test fun manoeuvreIconIsTheSdksBitmapElseOurNavigateIcon() {
        val plain = miles.routingInfo(info())!!.currentStep!!.maneuver!!.icon!!.icon!!
        assertEquals(IconCompat.TYPE_RESOURCE, plain.type)
        assertEquals(R.drawable.ic_action_navigate, plain.resId)
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val sdk = miles.routingInfo(info(current = step { setManeuverBitmap(bmp) }))!!.currentStep!!.maneuver!!.icon!!.icon!!
        assertEquals(IconCompat.TYPE_BITMAP, sdk.type)
    }

    @Test fun travelEstimateHasDistanceTimeAndArrival() {
        val before = System.currentTimeMillis()
        val e = miles.travelEstimate(info(toDest = 12_000, toDestS = 900))!!
        assertEquals(7.5 to Distance.UNIT_MILES_P1, e.remainingDistance!!.shown())
        assertEquals(900L, e.remainingTimeSeconds)
        val arrival = e.arrivalTimeAtDestination!!.timeSinceEpochMillis
        assertTrue(arrival in before + 900_000..System.currentTimeMillis() + 900_000)
    }

    @Test fun noTravelEstimateUntilBothDistanceAndTimeAreKnown() {
        assertNull(miles.travelEstimate(info(toDest = null)))
        assertNull(miles.travelEstimate(info(toDestS = null)))
    }

    @Test fun tripHasCurrentStepDestinationAndRoad() {
        val t = miles.trip(info(), "Tesco")!!
        assertEquals(1, t.steps.size)
        assertEquals("Main Rd / A61", t.steps[0].cue.toString())
        assertEquals(160.0 to Distance.UNIT_YARDS, t.stepTravelEstimates[0].remainingDistance!!.shown())
        assertEquals(20L, t.stepTravelEstimates[0].remainingTimeSeconds)
        assertEquals("Tesco", t.destinations.single().name.toString())
        assertEquals(900L, t.destinationTravelEstimates.single().remainingTimeSeconds)
        assertEquals("Main Rd/A61", t.currentRoad.toString())
        assertFalse(t.isLoading)
    }

    @Test fun tripAddsTheNextStepMeasuredFromTheCar() {
        val next = step(road = "High St", maneuver = Sdk.TURN_RIGHT) { setDistanceFromPrevStepMeters(400).setTimeFromPrevStepSeconds(40) }
        val t = miles.trip(info(next = next, toStep = 150, toStepS = 20), "Tesco")!!
        assertEquals(listOf("Main Rd / A61", "High St"), t.steps.map { it.cue.toString() })
        // 150 m + 400 m = 550 m = 0.34 mi; 20 s + 40 s.
        assertEquals(0.3 to Distance.UNIT_MILES_P1, t.stepTravelEstimates[1].remainingDistance!!.shown())
        assertEquals(60L, t.stepTravelEstimates[1].remainingTimeSeconds)
    }

    @Test fun tripDestinationDefaultsToDestinationAndRoadIsLeftOutWithoutOne() {
        val t = miles.trip(info(current = step(road = null, text = "Continue")), null)!!
        assertEquals("Destination", t.destinations.single().name.toString())
        assertNull(t.currentRoad)
    }

    @Test fun tripStepEstimatesNeverGoNegative() {
        val t = miles.trip(info(toStep = -30, toStepS = -5), "X")!!
        assertEquals(0.0 to Distance.UNIT_YARDS, t.stepTravelEstimates[0].remainingDistance!!.shown())
        assertEquals(0L, t.stepTravelEstimates[0].remainingTimeSeconds)
    }

    @Test fun noTripWithoutAStepOrAnEstimate() {
        assertNull(miles.trip(info(current = null), "X"))
        assertNull(miles.trip(info(toDestS = null), "X"))
    }

    @Test fun displayKeyIgnoresDriftTheCardWouldNotShow() {
        // 150 m and 149 m both show as 160 yd; 12 000 m and 12 010 m both as 7.5 mi.
        assertEquals(miles.displayKey(info(toStep = 150, toDest = 12_000)), miles.displayKey(info(toStep = 149, toDest = 12_010)))
        // Seconds within the same minute left are no change either.
        assertEquals(miles.displayKey(info(toDestS = 900)).substringBeforeLast('|'), miles.displayKey(info(toDestS = 930)).substringBeforeLast('|'))
    }

    @Test fun displayKeyChangesWithWhatTheCardsShow() {
        val base = miles.displayKey(info())
        assertNotEquals("step distance as shown", base, miles.displayKey(info(toStep = 400)))
        assertNotEquals("destination distance as shown", base, miles.displayKey(info(toDest = 13_000)))
        assertNotEquals("minutes left", base, miles.displayKey(info(toDestS = 1_000)))
        assertNotEquals("cue", base, miles.displayKey(info(current = step(road = "High St"))))
        assertNotEquals("manoeuvre", base, miles.displayKey(info(current = step(maneuver = Sdk.TURN_RIGHT))))
        assertNotEquals("next step", base, miles.displayKey(info(next = step(road = "High St"))))
        assertNotEquals("nav state", base, miles.displayKey(info(state = NavState.REROUTING)))
    }

    @Test fun displayKeyFollowsTheUnits() {
        assertNotEquals(miles.displayKey(info()), km.displayKey(info()))
    }
}

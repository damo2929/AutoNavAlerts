// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import android.app.Application
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.testing.navigation.TestNavigationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.nav.NavUpdatesService
import io.autonav.alerts.nav.RouteView
import io.autonav.alerts.testing.CarScreenRig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as Sdk

/**
 * The session's trip feed to the host (DESIGN.md §9.3, §12.4): `navigationStarted` once when guidance starts,
 * a `Trip` per update while en route (the host's travel-estimate card with Google's X, the one stop, hard rule 2;
 * the cluster's turn card), a loading trip while rerouting, nothing for a stopped or unknown state, and
 * `navigationEnded` once when guidance stops. Robolectric main looper; the Car App Library's
 * `TestNavigationManager` records what the host would receive.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class TripPublisherTest {
    private val rig = CarScreenRig()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val nm = rig.car.getCarService(NavigationManager::class.java) as TestNavigationManager
    private val publisher = TripPublisher(rig.car, rig.deps) { true }

    init {
        nm.setNavigationManagerCallback(Runnable::run, object : NavigationManagerCallback {})
        publisher.start(scope)
        rig.idle()
    }

    @After fun tearDown() {
        scope.cancel()
        NavUpdatesService.navInfo.value = null
    }

    private fun info(state: Int = NavState.ENROUTE, road: String = "Main Rd"): NavInfo = NavInfo.builder().setNavState(state)
        .setCurrentStep(StepInfo.builder().setManeuver(Sdk.TURN_LEFT).setSimpleRoadName(road).build())
        .setRemainingSteps(arrayOf(StepInfo.builder().setManeuver(Sdk.TURN_RIGHT).setSimpleRoadName("High St").build()))
        .setDistanceToCurrentStepMeters(150).setTimeToCurrentStepSeconds(20)
        .setDistanceToFinalDestinationMeters(12_000).setTimeToFinalDestinationSeconds(900).build()

    private fun guiding(on: Boolean) {
        rig.deps.setRoute(RouteView(destination = Destination("Tesco", 52.06, -1.34), guiding = on))
        rig.idle()
    }

    private fun feed(i: NavInfo?) {
        NavUpdatesService.navInfo.value = i
        rig.idle()
    }

    @Test fun nothingIsSentWithoutGuidance() {
        feed(info())
        assertEquals(0, nm.navigationStartedCount)
        assertTrue(nm.tripsSent.isEmpty())
    }

    @Test fun guidanceStartsTheHostsNavigationOnce() {
        guiding(true)
        feed(info())
        feed(info(road = "Mill Lane"))
        assertEquals(1, nm.navigationStartedCount)
        assertEquals(0, nm.navigationEndedCount)
    }

    @Test fun eachEnRouteUpdateIsATripWithBothStepsAndTheDestination() {
        guiding(true)
        feed(info())
        val trip = nm.tripsSent.last()
        assertFalse(trip.isLoading)
        assertEquals(listOf("Main Rd", "High St"), trip.steps.map { it.cue.toString() })
        assertEquals("Tesco", trip.destinations.single().name.toString())
        assertEquals("Main Rd", trip.currentRoad.toString())
    }

    @Test fun reroutingSendsALoadingTripNotAStaleTurn() {
        guiding(true)
        feed(info())
        feed(info(state = NavState.REROUTING))
        assertTrue(nm.tripsSent.last().isLoading)
    }

    @Test fun aStoppedOrUnknownStateSendsNothing() {
        guiding(true)
        val before = nm.tripsSent.size
        feed(info(state = NavState.STOPPED))
        feed(info(state = NavState.UNKNOWN))
        assertEquals(before, nm.tripsSent.size)
    }

    @Test fun guidanceStoppingEndsTheHostsNavigationOnceAndStopsTrips() {
        guiding(true)
        feed(info())
        guiding(false)
        val sent = nm.tripsSent.size
        feed(info(road = "Mill Lane"))
        assertEquals(1, nm.navigationEndedCount)
        assertEquals(sent, nm.tripsSent.size)
    }

    @Test fun stopEndsTheFeed() {
        guiding(true)
        publisher.stop()
        val sent = nm.tripsSent.size
        feed(info())
        assertEquals(sent, nm.tripsSent.size)
    }
}

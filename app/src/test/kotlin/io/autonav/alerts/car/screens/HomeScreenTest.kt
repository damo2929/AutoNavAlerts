// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import android.app.Application
import androidx.car.app.model.Action
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import io.autonav.alerts.R
import io.autonav.alerts.alerts.MarkerState
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.data.LimitSource
import io.autonav.alerts.nav.NavUnavailable
import io.autonav.alerts.nav.NavUpdatesService
import io.autonav.alerts.nav.RouteView
import io.autonav.alerts.testing.CarScreenRig
import io.autonav.alerts.testing.iconRes
import io.autonav.alerts.testing.persistent
import io.autonav.alerts.testing.privateFlow
import io.autonav.alerts.testing.tap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as Sdk

/**
 * The map screen's `NavigationTemplate` (DESIGN.md §8 "Action strip", "Map buttons", "Looking like Google Maps",
 * "Hard rule: no shake", §9.3): Search · Report · Recents · More, all persistent, with Recents leaving while a
 * route is set and **no stop action of ours** (Google's X on the host's card is the one stop, hard rule 2); the
 * host's map strip holds only Pan from car API 5 (our recenter is the one recenter); the teal turn card, the set-up
 * message, the travel estimate kept through a reroute so the X never blinks out; and the template re-sent only
 * when what the cards display changed, at most once a second with a burst folded into one (hard rule 3).
 * Robolectric main looper; the SDK's turn feed is `NavUpdatesService.navInfo`, set here by hand.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class HomeScreenTest {
    private var rig = CarScreenRig()

    @After fun clearFeed() {
        NavUpdatesService.navInfo.value = null
    }

    private fun step(road: String = "Main Rd/A61") = StepInfo.builder().setManeuver(Sdk.TURN_LEFT).setSimpleRoadName(road).build()

    private fun info(toStep: Int = 150, state: Int = NavState.ENROUTE, toDest: Int? = 12_000, toDestS: Int? = 900): NavInfo =
        NavInfo.builder().setNavState(state).setCurrentStep(step()).setRemainingSteps(emptyArray())
            .setDistanceToCurrentStepMeters(toStep).setTimeToCurrentStepSeconds(20)
            .setDistanceToFinalDestinationMeters(toDest).setTimeToFinalDestinationSeconds(toDestS).build()

    /** Home started; the navigator found no key (as on any test machine), then, unless [setup], declared ready. */
    private fun home(setup: Boolean = false): HomeScreen {
        val s = HomeScreen(rig.car, rig.host)
        rig.start(s)
        rig.awaitUntil { rig.deps.nav.unavailable.value != null }
        if (!setup) unavailable(null)
        rig.advance(1_100)
        return s
    }

    private fun unavailable(u: NavUnavailable?) {
        rig.deps.nav.privateFlow<NavUnavailable?>("_unavailable").value = u
        rig.idle()
    }

    private fun guiding(on: Boolean) {
        rig.deps.setRoute(RouteView(guiding = on))
        rig.idle()
    }

    private fun feed(i: NavInfo?) {
        NavUpdatesService.navInfo.value = i
        rig.idle()
    }

    private fun HomeScreen.template() = onGetTemplate() as NavigationTemplate

    private fun NavigationTemplate.strip() = actionStrip!!.actions.map { it.iconRes }

    @Test fun freeDriveStripIsSearchReportRecentsMoreAllPersistent() {
        val t = home().template()
        assertEquals(listOf(R.drawable.ic_action_search, R.drawable.ic_action_report, R.drawable.ic_action_recents, R.drawable.ic_action_more), t.strip())
        assertTrue(t.actionStrip!!.actions.all { it.persistent })
    }

    @Test fun noStopOrTextActionOfOursOnTheStrip() {
        guiding(true)
        val t = home().template()
        assertTrue("icon buttons only: no Stop / Done (hard rule 2)", t.actionStrip!!.actions.all { it.title == null })
    }

    @Test fun whileGuidingRecentsLeavesTheStrip() {
        val s = home()
        guiding(true)
        assertEquals(listOf(R.drawable.ic_action_search, R.drawable.ic_action_report, R.drawable.ic_action_more), s.template().strip())
        guiding(false)
        assertEquals(4, s.template().actionStrip!!.actions.size)
    }

    @Test fun fromCarApi5TheMapStripHoldsOnlyPan() {
        val actions = home().template().mapActionStrip!!.actions
        assertEquals(listOf(Action.TYPE_PAN), actions.map { it.type })
    }

    @Test fun belowCarApi5TheHostStripHasPanZoomAndTheOneRecenter() {
        rig = CarScreenRig(apiLevel = 4)
        val actions = home().template().mapActionStrip!!.actions
        assertEquals(Action.TYPE_PAN, actions[0].type)
        assertEquals(listOf(R.drawable.ic_action_zoom_in, R.drawable.ic_action_zoom_out, R.drawable.ic_action_recenter), actions.drop(1).map { it.iconRes })
    }

    @Test fun turnCardIsGoogleMapsTealByDayAndNight() {
        val c = home().template().backgroundColor!!
        assertEquals(0xFF07786D.toInt(), c.color)
        assertEquals(0xFF07786D.toInt(), c.colorDark)
    }

    @Test fun freeDriveHasNoCardAtAll() {
        val s = home()
        feed(info())
        val t = s.template()
        assertNull(t.navigationInfo)
        assertNull(t.destinationTravelEstimate)
        assertFalse(rig.host.surface.homeCard)
    }

    @Test fun eachSetUpProblemIsAMessageCardSayingWhatToDoOnThePhone() {
        val s = home(setup = true)
        val expected = mapOf(
            NavUnavailable.NO_API_KEY to "Add your Google Maps API key in the phone app's Settings.",
            NavUnavailable.TERMS_NOT_ACCEPTED to "Open the phone app once and accept the navigation terms.",
            NavUnavailable.LOCATION_PERMISSION to "Allow location access in the phone app.",
            NavUnavailable.NOT_AUTHORIZED to "The Maps API key isn't authorised for the Navigation SDK.",
            NavUnavailable.NETWORK to "No network connection.",
            NavUnavailable.UNKNOWN to "Navigation isn't available right now.",
        )
        expected.forEach { (u, text) ->
            unavailable(u)
            val m = s.template().navigationInfo as MessageInfo
            assertEquals("Finish setup on your phone", m.title.toString())
            assertEquals(text, m.text.toString())
            assertEquals(R.drawable.ic_action_report, m.image!!.icon!!.resId)
            assertTrue("the message is a card on the left", rig.host.surface.homeCard)
        }
    }

    @Test fun enRouteShowsTheTurnCardAndTheTravelEstimate() {
        val s = home()
        guiding(true)
        feed(info())
        val t = s.template()
        val r = t.navigationInfo as RoutingInfo
        assertEquals("Main Rd / A61", r.currentStep!!.cue.toString())
        assertEquals(160.0, r.currentDistance!!.displayDistance, 0.0)
        assertEquals(900L, t.destinationTravelEstimate!!.remainingTimeSeconds)
        assertTrue(rig.host.surface.homeCard)
    }

    @Test fun reroutingShowsALoadingTurnCardAndKeepsTheEstimateSoTheXStays() {
        val s = home()
        guiding(true)
        feed(info())
        s.template()
        feed(info(state = NavState.REROUTING))
        val t = s.template()
        assertTrue((t.navigationInfo as RoutingInfo).isLoading)
        assertEquals(900L, t.destinationTravelEstimate!!.remainingTimeSeconds)
    }

    @Test fun anUpdateWithoutAnEstimateKeepsTheLastOne() {
        val s = home()
        guiding(true)
        feed(info())
        s.template()
        feed(info(toStep = 90, toDestS = null))
        assertEquals(900L, s.template().destinationTravelEstimate!!.remainingTimeSeconds)
    }

    @Test fun beforeTheFirstUpdateOfARouteThereIsNoCard() {
        val s = home()
        guiding(true)
        val t = s.template()
        assertNull(t.navigationInfo)
        assertNull(t.destinationTravelEstimate)
        assertFalse(rig.host.surface.homeCard)
    }

    @Test fun aStoppedRouteLeavesNoEstimateForTheNextOne() {
        val s = home()
        guiding(true)
        feed(info())
        s.template()
        guiding(false)
        feed(info(state = NavState.REROUTING))
        guiding(true)
        val t = s.template()
        assertTrue((t.navigationInfo as RoutingInfo).isLoading)
        assertNull("the old route's estimate is gone (§9.3)", t.destinationTravelEstimate)
    }

    @Test fun noResendWhileTheCardsShowTheSame() {
        val s = home()
        guiding(true)
        // No time to the destination, so no arrival minute that the wall clock could tip over.
        feed(info(toStep = 150, toDestS = null))
        rig.advance(1_100)
        val before = rig.invalidations(s)
        feed(info(toStep = 149, toDest = 12_010, toDestS = null))
        feed(info(toStep = 148, toDest = 12_020, toDestS = null))
        rig.advance(1_100)
        assertEquals("a few metres of drift show the same: nothing re-sent", before, rig.invalidations(s))
        feed(info(toStep = 400, toDestS = null))
        rig.advance(1_100)
        assertEquals(before + 1, rig.invalidations(s))
    }

    @Test fun aBurstOfChangesIsFoldedIntoOneResendWithTheNewest() {
        val s = home()
        guiding(true)
        feed(info(toStep = 150, toDestS = null))
        rig.advance(1_100)
        val before = rig.invalidations(s)
        feed(info(toStep = 400, toDestS = null))
        feed(info(toStep = 800, toDestS = null))
        feed(info(toStep = 1_200, toDestS = null))
        rig.advance(1_100)
        val sent = rig.appManager.templatesReturned.filter { it.first === s }.drop(before)
        assertTrue("at most one a second: ${sent.size}", sent.size in 1..2)
        val last = (sent.last().second as NavigationTemplate).navigationInfo as RoutingInfo
        assertEquals("the newest content, never dropped", 0.7, last.currentDistance!!.displayDistance, 0.0)
    }

    @Test fun guidingStartingOrStoppingIsAChange() {
        val s = home()
        val before = rig.invalidations(s)
        guiding(true)
        rig.advance(1_100)
        assertEquals(before + 1, rig.invalidations(s))
        guiding(false)
        rig.advance(1_100)
        assertEquals(before + 2, rig.invalidations(s))
    }

    @Test fun searchOpensSearch() {
        val s = home()
        s.template().actionStrip!!.actions[0].tap()
        rig.idle()
        assertEquals("SearchScreen", rig.screens.screensPushed.last().javaClass.simpleName)
    }

    @Test fun recentsOpensRecentsAndMoreOpensMore() {
        val s = home()
        s.template().actionStrip!!.actions[2].tap()
        rig.idle()
        assertEquals("RecentsScreen", rig.screens.screensPushed.last().javaClass.simpleName)
        s.template().actionStrip!!.actions[3].tap()
        rig.idle()
        assertEquals("MoreScreen", rig.screens.screensPushed.last().javaClass.simpleName)
    }

    @Test fun reportWithoutAFixSaysItIsWaiting() {
        val s = home()
        s.template().actionStrip!!.actions[1].tap()
        rig.idle()
        assertEquals("Waiting for a GPS fix", rig.appManager.toastsShown.last().toString())
        assertTrue(runBlocking { rig.db.reports().withStatus(io.autonav.alerts.data.ReportStatus.DRAFT) }.isEmpty())
    }

    /** §8.5: one tap saves position, heading and the best-known limit; without the car mic it just says so (hard rule 8: no review here). */
    @Test fun reportSavesADraftAtOnceWithTheLimitAndPointsToThePhone() {
        val s = home()
        rig.host.limit = 30 to LimitSource.GOOGLE
        rig.deps.coordinator.privateFlow<MarkerState>("_markers").value = MarkerState(lastFix = Fix(52.06, -1.34, 13.4, 274.0, 0L))
        s.template().actionStrip!!.actions[1].tap()
        rig.awaitUntil { rig.appManager.toastsShown.isNotEmpty() }
        assertEquals("Camera reported. Check it on your phone after the drive.", rig.appManager.toastsShown.last().toString())
        val draft = runBlocking { rig.db.reports().withStatus(io.autonav.alerts.data.ReportStatus.DRAFT) }.single()
        assertEquals(52.06, draft.lat, 1e-9)
        assertEquals(274f, draft.heading)
        assertEquals(30, draft.speedLimit)
        assertEquals(LimitSource.GOOGLE, draft.limitSource)
        assertTrue("no screen pushed without a microphone", rig.screens.screensPushed.none { it is VoiceCommentScreen })
    }
}

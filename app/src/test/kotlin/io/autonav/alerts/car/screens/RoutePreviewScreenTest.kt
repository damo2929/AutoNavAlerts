// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import android.app.Application
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.navigation.TimeAndDistance
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.nav.EcoRoutes
import io.autonav.alerts.nav.RouteChoice
import io.autonav.alerts.nav.RouteOption
import io.autonav.alerts.nav.RouteView
import io.autonav.alerts.testing.CarScreenRig
import io.autonav.alerts.testing.lines
import io.autonav.alerts.testing.rows
import io.autonav.alerts.testing.setPrivate
import io.autonav.alerts.testing.tap
import io.autonav.alerts.testing.titleText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The route preview (DESIGN.md §8 "Route preview" row, §8.3a): the loading preview under the destination's name,
 * the driver-worded error with Back, one SDK route with its traffic word and the note that says why there is no
 * energy-efficient one, or the fastest / energy-efficient pair with the selected one ticked and named on the one
 * Go (hard rule 2: no Stop here, no Go per row); Back without Go abandons the preview, Go remembers the place in
 * Recents first. The route itself needs the Navigation SDK, so the screen's own route fields are set by hand once
 * its real `setDestination` has failed for want of a key. Robolectric main looper.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class RoutePreviewScreenTest {
    private val rig = CarScreenRig()
    private val tesco = Destination("Tesco", 52.06, -1.34, address = "Tesco, Banbury")
    /** The lifecycle of the preview under test, as the host drives it. */
    private lateinit var ctl: androidx.car.app.testing.ScreenController

    /** A preview whose real routing has finished (no key here, so an error), then given [route] as if it had worked. */
    private fun preview(route: RouteOption? = null, view: RouteView = RouteView(destination = tesco)): RoutePreviewScreen {
        rig.root()
        val s = RoutePreviewScreen(rig.car, rig.host, tesco)
        ctl = rig.start(s)
        rig.awaitUntil { s.onGetTemplate() is MessageTemplate }
        s.setPrivate("error", null)
        s.setPrivate("route", route)
        rig.deps.setRoute(view)
        return s
    }

    private fun RoutePreviewScreen.template() = onGetTemplate() as RoutePreviewNavigationTemplate

    private fun RoutePreviewScreen.rows(): List<Row> = template().itemList!!.rows

    private val fastest = EcoRoutes.Route("t1", seconds = 3_600, meters = 60_000, freeFlowSeconds = 3_000, firstJamMeters = 20_000)
    private val efficient = EcoRoutes.Route("t2", seconds = 3_900, meters = 58_000, freeFlowSeconds = 3_800)
    private val pair = RouteView(
        destination = tesco, selected = 1,
        choices = listOf(RouteChoice("Fastest", fastest, efficient = false), RouteChoice("Energy-efficient (EV)", efficient, efficient = true)),
    )

    @Test fun withoutAKeyThePreviewSaysWhyAndOffersBack() {
        rig.root()
        val s = RoutePreviewScreen(rig.car, rig.host, tesco)
        rig.start(s)
        rig.awaitUntil { s.onGetTemplate() is MessageTemplate }
        val t = s.onGetTemplate() as MessageTemplate
        assertEquals("Navigation isn't available (check the API key and terms on the phone)", t.message.toString())
        assertEquals("Tesco", t.header!!.title.toString())
        assertSame(Action.BACK, t.header!!.startHeaderAction)
        assertEquals(listOf("Back"), t.actions.map { it.title.toString() })
        t.actions.single().tap()
        rig.idle()
        assertTrue(rig.screens.screensRemoved.contains(s))
    }

    @Test fun whileLoadingItIsTheHostsLoadingPreviewUnderTheDestination() {
        val s = preview()
        s.setPrivate("loading", true)
        val t = s.template()
        assertTrue(t.isLoading)
        assertEquals("Tesco", t.header!!.title.toString())
        assertSame(Action.BACK, t.header!!.startHeaderAction)
        assertNull(t.navigateAction)
    }

    @Test fun noRouteFoundIsAMessageNotAnEmptyPreview() {
        val t = preview(route = null).onGetTemplate() as MessageTemplate
        assertEquals("Couldn't find a route there", t.message.toString())
        assertEquals(listOf("Back"), t.actions.map { it.title.toString() })
    }

    @Test fun oneSdkRouteIsOneSelectedRowWithItsTrafficAndTheNote() {
        val s = preview(RouteOption(1_500, 20_000, TimeAndDistance.DELAY_HEAVY), RouteView(destination = tesco, routeNote = "Fastest route (energy-efficient routing isn't available: no key)"))
        val t = s.template()
        val row = t.itemList!!.rows.single()
        assertEquals("  ", row.titleText)
        assertEquals(listOf("   · heavy traffic · Fastest route (energy-efficient routing isn't available: no key)"), row.lines)
        assertEquals(0, t.itemList!!.selectedIndex)
        assertNull("no tick with one route", row.image)
    }

    @Test fun theSingleRoutesTrafficWordIsTheSdksOwn() {
        assertEquals(listOf("   · some traffic"), preview(RouteOption(1_500, 20_000, TimeAndDistance.DELAY_MEDIUM)).rows().single().lines)
        assertEquals(listOf("  "), preview(RouteOption(1_500, 20_000, TimeAndDistance.DELAY_LIGHT)).rows().single().lines)
    }

    @Test fun oneGoAndNoStopOnThePreview() {
        val t = preview(RouteOption(1_500, 20_000, TimeAndDistance.DELAY_LIGHT)).template()
        assertEquals("Go", t.navigateAction!!.title.toString())
        // Debug builds only: the bench's Simulate (AGENTS.md §4); nothing else on the strip.
        assertEquals(listOf("Simulate"), t.actionStrip!!.actions.map { it.title.toString() })
    }

    @Test fun twoRoutesAreNamedOnTheTitleLineWithTrafficAndQueueBelow() {
        val rows = preview(RouteOption(0, 0, 0), pair).rows()
        assertEquals(listOf("   · Fastest", "   · Energy-efficient"), rows.map { it.titleText })
        assertEquals(listOf("   · some traffic +10 min", "Queue in 12.4 mi · ~20 min"), rows[0].lines)
        assertEquals(listOf("   · light traffic +2 min", "No queues expected"), rows[1].lines)
    }

    @Test fun theSelectedRouteIsTickedHighlightedAndNamedOnGo() {
        val t = preview(RouteOption(0, 0, 0), pair).template()
        assertEquals(1, t.itemList!!.selectedIndex)
        assertTrue("a tick or a circle on each of two rows", t.itemList!!.rows.all { it.image != null })
        assertEquals("Go · Energy-efficient", t.navigateAction!!.title.toString())
    }

    @Test fun selectingFastestNamesItOnGo() {
        val t = preview(RouteOption(0, 0, 0), pair.copy(selected = 0)).template()
        assertEquals(0, t.itemList!!.selectedIndex)
        assertEquals("Go · Fastest", t.navigateAction!!.title.toString())
    }

    @Test fun oneRouteThatIsBothIsUntickedWithAPlainGo() {
        val same = RouteView(destination = tesco, choices = listOf(RouteChoice("Fastest and most efficient", efficient, efficient = true)))
        val t = preview(RouteOption(0, 0, 0), same).template()
        val row = t.itemList!!.rows.single()
        assertEquals("   · Fastest and most efficient", row.titleText)
        assertNull(row.image)
        assertEquals("Go", t.navigateAction!!.title.toString())
    }

    @Test fun aRouteWithoutTrafficDataHasNoQueueLine() {
        val unknown = EcoRoutes.Route("t3", 600, 9_000)
        val view = RouteView(destination = tesco, choices = listOf(RouteChoice("Fastest", unknown, false), RouteChoice("Energy-efficient (EV)", unknown, true)))
        val row = preview(RouteOption(0, 0, 0), view).rows()[0]
        assertEquals(listOf("  "), row.lines)
    }

    @Test fun aSwitchTheSdkRefusesSaysSoAndResendsOnce() {
        val s = preview(RouteOption(0, 0, 0), pair)
        val before = rig.invalidations(s)
        s.template().itemList!!.onSelectedDelegate!!.sendSelected(0, object : androidx.car.app.OnDoneCallback {})
        rig.awaitUntil { rig.appManager.toastsShown.isNotEmpty() }
        assertEquals("Couldn't switch to that route", rig.appManager.toastsShown.last().toString())
        assertEquals(before + 1, rig.invalidations(s))
    }

    @Test fun onScreenTheWatchdogStandsDownAndTheBenchCanFindIt() {
        val s = preview()
        assertTrue(rig.host.surface.previewShowing)
        assertSame(s, RoutePreviewScreen.showing)
        ctl.moveToState(Lifecycle.State.CREATED)
        assertFalse(rig.host.surface.previewShowing)
        assertNull(RoutePreviewScreen.showing)
    }

    @Test fun backWithoutGoAbandonsThePreview() {
        val s = preview(RouteOption(0, 0, 0))
        assertNotNull(rig.deps.guide.destination.value)
        ctl.moveToState(Lifecycle.State.DESTROYED)
        assertNull("the preview's route is cleared (§8)", rig.deps.guide.destination.value)
    }

    @Test fun goRemembersThePlaceInRecentsThenPopsToTheMapAndKeepsTheRoute() {
        val s = preview(RouteOption(0, 0, 0))
        s.template().navigateAction!!.tap()
        rig.awaitUntil { rig.screens.screensRemoved.contains(s) }
        val recent = runBlocking { rig.db.places().list() }.single()
        assertEquals("Tesco", recent.name)
        assertEquals("Tesco, Banbury", recent.address)
        assertNotNull("Go is not an abandon", rig.deps.guide.destination.value)
    }
}

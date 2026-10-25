// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import android.app.Application
import androidx.car.app.model.Action
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.R
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.alerts.SpeechScheduler
import io.autonav.alerts.data.PlaceEntity
import io.autonav.alerts.data.PlaceKind
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.PoiSourceType
import io.autonav.alerts.data.StageCols
import io.autonav.alerts.testing.CarScreenRig
import io.autonav.alerts.testing.actionsFor
import io.autonav.alerts.testing.field
import io.autonav.alerts.testing.headerTitle
import io.autonav.alerts.testing.iconRes
import io.autonav.alerts.testing.lines
import io.autonav.alerts.testing.list
import io.autonav.alerts.testing.privateFlow
import io.autonav.alerts.testing.rows
import io.autonav.alerts.testing.rowsFor
import io.autonav.alerts.testing.switch
import io.autonav.alerts.testing.tap
import io.autonav.alerts.testing.titleText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The list screens pushed from Home and More, as templates (DESIGN.md §8 screen table, §8.2, §8.3a, §12.3):
 * Route options' two toggles, POI layers' one toggle per set with its icon and alerts state, the speech records
 * newest first, Recents with its delete action from car API 6 and Clear all behind a confirmation, the older
 * hosts' place detail, and the Yes / No question. Each `LoadingListScreen` shows the host's spinner until its
 * load and is re-sent once per load (hard rule 3); every row that is shown does something or shows data
 * (hard rule 2). Robolectric main looper; Room in memory, DataStore on its own thread.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ListScreensTest {
    private val rig = CarScreenRig()

    private fun set(name: String, enabled: Boolean = true, alerts: Boolean = true, count: Int = 10) = PoiSetEntity(
        name = name, source = PoiSourceType.FILE, format = "KML:$name", enabled = enabled, alertsEnabled = alerts,
        defaultIconId = "builtin:camera_fixed", firstTemplate = "{category}", secondTemplate = "{category}", passingTemplate = "{category}",
        firstAlert = StageCols(true, DistanceMode.TIME, 242, null), secondAlert = StageCols(true, DistanceMode.TIME, 142, null),
        poiCount = count, lastUpdated = 0,
    )

    private fun place(name: String, kind: PlaceKind = PlaceKind.RECENT, used: Long = 0, address: String? = "$name, Banbury") =
        PlaceEntity(placeId = null, name = name, address = address, lat = 52.06, lon = -1.34, kind = kind, lastUsedAt = used)

    private fun record(kind: AlertKind, outcome: SpeechScheduler.Outcome, poi: Long = 7, waited: Long? = null, by: AlertKind? = null, detail: String? = null, at: Long = 1_000) =
        SpeechScheduler.Record(SpeechScheduler.Utterance(at, poi, kind, "text", kind.priority, 100.0, null, false, at), outcome, at, waitedMs = waited, byKind = by, detail = detail)

    // --- Route options (§8.3a) ---------------------------------------------------------------

    @Test fun aLoadingListShowsTheHostsSpinnerUntilItsFirstLoad() {
        // Recents loads on resume, so before it the template is the spinner under the header.
        val first = RecentsScreen(rig.car, rig.host).onGetTemplate() as ListTemplate
        assertTrue(first.isLoading)
        assertEquals("Recents", first.headerTitle)
        assertSame(Action.BACK, first.header!!.startHeaderAction)
        assertNull(first.singleList)
    }

    @Test fun routeOptionsLoadsAsItIsMadeBeforeItIsShown() {
        val s = RouteOptionsScreen(rig.car, rig.host)
        rig.awaitUntil { s.field("items").get(s) != null }
        assertFalse((s.onGetTemplate() as ListTemplate).isLoading)
        assertEquals("Route options", s.onGetTemplate().headerTitle)
    }

    @Test fun routeOptionsHasTheTwoTogglesWithTheirSavedState() {
        rig.settings { it.copy(avoidTolls = true, evEfficientRoutes = false) }
        val s = RouteOptionsScreen(rig.car, rig.host)
        rig.start(s)
        rig.awaitUntil { rig.invalidations(s) == 1 }
        val rows = s.onGetTemplate().list.rows
        assertEquals(listOf("Avoid tolls", "Energy-efficient routes (EV)"), rows.map { it.titleText })
        assertEquals(listOf("Where there's a way round"), rows[0].lines)
        assertEquals(listOf("Least energy for an electric car"), rows[1].lines)
        assertEquals(listOf(true, false), rows.map { it.toggle!!.isChecked })
        assertTrue("no template actions", (s.onGetTemplate() as ListTemplate).actions.isEmpty())
    }

    @Test fun routeOptionsLoadsOnceAndAResumeDoesNotResend() {
        val s = RouteOptionsScreen(rig.car, rig.host)
        val c = rig.start(s)
        rig.awaitUntil { rig.invalidations(s) == 1 }
        c.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
        repeat(10) { rig.idle(); Thread.sleep(5) }
        assertEquals(1, rig.invalidations(s))
    }

    @Test fun routeOptionsToggleWritesTheSettingAndShowsWhatWasSaved() {
        rig.settings { it.copy(avoidTolls = true, evEfficientRoutes = false) }
        val s = RouteOptionsScreen(rig.car, rig.host)
        rig.start(s)
        rig.awaitUntil { rig.invalidations(s) == 1 }
        s.onGetTemplate().list.rows[1].switch(true)
        rig.awaitUntil { rig.invalidations(s) == 2 }
        assertTrue(runBlocking { rig.deps.store.current() }.evEfficientRoutes)
        assertEquals(listOf(true, true), s.onGetTemplate().list.rows.map { it.toggle!!.isChecked })
        s.onGetTemplate().list.rows[0].switch(false)
        rig.awaitUntil { rig.invalidations(s) == 3 }
        assertFalse(runBlocking { rig.deps.store.current() }.avoidTolls)
    }

    // --- POI layers (§8) ---------------------------------------------------------------------

    @Test fun poiLayersWithNoSetsSaysWhereToGetThem() {
        val s = PoiLayersScreen(rig.car, rig.host)
        rig.start(s)
        rig.awaitUntil { rig.invalidations(s) == 1 }
        val t = s.onGetTemplate()
        assertEquals("POI layers", t.headerTitle)
        assertEquals("No POI sets yet. Import or sync on your phone.", t.list.noItemsMessage.toString())
        assertTrue(t.list.rows.isEmpty())
    }

    @Test fun poiLayersShowsEachSetWithIconCountAlertsAndItsToggle() {
        runBlocking {
            rig.db.poiSets().insert(set("Mobile", enabled = false, alerts = false, count = 3))
            rig.db.poiSets().insert(set("Gatso", count = 120))
        }
        val s = PoiLayersScreen(rig.car, rig.host)
        rig.start(s)
        rig.awaitUntil { rig.invalidations(s) == 1 }
        val rows = s.onGetTemplate().list.rows
        assertEquals("by name", listOf("Gatso", "Mobile"), rows.map { it.titleText })
        assertEquals(listOf("120 POIs · alerts on"), rows[0].lines)
        assertEquals(listOf("3 POIs · alerts off"), rows[1].lines)
        assertEquals(listOf(true, false), rows.map { it.toggle!!.isChecked })
        assertTrue("the set's own map icon, as a bitmap", rows.all { it.image != null && it.iconRes == 0 })
        assertTrue("toggle only, no tap", rows.all { it.onClickDelegate == null })
    }

    @Test fun poiLayersShowsNoMoreRowsThanTheHostAllows() {
        runBlocking { repeat(12) { rig.db.poiSets().insert(set("Set %02d".format(it))) } }
        val s = PoiLayersScreen(rig.car, rig.host)
        rig.start(s)
        rig.awaitUntil { rig.invalidations(s) == 1 }
        assertEquals(minOf(12, CarUi.listLimit(rig.car)), s.onGetTemplate().list.rows.size)
    }

    @Test fun poiLayersToggleWritesTheSetAndResendsOnceWithoutSnappingBack() {
        val id = runBlocking { rig.db.poiSets().insert(set("Gatso")) }
        val s = PoiLayersScreen(rig.car, rig.host)
        rig.start(s)
        rig.awaitUntil { rig.invalidations(s) == 1 }
        s.onGetTemplate().list.rows.single().switch(false)
        rig.awaitUntil { rig.invalidations(s) == 2 }
        assertFalse(runBlocking { rig.db.poiSets().get(id) }!!.enabled)
        assertFalse(s.onGetTemplate().list.rows.single().toggle!!.isChecked)
    }

    // --- Alerts: the speech records (§12.3) ---------------------------------------------------

    /**
     * Found while pinning: the records screen passes a text "Refresh" action to `ListTemplate`, which the Car App
     * Library 1.7 refuses ("max 0 actions with custom titles"), so its template can't be built at all and the
     * More → Alerts row can't open a usable screen. Pinned as built; reported against hard rule 2.
     */
    @Test fun recordsTemplateIsRefusedByTheLibraryBecauseOfItsTextRefresh() {
        val s = AlertsRecordsScreen(rig.car, rig.deps.tts)
        val e = runCatching { s.onGetTemplate() }.exceptionOrNull()
        assertTrue("got $e", e is IllegalArgumentException)
        assertEquals(listOf("Refresh"), s.actionsFor(emptyList<SpeechScheduler.Record>()).map { it.title.toString() })
    }

    @Test fun recordsAreTakenWhenTheScreenIsMadeNeverASpinner() {
        val recs = listOf(record(AlertKind.FIRST, SpeechScheduler.Outcome.SPOKEN))
        rig.deps.tts.privateFlow<List<SpeechScheduler.Record>>("_records").value = recs
        val s = AlertsRecordsScreen(rig.car, rig.deps.tts)
        assertEquals(recs, s.field("items").get(s))
        assertEquals("Alerts", s.field("title").get(s))
        assertEquals("No alerts spoken yet", s.field("emptyText").get(s))
    }

    @Test fun recordRowsAreNewestFirstWithKindPoiAndOutcome() {
        val s = AlertsRecordsScreen(rig.car, rig.deps.tts)
        val rows = s.rowsFor(listOf(
            record(AlertKind.FIRST, SpeechScheduler.Outcome.SPOKEN, poi = 7),
            record(AlertKind.LIMIT_CHANGE, SpeechScheduler.Outcome.MUTED),
            record(AlertKind.GUIDANCE, SpeechScheduler.Outcome.REPLACED),
            record(AlertKind.PASSING, SpeechScheduler.Outcome.STALE, poi = 9),
        ))
        assertEquals(
            listOf("PASSING poi 9 STALE", "Directions REPLACED", "Speed limit change MUTED", "FIRST poi 7 SPOKEN"),
            rows.map { it.titleText },
        )
    }

    @Test fun recordRowTextIsTimeWaitAndWhoReplacedIt() {
        val s = AlertsRecordsScreen(rig.car, rig.deps.tts)
        val full = s.rowsFor(listOf(record(AlertKind.SECOND, SpeechScheduler.Outcome.REPLACED, waited = 120, by = AlertKind.PASSING, detail = "closer"))).single()
        assertTrue(full.lines.single(), Regex("""\d\d:\d\d:\d\d · waited 120 ms · by PASSING · closer""").matches(full.lines.single()))
        val bare = s.rowsFor(listOf(record(AlertKind.FIRST, SpeechScheduler.Outcome.TIMED_OUT))).single()
        assertTrue(bare.lines.single(), Regex("""\d\d:\d\d:\d\d · waited –""").matches(bare.lines.single()))
    }

    @Test fun recordRowsStopAtTheHostsListLimit() {
        val s = AlertsRecordsScreen(rig.car, rig.deps.tts)
        val rows = s.rowsFor((1L..20L).map { record(AlertKind.FIRST, SpeechScheduler.Outcome.SPOKEN, poi = it) })
        assertEquals(CarUi.listLimit(rig.car), rows.size)
        assertEquals("FIRST poi 20 SPOKEN", rows.first().titleText)
    }

    // --- Recents (§8.2) ----------------------------------------------------------------------

    private fun recents(): RecentsScreen = RecentsScreen(rig.car, rig.host).also { s ->
        rig.start(s)
        rig.awaitUntil { s.field("items").get(s) != null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun RecentsScreen.loaded() = field("items").get(this) as List<PlaceEntity>

    @Test fun recentsEmptySaysSoAndOffersNoClearAll() {
        val s = recents()
        val t = s.onGetTemplate() as ListTemplate
        assertEquals("Recents", t.headerTitle)
        assertEquals("No recent places", t.singleList!!.noItemsMessage.toString())
        assertTrue(t.actions.isEmpty())
    }

    @Test fun recentsListFavouritesFirstThenTheNewestWithNameAndAddress() {
        runBlocking {
            rig.db.places().insert(place("Old", used = 1_000))
            rig.db.places().insert(place("Office", PlaceKind.FAVOURITE, used = 500))
            rig.db.places().insert(place("New", used = 9_000, address = null))
        }
        val s = recents()
        val rows = s.rowsFor(s.loaded())
        assertEquals(listOf("Office", "New", "Old"), rows.map { it.titleText })
        assertEquals(listOf("Office, Banbury"), rows[0].lines)
        assertTrue("no address, no line", rows[1].lines.isEmpty())
    }

    @Test fun fromCarApi6EachRecentHasOneDeleteActionAndAFavouriteNone() {
        runBlocking {
            rig.db.places().insert(place("Office", PlaceKind.FAVOURITE))
            rig.db.places().insert(place("Tesco"))
        }
        val s = recents()
        val rows = s.rowsFor(s.loaded())
        assertTrue("favourites are removed on the phone (§8.2)", rows[0].actions.isEmpty())
        assertEquals(listOf(R.drawable.ic_action_delete), rows[1].actions.map { it.iconRes })
    }

    @Test fun belowCarApi6RowsHaveNoActionsAndOpenThePlaceDetail() {
        val rig5 = CarScreenRig(apiLevel = 5)
        runBlocking { rig5.db.places().insert(place("Tesco")) }
        val s = RecentsScreen(rig5.car, rig5.host)
        rig5.start(s)
        rig5.awaitUntil { s.field("items").get(s) != null }
        val row = s.rowsFor(s.loaded()).single()
        assertTrue(row.actions.isEmpty())
        row.tap()
        rig5.idle()
        assertEquals("PlaceDetailScreen", rig5.screens.screensPushed.last().javaClass.simpleName)
    }

    @Test fun fromCarApi6ATapGoesStraightToTheRoutePreview() {
        runBlocking { rig.db.places().insert(place("Tesco")) }
        val s = recents()
        s.rowsFor(s.loaded()).single().tap()
        rig.idle()
        assertEquals("RoutePreviewScreen", rig.screens.screensPushed.last().javaClass.simpleName)
    }

    @Test fun clearAllIsOfferedOnlyWhenARecentExists() {
        val s = recents()
        assertTrue(s.actionsFor(listOf(place("Office", PlaceKind.FAVOURITE))).isEmpty())
        assertEquals(listOf("Clear all"), s.actionsFor(listOf(place("Tesco"))).map { it.title.toString() })
    }

    /** Found while pinning: as with the records' Refresh, the text "Clear all" makes `ListTemplate` throw (reported). */
    @Test fun recentsTemplateWithARecentIsRefusedByTheLibraryBecauseOfClearAll() {
        runBlocking { rig.db.places().insert(place("Tesco")) }
        val s = recents()
        assertTrue(runCatching { s.onGetTemplate() }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun recentsWithOnlyFavouritesBuildsWithoutActions() {
        runBlocking { rig.db.places().insert(place("Office", PlaceKind.FAVOURITE)) }
        val s = recents()
        val t = s.onGetTemplate() as ListTemplate
        assertEquals(listOf("Office"), t.singleList!!.rows.map { it.titleText })
        assertTrue(t.actions.isEmpty())
    }

    @Test fun deleteRemovesTheRowAndSaysSo() {
        runBlocking { rig.db.places().insert(place("Tesco")); rig.db.places().insert(place("Office", PlaceKind.FAVOURITE)) }
        val s = recents()
        s.rowsFor(s.loaded()).single { it.titleText == "Tesco" }.actions.single().tap()
        rig.awaitUntil { s.loaded().none { it.name == "Tesco" } }
        assertEquals(listOf("Office"), runBlocking { rig.db.places().list() }.map { it.name })
        assertTrue(rig.appManager.toastsShown.map { it.toString() }.contains("Removed Tesco"))
    }

    @Test fun clearAllAsksFirstAndKeepsFavourites() {
        runBlocking { rig.db.places().insert(place("Tesco")); rig.db.places().insert(place("Office", PlaceKind.FAVOURITE)) }
        val s = recents()
        s.actionsFor(s.loaded()).single().tap()
        rig.idle()
        val confirm = rig.screens.screensPushed.last() as ConfirmScreen
        rig.start(confirm)
        val t = confirm.onGetTemplate() as MessageTemplate
        assertEquals("Clear all recents?", t.message.toString())
        assertEquals(listOf("Tesco", "Office").sorted(), runBlocking { rig.db.places().list() }.map { it.name }.sorted())
        t.actions.single { it.title.toString() == "Yes" }.tap()
        rig.awaitUntil { s.loaded().none { it.kind == PlaceKind.RECENT } }
        assertEquals(listOf("Office"), runBlocking { rig.db.places().list() }.map { it.name })
    }

    @Test fun recentsReloadOnEveryResume() {
        val s = RecentsScreen(rig.car, rig.host)
        val c = rig.start(s)
        rig.awaitUntil { s.field("items").get(s) != null }
        assertTrue(s.loaded().isEmpty())
        val before = rig.invalidations(s)
        // A favourite, so the list template can be built (a recent brings the refused Clear all, above).
        runBlocking { rig.db.places().insert(place("Office", PlaceKind.FAVOURITE)) }
        c.moveToState(Lifecycle.State.STARTED).moveToState(Lifecycle.State.RESUMED)
        rig.awaitUntil { s.loaded().isNotEmpty() }
        assertEquals(listOf("Office"), s.loaded().map { it.name })
        assertEquals("one re-send for the one reload", before + 1, rig.invalidations(s))
    }

    // --- Place detail and the confirmation (§8.2) ---------------------------------------------

    @Test fun placeDetailForARecentHasNavigateThenRemove() {
        val t = PlaceDetailScreen(rig.car, rig.host, place("Tesco")) { }.onGetTemplate() as MessageTemplate
        assertEquals("Tesco", t.header!!.title.toString())
        assertSame(Action.BACK, t.header!!.startHeaderAction)
        assertEquals("Tesco, Banbury", t.message.toString())
        assertEquals(listOf("Navigate", "Remove"), t.actions.map { it.title.toString() })
    }

    @Test fun placeDetailForAFavouriteOffersNavigateOnly() {
        val t = PlaceDetailScreen(rig.car, rig.host, place("Office", PlaceKind.FAVOURITE)) { }.onGetTemplate() as MessageTemplate
        assertEquals(listOf("Navigate"), t.actions.map { it.title.toString() })
    }

    @Test fun placeDetailWithoutAnAddressShowsTheName() {
        val t = PlaceDetailScreen(rig.car, rig.host, place("Tesco", address = null)) { }.onGetTemplate() as MessageTemplate
        assertEquals("Tesco", t.message.toString())
    }

    @Test fun placeDetailRemoveDeletesTellsTheListAndPops() {
        val id = runBlocking { rig.db.places().insert(place("Tesco")) }
        var changed = 0
        val s = PlaceDetailScreen(rig.car, rig.host, place("Tesco").copy(id = id)) { changed++ }
        rig.root()
        rig.start(s)
        (s.onGetTemplate() as MessageTemplate).actions[1].tap()
        rig.awaitUntil { changed == 1 }
        assertTrue(runBlocking { rig.db.places().list() }.isEmpty())
        assertTrue(rig.screens.screensRemoved.contains(s))
    }

    @Test fun placeDetailNavigateOpensThePreview() {
        val s = PlaceDetailScreen(rig.car, rig.host, place("Tesco")) { }
        rig.start(s)
        (s.onGetTemplate() as MessageTemplate).actions[0].tap()
        rig.idle()
        assertEquals("RoutePreviewScreen", rig.screens.screensPushed.last().javaClass.simpleName)
    }

    @Test fun confirmAsksWithYesThenNo() {
        val t = ConfirmScreen(rig.car, "Clear all recents?") { }.onGetTemplate() as MessageTemplate
        assertEquals("Confirm", t.header!!.title.toString())
        assertSame(Action.BACK, t.header!!.startHeaderAction)
        assertEquals(listOf("Yes", "No"), t.actions.map { it.title.toString() })
    }

    @Test fun confirmYesRunsTheActionAndPops() {
        var yes = 0
        val s = ConfirmScreen(rig.car, "Clear all recents?") { yes++ }
        rig.root()
        rig.start(s)
        (s.onGetTemplate() as MessageTemplate).actions[0].tap()
        rig.idle()
        assertEquals(1, yes)
        assertTrue(rig.screens.screensRemoved.contains(s))
    }

    @Test fun confirmNoPopsWithoutTheAction() {
        var yes = 0
        val s = ConfirmScreen(rig.car, "Clear all recents?") { yes++ }
        rig.root()
        rig.start(s)
        (s.onGetTemplate() as MessageTemplate).actions[1].tap()
        rig.idle()
        assertEquals(0, yes)
        assertTrue(rig.screens.screensRemoved.contains(s))
    }
}

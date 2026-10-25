// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import android.app.Application
import android.location.Location
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.Action
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.SearchTemplate
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.R
import io.autonav.alerts.contacts.ContactAddress
import io.autonav.alerts.core.contacts.ContactMatch
import io.autonav.alerts.data.ContactGeocodeEntity
import io.autonav.alerts.data.PlaceEntity
import io.autonav.alerts.data.PlaceKind
import io.autonav.alerts.places.SearchHit
import io.autonav.alerts.testing.CarScreenRig
import io.autonav.alerts.testing.FakeContactsProvider
import io.autonav.alerts.testing.field
import io.autonav.alerts.testing.headerTitle
import io.autonav.alerts.testing.iconRes
import io.autonav.alerts.testing.lines
import io.autonav.alerts.testing.list
import io.autonav.alerts.testing.privateFlow
import io.autonav.alerts.testing.rows
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
 * Search and Contacts as the host receives them (DESIGN.md §8 "Search" and "Contacts" rows, §8.2 "Car screen use",
 * §8.3 "Car UI"): favourites and recents in the empty box with the Contacts row only when the optional permission is
 * granted; "Searching…" then the results or "No results for …", never a silent empty list; contact rows above
 * Places; "Which address for …?" to pick one of several; Contacts A–Z, recently used first, within the host's
 * limit, ending in "Search contacts…". Every row shown leads somewhere (hard rule 2). Places has no key here, so its
 * results are empty, as on a phone without a key. Robolectric main looper; the address book is a fake provider.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SearchAndContactsScreenTest {
    private val rig = CarScreenRig()
    private val done = object : OnDoneCallback {}

    private fun contact(id: Long, name: String, type: Int, address: String, label: String? = null) = arrayOf<Any?>(id, name, type, label, address)

    private fun search(contactsOnly: Boolean = false, initialQuery: String? = null, choice: Pair<ContactAddress, List<SearchHit>>? = null): SearchScreen =
        SearchScreen(rig.car, rig.host, initialQuery, contactsOnly, choice).also { s ->
            rig.start(s)
            rig.awaitUntil { rig.invalidations(s) > 0 }
        }

    private fun SearchScreen.template() = onGetTemplate() as SearchTemplate

    private fun SearchScreen.rows() = template().itemList!!.rows

    private fun SearchScreen.submit(text: String) {
        template().searchCallbackDelegate.sendSearchSubmitted(text, done)
        rig.idle()
    }

    private fun place(name: String, kind: PlaceKind = PlaceKind.RECENT, used: Long = 0) =
        PlaceEntity(placeId = null, name = name, address = "$name, Banbury", lat = 52.06, lon = -1.34, kind = kind, lastUsedAt = used)

    private fun carAt(lat: Double, lon: Double) {
        rig.deps.loc.privateFlow<Location?>("_lastLocation").value = Location("test").apply { latitude = lat; longitude = lon }
    }

    // --- Search: the empty box ----------------------------------------------------------------

    @Test fun emptySearchWithNothingToPickSaysWhatToDoAndOpensTheKeyboard() {
        val t = search().template()
        assertTrue(t.itemList!!.rows.isEmpty())
        assertEquals("Search for a place or address", t.itemList!!.noItemsMessage.toString())
        assertEquals("Search places or addresses", t.searchHint)
        assertTrue(t.isShowKeyboardByDefault)
        assertSame(Action.BACK, t.headerAction)
        assertEquals("", t.initialSearchText)
    }

    @Test fun emptySearchListsFavouritesThenRecentsWithTheirIconsAndKeepsTheKeyboardClosed() {
        runBlocking {
            rig.db.places().insert(place("Tesco", used = 9))
            rig.db.places().insert(place("Office", PlaceKind.FAVOURITE))
        }
        val t = search().template()
        assertEquals(listOf("Office", "Tesco"), t.itemList!!.rows.map { it.titleText })
        assertEquals(listOf(R.drawable.ic_action_star, R.drawable.ic_action_recents), t.itemList!!.rows.map { it.iconRes })
        assertEquals(listOf("Office, Banbury"), t.itemList!!.rows[0].lines)
        assertFalse(t.isShowKeyboardByDefault)
    }

    @Test fun emptySearchShowsAtMostSixPlaces() {
        runBlocking { repeat(9) { rig.db.places().insert(place("P$it", used = it.toLong())) } }
        assertEquals(6, search().rows().size)
    }

    @Test fun aRecentInSearchGoesToTheRoutePreview() {
        runBlocking { rig.db.places().insert(place("Tesco")) }
        search().rows().single().tap()
        rig.idle()
        assertEquals("RoutePreviewScreen", rig.screens.screensPushed.last().javaClass.simpleName)
    }

    @Test fun withoutTheContactsPermissionThereIsNoContactsRow() {
        runBlocking { rig.db.places().insert(place("Tesco")) }
        assertTrue(search().rows().none { it.titleText == "Contacts" })
    }

    @Test fun withTheContactsPermissionTheContactsRowComesLastAndOpensContacts() {
        FakeContactsProvider.install(rig.app)
        runBlocking { rig.db.places().insert(place("Tesco")) }
        val s = search()
        val rows = s.rows()
        assertEquals(listOf("Tesco", "Contacts"), rows.map { it.titleText })
        assertEquals(listOf("Drive to a contact's address"), rows[1].lines)
        assertEquals(R.drawable.ic_action_contact, rows[1].iconRes)
        rows[1].tap()
        rig.idle()
        assertEquals("ContactsScreen", rig.screens.screensPushed.last().javaClass.simpleName)
    }

    @Test fun withContactsButNoPlacesTheContactsRowStandsAlone() {
        FakeContactsProvider.install(rig.app)
        val t = search().template()
        assertEquals(listOf("Contacts"), t.itemList!!.rows.map { it.titleText })
        assertNull(t.itemList!!.noItemsMessage)
    }

    @Test fun contactsOnlySearchAsksForANameWithItsOwnHint() {
        FakeContactsProvider.install(rig.app)
        val t = search(contactsOnly = true).template()
        assertTrue("no Contacts row inside the contacts search", t.itemList!!.rows.none { it.titleText == "Contacts" })
        assertEquals("Type or say a contact's name", t.itemList!!.noItemsMessage.toString())
        assertEquals("Search your contacts", t.searchHint)
        assertTrue(t.isShowKeyboardByDefault)
    }

    // --- Search: submitting --------------------------------------------------------------------

    @Test fun submitShowsSearchingThenNoResultsForTheText() {
        val s = search()
        s.template().searchCallbackDelegate.sendSearchSubmitted("pizza", done)
        assertEquals(listOf("Searching…"), s.rows().map { it.titleText })
        rig.awaitUntil { s.field("searching").get(s) == false }
        val t = s.template()
        assertTrue(t.itemList!!.rows.isEmpty())
        assertEquals("No results for pizza", t.itemList!!.noItemsMessage.toString())
        assertEquals("pizza", t.initialSearchText)
        assertFalse(t.isShowKeyboardByDefault)
    }

    @Test fun aNameFromANavigateIntentIsSearchedAtOnce() {
        val s = search(initialQuery = "Tesco Banbury")
        rig.awaitUntil { s.field("searching").get(s) == false }
        assertEquals("No results for Tesco Banbury", s.template().itemList!!.noItemsMessage.toString())
    }

    @Test fun contactsOnlySubmitWithNoMatchSaysNoContactMatches() {
        FakeContactsProvider.install(rig.app, contact(1, "Mum", 1, "12 High St, Sheffield"))
        val s = search(contactsOnly = true)
        s.submit("zed")
        rig.awaitUntil { s.field("searching").get(s) == false }
        assertEquals("No contacts with an address match", s.template().itemList!!.noItemsMessage.toString())
    }

    @Test fun matchingContactsAreListedWithLabelAndAddress() {
        FakeContactsProvider.install(rig.app, contact(1, "Mum", 1, "12 High St\nSheffield"), contact(2, "Dave Smith", 2, "1 Mill Lane"), contact(3, "Ann", 1, "3 Elm Rd"))
        val s = search()
        s.submit("smith")
        rig.awaitUntil { s.field("searching").get(s) == false }
        val row = s.rows().single()
        assertEquals("Dave Smith", row.titleText)
        assertEquals(listOf("Work · 1 Mill Lane"), row.lines)
        assertEquals(R.drawable.ic_action_contact, row.iconRes)
        assertNull("contacts found: no 'no results' message", s.template().itemList!!.noItemsMessage)
    }

    /**
     * A contact row in Search puts the known distance first ("0.7 mi · Home · 12 High St, Sheffield"), where
     * DESIGN.md §8.3 writes "Home · 12 High Street, Sheffield … (plus the distance)" and Contacts puts it last
     * (reported; pinned as built).
     */
    @Test fun aContactLocatedBeforeShowsItsDistanceFirstInSearch() {
        FakeContactsProvider.install(rig.app, contact(1, "Mum", 1, "12 High St, Sheffield"))
        runBlocking { rig.db.contactGeocodes().put(ContactGeocodeEntity(ContactMatch.key("12 High St, Sheffield"), null, 53.39, -1.47, null, 1)) }
        carAt(53.38, -1.47)
        val s = search()
        s.submit("mum")
        rig.awaitUntil { s.field("searching").get(s) == false }
        assertEquals(listOf("0.7 mi · Home · 12 High St, Sheffield"), s.rows().single().lines)
    }

    @Test fun atMostThreeContactsAboveThePlacesResults() {
        FakeContactsProvider.install(rig.app, *(1L..5L).map { contact(it, "Sam $it", 1, "$it Elm Rd") }.toTypedArray())
        val s = search()
        s.submit("sam")
        rig.awaitUntil { s.field("searching").get(s) == false }
        assertEquals(listOf("Sam 1", "Sam 2", "Sam 3"), s.rows().map { it.titleText })
    }

    @Test fun typingShowsMatchingContactsAtOnceAndClearingEmptiesTheList() {
        FakeContactsProvider.install(rig.app, contact(1, "Mum", 1, "12 High St"))
        val s = search()
        s.template().searchCallbackDelegate.sendSearchTextChanged("mu", done)
        rig.awaitUntil { s.rows().isNotEmpty() }
        assertEquals(listOf("Mum"), s.rows().map { it.titleText })
        s.template().searchCallbackDelegate.sendSearchTextChanged("", done)
        rig.idle()
        assertEquals("the empty box again: the Contacts row", listOf("Contacts"), s.rows().map { it.titleText })
    }

    @Test fun severalPlacesForAContactAskWhichAddress() {
        val mum = ContactAddress(1, "Mum", "Home", "12 High St")
        val hits = listOf(
            SearchHit("a", "12 High St", "12 High St, Sheffield S1", 1_200, 53.38, -1.47),
            SearchHit("b", "12 High St", null, null, 51.5, -0.1),
        )
        val s = search(contactsOnly = true, initialQuery = "Mum", choice = mum to hits)
        val rows = s.rows()
        assertEquals(listOf("12 High St, Sheffield S1", "12 High St"), rows.map { it.titleText })
        assertEquals(listOf("Which address for Mum? · 0.7 mi"), rows[0].lines)
        assertEquals(listOf("Which address for Mum?"), rows[1].lines)
        assertEquals("the choice opens as it is, not searched over", "Mum", s.template().initialSearchText)
    }

    @Test fun pickingAnAddressRemembersItAndOpensThePreview() {
        val mum = ContactAddress(1, "Mum", "Home", "12 High St")
        val s = search(contactsOnly = true, choice = mum to listOf(SearchHit("a", "12 High St", "12 High St, Sheffield", null, 53.38, -1.47)))
        s.rows().single().tap()
        rig.awaitUntil { rig.screens.screensPushed.last() is RoutePreviewScreen }
        val cached = runBlocking { rig.db.contactGeocodes().get(ContactMatch.key("12 High St")) }!!
        assertEquals(53.38, cached.lat, 1e-9)
    }

    // --- Contacts (§8.3) -----------------------------------------------------------------------

    private fun contacts(): ContactsScreen = ContactsScreen(rig.car, rig.host).also { s ->
        rig.start(s)
        rig.awaitUntil { s.field("items").get(s) != null }
    }

    @Test fun contactsAreAToZWithLabelAndAddressThenSearchContacts() {
        FakeContactsProvider.install(rig.app, contact(1, "Mum", 1, "12 High St"), contact(2, "Dave Smith", 2, "1 Mill Lane"), contact(3, "Ann", 0, "3 Elm Rd", label = "Gran's"))
        val t = contacts().onGetTemplate()
        assertEquals("Contacts", t.headerTitle)
        val rows = t.list.rows
        assertEquals(listOf("Ann", "Dave Smith", "Mum", "Search contacts…"), rows.map { it.titleText })
        assertEquals(listOf("Gran's · 3 Elm Rd"), rows[0].lines)
        assertEquals(listOf("Work · 1 Mill Lane"), rows[1].lines)
        assertTrue(rows.dropLast(1).all { it.iconRes == R.drawable.ic_action_contact })
        assertEquals(R.drawable.ic_action_search, rows.last().iconRes)
    }

    @Test fun aContactUsedRecentlyComesFirstWithItsDistanceLast() {
        FakeContactsProvider.install(rig.app, contact(1, "Mum", 1, "12 High St"), contact(2, "Ann", 1, "3 Elm Rd"))
        runBlocking { rig.db.contactGeocodes().put(ContactGeocodeEntity(ContactMatch.key("12 High St"), null, 53.39, -1.47, null, 5_000)) }
        carAt(53.38, -1.47)
        val rows = contacts().onGetTemplate().list.rows
        assertEquals(listOf("Mum", "Ann", "Search contacts…"), rows.map { it.titleText })
        assertEquals(listOf("Home · 12 High St · 0.7 mi"), rows[0].lines)
        assertEquals(listOf("Home · 3 Elm Rd"), rows[1].lines)
    }

    @Test fun contactsFillTheHostsLimitLessOneForSearchContacts() {
        FakeContactsProvider.install(rig.app, *(1L..20L).map { contact(it, "Sam %02d".format(it), 1, "$it Elm Rd") }.toTypedArray())
        val rows = contacts().onGetTemplate().list.rows
        assertEquals(CarUi.listLimit(rig.car), rows.size)
        assertEquals("Search contacts…", rows.last().titleText)
    }

    @Test fun withoutThePermissionOnlySearchContactsIsLeft() {
        val t = contacts().onGetTemplate() as ListTemplate
        assertEquals(listOf("Search contacts…"), t.singleList!!.rows.map { it.titleText })
        assertEquals("No contacts with a postal address", t.singleList!!.noItemsMessage.toString())
    }

    @Test fun searchContactsOpensTheContactsOnlySearch() {
        contacts().onGetTemplate().list.rows.last().tap()
        rig.idle()
        val pushed = rig.screens.screensPushed.last() as SearchScreen
        assertEquals(true, pushed.field("contactsOnly").get(pushed))
    }

    @Test fun contactsAreLoadedOncePerResume() {
        FakeContactsProvider.install(rig.app, contact(1, "Mum", 1, "12 High St"))
        val s = contacts()
        rig.idle()
        assertEquals(1, rig.invalidations(s))
    }
}

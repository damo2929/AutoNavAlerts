// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.contacts

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.location.Location
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.core.contacts.ContactMatch
import io.autonav.alerts.data.AppDatabase
import io.autonav.alerts.data.ContactGeocodeEntity
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.nav.NavigatorHolder
import io.autonav.alerts.places.PlacesSearch
import io.autonav.alerts.places.SearchHit
import io.autonav.alerts.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Contacts' postal addresses for navigation (DESIGN.md §8.3) over a made-up contacts provider and an
 * in-memory Room database: nothing without READ_CONTACTS; name matching from 2 characters, whole name or any
 * word, name-starts first; one row per address with its type label; blank addresses skipped and multi-line
 * ones joined; the full list recently located first (contact_geocode) then A–Z; the geocode cache hit, its
 * 90-day expiry and a miss (no Maps key in the test, so Places finds nothing: "not found"). Coroutines run
 * with runBlocking on the test thread. Upholds §8.3 Privacy: the cache is keyed by a hash, not the text.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ContactsSourceTest {
    /** A made-up address book: the projection's columns, the provider's LIKE on the name, sorted by name. */
    class FakeContacts : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val cols = projection!!
            val c = MatrixCursor(cols)
            val like = args?.firstOrNull()?.trim('%')?.lowercase()
            for (r in ROWS.sortedBy { (it[StructuredPostal.DISPLAY_NAME_PRIMARY] as String?)?.lowercase() }) {
                if (like != null && (r[StructuredPostal.DISPLAY_NAME_PRIMARY] as String?)?.lowercase()?.contains(like) != true) continue
                c.addRow(cols.map { r[it] })
            }
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val eventLog = EventLog(app)
    private val places = PlacesSearch(NavigatorHolder(app, SettingsStore(app)), db, eventLog)
    private val contacts = ContactsSource(app, db, places)

    init {
        Robolectric.setupContentProvider(FakeContacts::class.java, ContactsContract.AUTHORITY)
    }

    @After fun close() = db.close()

    private fun allow() = shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
    private fun near() = Location("test").apply { latitude = 53.38; longitude = -1.47 }
    private fun cache(address: String, ageMs: Long, lat: Double = 53.40, lon: Double = -1.50, placeId: String? = "place-1", formatted: String? = "1 Cached Road, Sheffield") =
        runBlocking { db.contactGeocodes().put(ContactGeocodeEntity(ContactMatch.key(address), placeId, lat, lon, formatted, System.currentTimeMillis() - ageMs)) }

    @Test fun withoutThePermissionThereAreNoContacts() = runBlocking {
        assertFalse(contacts.available)
        assertTrue(contacts.search("mum").isEmpty())
        assertTrue(contacts.all(50).isEmpty())
    }

    @Test fun aNameMatchesFromItsStartOrAnyWord() = runBlocking {
        allow()
        assertEquals(listOf("Mum (Home)", "Mum (Work)"), contacts.search("mum").map { it.title })
        assertEquals(listOf("Smithers", "Dave Smith"), contacts.search("smith").map { it.name })
        assertTrue("one character is too few", contacts.search("m").isEmpty())
        assertTrue("inside a word is not a match", contacts.search("ave").isEmpty())
    }

    @Test fun namesThatStartWithTheQueryComeFirst() = runBlocking {
        allow()
        // "Dave Smith" sorts first by name, but "Smithers" starts with the query.
        assertEquals(listOf("Smithers", "Dave Smith"), contacts.search("smi").map { it.name })
        assertEquals(listOf("Smithers"), contacts.search("smi", limit = 1).map { it.name })
    }

    @Test fun eachRowCarriesItsTypeLabelAndAOneLineAddress() = runBlocking {
        allow()
        val gran = contacts.search("gran").single()
        assertEquals("Gran's flat", gran.label)
        assertEquals("Gran (Gran's flat)", gran.title)
        val home = contacts.search("mum").first()
        assertEquals("Home", home.label)
        assertEquals("multi-line addresses become one line", "12 High Street, Sheffield, S1 2AB", home.address)
        assertEquals(101L, home.dataId)
    }

    @Test fun aBlankAddressIsSkipped() = runBlocking {
        allow()
        assertTrue(contacts.search("nobody").isEmpty())
        assertFalse(contacts.all(50).any { it.name == "Nobody Blank" })
        assertFalse("a row with no name is skipped too", contacts.all(50).any { "No name street" in it.address })
    }

    @Test fun anAddressOfOnlySpacesAndLineBreaksIsKeptAsAComma() = runBlocking {
        allow()
        // Pinned as built, reported: the line breaks become commas before the blank check, so " \n " survives as ",".
        assertEquals(",", contacts.search("odd").single().address)
    }

    @Test fun theFullListIsRecentlyLocatedFirstThenAToZ() = runBlocking {
        allow()
        cache("1 Work Park, Leeds", ageMs = 60_000)
        cache("5 Market Place, Derby", ageMs = 10_000)
        val all = contacts.all(50)
        assertEquals(
            listOf("Smithers (Home)", "Mum (Work)", "Dave Smith (Home)", "Gran (Gran's flat)", "Mum (Home)", "Odd Blank (Home)"),
            all.map { it.title },
        )
        assertEquals(2, contacts.all(2).size)
        assertTrue(contacts.all(0).isEmpty())
    }

    @Test fun aCachedLocationUnder90DaysOldIsUsedWithoutASearch() = runBlocking {
        allow()
        val mum = contacts.search("mum").first()
        cache(mum.address, ageMs = 89L * 24 * 3_600_000)
        val found = contacts.locate(mum, near()) as Located.Found
        assertEquals("Mum (Home)", found.destination.title)
        assertEquals(53.40, found.destination.lat, 0.0)
        assertEquals("place-1", found.destination.placeId)
        assertEquals("1 Cached Road, Sheffield", found.address)
    }

    @Test fun anOlderCachedLocationIsLookedUpAgain() = runBlocking {
        allow()
        val mum = contacts.search("mum").first()
        cache(mum.address, ageMs = 91L * 24 * 3_600_000)
        // No Maps key in a test: Places has no client and finds nothing.
        assertEquals(Located.NotFound, contacts.locate(mum, near()))
    }

    @Test fun anAddressNeverLocatedIsNotFoundWithoutAKey() = runBlocking {
        allow()
        assertEquals(Located.NotFound, contacts.locate(contacts.search("gran").single(), near()))
    }

    @Test fun rememberStoresTheChoiceUnderAHashOfTheAddress() = runBlocking {
        allow()
        val gran = contacts.search("gran").single()
        contacts.remember(gran, SearchHit("place-9", "Flat 2", "Flat 2, 3 Hill Rd, Sheffield S10 1AA", null, 53.37, -1.52))
        val e = db.contactGeocodes().get(ContactMatch.key(gran.address))!!
        assertEquals("place-9", e.placeId)
        assertFalse("the address text isn't the key", e.key.contains("Hill"))
        assertEquals(64, e.key.length)
        val found = contacts.locate(gran, null) as Located.Found
        assertEquals(53.37, found.destination.lat, 0.0)
        // A hit without a position isn't stored.
        contacts.clearCache()
        contacts.remember(gran, SearchHit("place-10", "x", null, null, null, null))
        assertNull(db.contactGeocodes().get(ContactMatch.key(gran.address)))
    }

    @Test fun theKnownDistanceIsShownAtAnyAgeAndOnlyOnceLocated() = runBlocking {
        allow()
        val mum = contacts.search("mum").first()
        assertNull(contacts.knownDistanceM(mum, near()))
        cache(mum.address, ageMs = 400L * 24 * 3_600_000, lat = 53.39, lon = -1.47)
        val d = contacts.knownDistanceM(mum, near())!!
        assertTrue("about 1.1 km: $d", d in 1_100..1_125)
        assertNull("no position, no distance", contacts.knownDistanceM(mum, null))
    }

    @Test fun clearingTheCacheForgetsEveryLocation() = runBlocking {
        allow()
        val mum = contacts.search("mum").first()
        cache(mum.address, ageMs = 0)
        contacts.clearCache()
        assertNull(db.contactGeocodes().get(ContactMatch.key(mum.address)))
        assertEquals("the recent order is gone too", "Dave Smith (Home)", contacts.all(50).first().title)
    }

    companion object {
        private fun row(id: Long, name: String?, type: Int, label: String?, address: String?) = mapOf(
            StructuredPostal._ID to id, StructuredPostal.DISPLAY_NAME_PRIMARY to name, StructuredPostal.TYPE to type,
            StructuredPostal.LABEL to label, StructuredPostal.FORMATTED_ADDRESS to address,
        )

        /** Made-up people and places. */
        val ROWS = listOf(
            row(101, "Mum", StructuredPostal.TYPE_HOME, null, "12 High Street\nSheffield\nS1 2AB"),
            row(102, "Mum", StructuredPostal.TYPE_WORK, null, "1 Work Park, Leeds"),
            row(103, "Dave Smith", StructuredPostal.TYPE_HOME, null, "7 Low Lane, York"),
            row(104, "Smithers", StructuredPostal.TYPE_HOME, null, "5 Market Place, Derby"),
            row(105, "Gran", StructuredPostal.TYPE_CUSTOM, "Gran's flat", "Flat 2\n3 Hill Rd\nSheffield"),
            row(106, "Nobody Blank", StructuredPostal.TYPE_HOME, null, ""),
            row(108, "Odd Blank", StructuredPostal.TYPE_HOME, null, " \n "),
            row(107, null, StructuredPostal.TYPE_HOME, null, "No name street"),
        )
    }
}

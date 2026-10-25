// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.testing.DataFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The non-POI tables of `poi.db` on an in-memory database (DESIGN.md §4.1): places (§8.2: favourites before
 * recents, recents newest first, the 200-recent cap, "Clear all recents" keeps favourites), camera reports
 * (§8.5: the Reports tab leaves DISCARDED out and lists newest first; the worker and the reminder read by
 * status) and the contact geocode cache (§8.3: `usedAt` orders the Contacts screen, a fresh lookup replaces
 * the old one). Runs on the test thread through runBlocking.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PlaceReportContactDaoTest {
    private lateinit var db: AppDatabase

    @Before fun open() { db = DataFixtures.memoryDb(ApplicationProvider.getApplicationContext()) }
    @After fun close() = db.close()

    private fun place(name: String, at: Long, kind: PlaceKind = PlaceKind.RECENT, placeId: String? = null) =
        PlaceEntity(placeId = placeId, name = name, address = null, lat = 52.0, lon = -1.0, kind = kind, lastUsedAt = at)

    private fun report(at: Long, status: ReportStatus, kind: ReportKind = ReportKind.UNSPECIFIED) = CameraReportEntity(
        kind = kind, lat = 52.0, lon = -1.0, heading = 90f, speedMps = 13f, accuracyM = null, road = null, speedLimit = 30,
        directionType = DirectionType.ONE_WAY, relatedPoiId = null, comment = null, createdAt = at, status = status,
    )

    @Test fun placesListFavouritesFirstThenRecentsNewestFirst() = runBlocking<Unit> {
        val places = db.places()
        places.insert(place("Old recent", 100))
        places.insert(place("Fav old", 50, PlaceKind.FAVOURITE))
        places.insert(place("New recent", 300))
        places.insert(place("Fav new", 400, PlaceKind.FAVOURITE))
        places.insert(place("Mid recent", 200))

        assertEquals(listOf("Fav new", "Fav old", "New recent", "Mid recent", "Old recent"), places.list().map { it.name })
        assertEquals(listOf("Fav new", "Fav old"), places.list(limit = 2).map { it.name })
    }

    @Test fun placeDedupeLookupAndRepeatVisitBump() = runBlocking<Unit> {
        val places = db.places()
        val id = places.insert(place("Cafe", 100, placeId = "ChIJ-made-up"))
        val found = places.byPlaceId("ChIJ-made-up")!!
        assertEquals(id, found.id)
        assertNull(places.byPlaceId("ChIJ-other"))
        places.update(found.copy(lastUsedAt = 999, useCount = found.useCount + 1))
        assertEquals(2, places.byPlaceId("ChIJ-made-up")!!.useCount)
        places.delete(places.byPlaceId("ChIJ-made-up")!!)
        assertTrue(places.list().isEmpty())
    }

    @Test fun clearRecentsKeepsFavourites() = runBlocking<Unit> {
        val places = db.places()
        places.insert(place("Recent", 100))
        places.insert(place("Fav", 50, PlaceKind.FAVOURITE))
        places.clearRecents()
        assertEquals(listOf("Fav"), places.list().map { it.name })
    }

    @Test fun trimKeepsTheNewest200RecentsAndEveryFavourite() = runBlocking<Unit> {
        val places = db.places()
        for (i in 1..205) places.insert(place("R$i", i.toLong()))
        places.insert(place("Fav", 0, PlaceKind.FAVOURITE))
        places.trimRecents()
        val all = places.list(limit = 500)
        assertEquals(201, all.size)
        assertEquals("Fav", all.first().name)
        // R1..R5 were the oldest five; R6 is the oldest left.
        assertEquals("R6", all.last().name)
        assertTrue(all.none { it.name in setOf("R1", "R2", "R3", "R4", "R5") })
    }

    @Test fun reportsTabLeavesDiscardedOutNewestFirst() = runBlocking<Unit> {
        val reports = db.reports()
        reports.insert(report(100, ReportStatus.DRAFT))
        reports.insert(report(300, ReportStatus.SENT))
        reports.insert(report(400, ReportStatus.DISCARDED))
        reports.insert(report(200, ReportStatus.QUEUED))

        assertEquals(listOf(300L, 200L, 100L), reports.observe().first().map { it.createdAt })
        assertEquals(listOf(100L), reports.withStatus(ReportStatus.DRAFT).map { it.createdAt })
        assertEquals(listOf(200L), reports.withStatus(ReportStatus.QUEUED).map { it.createdAt })
        assertTrue(reports.withStatus(ReportStatus.FAILED).isEmpty())
    }

    @Test fun reportRowKeepsItsDefaultsAndUpdatesInPlace() = runBlocking<Unit> {
        val reports = db.reports()
        val id = reports.insert(report(100, ReportStatus.DRAFT))
        val stored = reports.get(id)!!
        assertEquals(LimitSource.NONE, stored.limitSource)
        assertNull(stored.audioPath)
        assertNull(stored.transcription)
        reports.update(stored.copy(kind = ReportKind.NEW_AVERAGE, status = ReportStatus.FAILED, lastError = "Not accepted"))
        val after = reports.get(id)!!
        assertEquals(ReportKind.NEW_AVERAGE, after.kind)
        assertEquals("Not accepted", after.lastError)
        // Undo deletes the row outright (Discard keeps it as DISCARDED).
        reports.delete(id)
        assertNull(reports.get(id))
    }

    @Test fun contactCacheUsedAtOrdersByLastLookupAndReplaces() = runBlocking<Unit> {
        val cache = db.contactGeocodes()
        cache.put(ContactGeocodeEntity("hash-a", "place-a", 52.0, -1.0, "1 Example Road", updatedAt = 10))
        cache.put(ContactGeocodeEntity("hash-b", null, 53.0, -2.0, null, updatedAt = 20))
        assertEquals(mapOf("hash-a" to 10L, "hash-b" to 20L), cache.usedAt())

        // A fresh lookup for the same address overwrites the entry (REPLACE).
        cache.put(ContactGeocodeEntity("hash-a", "place-a2", 52.5, -1.5, "2 Example Road", updatedAt = 30))
        assertEquals(30L, cache.usedAt()["hash-a"])
        assertEquals("2 Example Road", cache.get("hash-a")!!.formattedAddress)
        assertEquals(2, cache.usedAt().size)
        assertNull(cache.get("hash-missing"))

        cache.clear()
        assertTrue(cache.usedAt().isEmpty())
    }
}

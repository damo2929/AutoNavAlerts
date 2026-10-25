// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.core.poi.Grid
import io.autonav.alerts.core.poi.ZoneRole
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
 * The POI side of `poi.db` on an in-memory Room database (DESIGN.md §4.1, §4.3, §4.4; the Room rows of §15.2
 * moved onto the JVM): cascade delete of a set's categories and POIs, the startup cleanup of STAGING sets,
 * the "active" filter every lookup applies, and the grid-cell query the alert cache and the map use (enabled
 * sets only, hidden and expired POIs left out). Runs on the test thread through runBlocking.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PoiDaoTest {
    private lateinit var db: AppDatabase
    private val sets get() = db.poiSets()
    private val pois get() = db.pois()

    @Before fun open() { db = DataFixtures.memoryDb(ApplicationProvider.getApplicationContext()) }
    @After fun close() = db.close()

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test fun deletingASetCascadesToItsCategoriesAndPois() = runBlocking<Unit> {
        val keep = sets.insert(DataFixtures.set("Keep"))
        val gone = sets.insert(DataFixtures.set("Gone"))
        db.categories().insert(PoiCategoryEntity(setId = gone, sourceCode = "1", name = "Fixed camera"))
        db.categories().insert(PoiCategoryEntity(setId = keep, sourceCode = "1", name = "Fixed camera"))
        pois.insertAll(listOf(DataFixtures.poi(gone, 52.0, -1.0), DataFixtures.poi(gone, 52.1, -1.1), DataFixtures.poi(keep, 52.2, -1.2)))

        sets.delete(gone)

        assertNull(sets.get(gone))
        assertEquals(1, count("poi"))
        assertEquals(1, count("poi_category"))
        assertEquals(listOf(keep), db.categories().all().map { it.setId })
    }

    @Test fun startupCleanupDropsOnlyStagingSetsAndTheirRows() = runBlocking<Unit> {
        val active = sets.insert(DataFixtures.set("Live"))
        val staging = sets.insert(DataFixtures.set("Half imported", state = SetState.STAGING).copy(replacesSetId = active))
        pois.insertAll(listOf(DataFixtures.poi(active, 52.0, -1.0), DataFixtures.poi(staging, 52.0, -1.0)))

        sets.deleteStaging()

        assertEquals(listOf(active), sets.active().map { it.id })
        assertNull(sets.get(staging))
        assertEquals(1, count("poi"))
    }

    @Test fun lookupsSeeActiveSetsOnlyAndListThemByName() = runBlocking<Unit> {
        sets.insert(DataFixtures.set("Zebra", source = PoiSourceType.PGW, format = "OV2:gatso_30"))
        sets.insert(DataFixtures.set("Alpha", source = PoiSourceType.PGW_PENDING, format = "OV2:pmobile"))
        sets.insert(DataFixtures.set("Middle", source = PoiSourceType.REPORT, format = "REPORT"))
        sets.insert(DataFixtures.set("Staged", source = PoiSourceType.PGW, format = "OV2:gatso_30", state = SetState.STAGING))
        sets.insert(DataFixtures.set("Hand", source = PoiSourceType.FILE, format = "KML"))

        assertEquals(listOf("Alpha", "Hand", "Middle", "Zebra"), sets.observeActive().first().map { it.name })
        assertEquals(4, sets.active().size)
        // The staging copy with the same import key is invisible until the swap (§4.4).
        assertEquals("Zebra", sets.byFormat("OV2:gatso_30")?.name)
        assertEquals("Middle", sets.bySource(PoiSourceType.REPORT)?.name)
        assertNull(sets.byFormat("OV2:specs_50"))
        // Both PGW sources, nothing else (§5.2 "sets from an older layout are removed" reads this).
        assertEquals(setOf("Zebra", "Alpha"), sets.pgwSets().map { it.name }.toSet())
    }

    @Test fun updateAllWritesEverySetAtOnce() = runBlocking<Unit> {
        val a = sets.insert(DataFixtures.set("A"))
        val b = sets.insert(DataFixtures.set("B"))
        sets.updateAll(listOf(sets.get(a)!!.copy(alertsEnabled = false), sets.get(b)!!.copy(soundEnabled = false, name = "B2")))
        assertEquals(false, sets.get(a)!!.alertsEnabled)
        assertEquals("B2", sets.get(b)!!.name)
        assertEquals(false, sets.get(b)!!.soundEnabled)
    }

    @Test fun cellQueryReturnsOnlyVisiblePoisOfActiveEnabledSets() = runBlocking<Unit> {
        val now = 1_000_000L
        val live = sets.insert(DataFixtures.set("Live"))
        val off = sets.insert(DataFixtures.set("Switched off", enabled = false))
        val staged = sets.insert(DataFixtures.set("Staged", state = SetState.STAGING))
        val lat = 52.4801
        val lon = -1.9025
        pois.insertAll(listOf(
            DataFixtures.poi(live, lat, lon, sourceId = "visible"),
            DataFixtures.poi(live, lat, lon, hidden = true, sourceId = "hidden by report"),
            DataFixtures.poi(live, lat, lon, expiresAt = now, sourceId = "expired at now"),
            DataFixtures.poi(live, lat, lon, expiresAt = now + 1, sourceId = "not yet expired"),
            DataFixtures.poi(off, lat, lon, sourceId = "set off"),
            DataFixtures.poi(staged, lat, lon, sourceId = "staging"),
            DataFixtures.poi(live, lat + 0.5, lon, sourceId = "other cell"),
        ))

        val got = pois.inCells(listOf(Grid.cellOf(lat, lon)), now).map { it.sourceId }.toSet()

        assertEquals(setOf("visible", "not yet expired"), got)
        assertEquals(setOf("visible", "not yet expired", "other cell"),
            pois.inCells(listOf(Grid.cellOf(lat, lon), Grid.cellOf(lat + 0.5, lon)), now).map { it.sourceId }.toSet())
        assertTrue(pois.inCells(listOf(Grid.cellOf(10.0, 10.0)), now).isEmpty())
    }

    @Test fun hidingAndExpiryChangeWhatTheCellQuerySees() = runBlocking<Unit> {
        val set = sets.insert(DataFixtures.set("Live"))
        val cell = Grid.cellOf(53.0, -2.0)
        val camera = pois.insert(DataFixtures.poi(set, 53.0, -2.0))
        val report = pois.insert(DataFixtures.poi(set, 53.0, -2.0, expiresAt = 500L))
        assertEquals(setOf(camera, report), pois.inCells(listOf(cell), 100L).map { it.id }.toSet())

        // "Camera not there" hides it without deleting it (§8.5); un-hiding brings it back.
        pois.setHidden(camera, true)
        assertEquals(listOf(report), pois.inCells(listOf(cell), 100L).map { it.id })
        assertEquals(true, pois.get(camera)!!.hiddenByReport)
        pois.setHidden(camera, false)

        // Startup purge drops a reported mobile site at or past its expiry, never a permanent POI.
        pois.deleteExpired(499L)
        assertEquals(2, count("poi"))
        pois.deleteExpired(500L)
        assertNull(pois.get(report))
        assertEquals(listOf(camera), pois.inCells(listOf(cell), 100L).map { it.id })
    }

    @Test fun poiUpdateAndDeleteActOnOneRow() = runBlocking<Unit> {
        val set = sets.insert(DataFixtures.set("My reports", source = PoiSourceType.REPORT))
        val id = pois.insert(DataFixtures.poi(set, 51.0, -0.1))
        pois.update(pois.get(id)!!.copy(name = "Mobile camera (reported)", speedLimit = 40))
        assertEquals("Mobile camera (reported)", pois.get(id)!!.name)
        assertEquals(40, pois.get(id)!!.speedLimit)
        pois.delete(id)
        assertNull(pois.get(id))
    }

    @Test fun aRepeatedCategoryCodeInOneSetKeepsTheFirstRow() = runBlocking<Unit> {
        val set = sets.insert(DataFixtures.set("Cams"))
        val other = sets.insert(DataFixtures.set("Other"))
        val first = db.categories().insert(PoiCategoryEntity(setId = set, sourceCode = "4", name = "Average speed camera", zoneRole = ZoneRole.ZONE_POINT))
        val repeat = db.categories().insert(PoiCategoryEntity(setId = set, sourceCode = "4", name = "Duplicate"))
        val elsewhere = db.categories().insert(PoiCategoryEntity(setId = other, sourceCode = "4", name = "Same code, other set"))
        db.categories().insert(PoiCategoryEntity(setId = set, sourceCode = "1", name = "Fixed camera"))

        assertTrue(first > 0)
        assertEquals(-1L, repeat)
        assertTrue(elsewhere > 0)
        // forSet is by name (the set editor's list); the kept row is the first one, zone role and all.
        assertEquals(listOf("Average speed camera", "Fixed camera"), db.categories().forSet(set).map { it.name })
        assertEquals(ZoneRole.ZONE_POINT, db.categories().forSet(set).first().zoneRole)
        assertEquals(3, db.categories().all().size)
    }
}

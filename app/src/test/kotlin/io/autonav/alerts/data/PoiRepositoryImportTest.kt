// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.Grid
import io.autonav.alerts.core.poi.ZoneRole
import io.autonav.alerts.testing.DataFixtures
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * `PoiRepository`'s hand imports against an in-memory database (DESIGN.md §4.4 staging and swap, §5 importers,
 * §5.1 failure rule, §5.8 zips). Hard rule 9 (AGENTS.md §3.1): every file in a zip becomes its own set, never
 * merged, named after its file with no prompt, with a matching `.bmp`/`.png` as its icon. Files are made up
 * (hard rule 6) and picked as `file://` URIs. Runs on the test thread; the repository moves to Dispatchers.IO.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PoiRepositoryImportTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: PoiRepository
    private lateinit var dir: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = DataFixtures.memoryDb(context)
        repo = PoiRepository(context, db, IconRepository(context))
        dir = File(context.cacheDir, "picked").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() = db.close()

    private fun file(name: String, text: String) = File(dir, name).apply { writeText(text) }
    private fun uri(f: File): Uri = Uri.fromFile(f)

    private fun gpx(vararg wpts: String) = "<?xml version=\"1.0\"?><gpx version=\"1.1\">" + wpts.joinToString("") + "</gpx>"
    private fun wpt(lat: Double, lon: Double, name: String, type: String? = null, course: Int? = null) =
        "<wpt lat=\"$lat\" lon=\"$lon\"><name>$name</name>" + (type?.let { "<type>$it</type>" } ?: "") +
            (course?.let { "<course>$it</course>" } ?: "") + "</wpt>"

    private suspend fun poisOf(setId: Long): List<PoiEntity> =
        db.openHelper.readableDatabase.query("SELECT id FROM poi WHERE setId = $setId ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(db.pois().get(c.getLong(0))!!) }
        }

    @Test fun aSingleFileBecomesOneSetNamedAfterTheFile() = runBlocking<Unit> {
        val f = file("Croydon_Tramlink_Stop_PGPSW.gpx", gpx(
            wpt(51.3721, -0.0982, "Stop 20 mph", type = "tram", course = 90),
            wpt(51.3750, -0.1000, "Stop two", type = "tram"),
        ))

        val out = repo.importFile(uri(f))

        assertFalse(out.message, out.failed)
        assertEquals(2, out.imported)
        val set = db.poiSets().get(out.setId!!)!!
        assertEquals("Croydon Tramlink Stop", set.name)
        assertEquals(PoiSourceType.FILE, set.source)
        assertEquals("GPX", set.format)
        assertEquals(IconRepository.PIN, set.defaultIconId)
        assertEquals(SetState.ACTIVE, set.state)
        assertEquals(2, set.poiCount)
        assertNull(set.version)
        val rows = poisOf(set.id)
        assertEquals(DirectionType.ONE_WAY, rows[0].directionType)
        assertEquals(90f, rows[0].bearing)
        assertEquals(20, rows[0].speedLimit)
        assertEquals(Grid.cellOf(51.3721, -0.0982), rows[0].gridCell)
        // No bearing in the data: that POI alone is ANY (hard rule 1 only forbids ANY for one that has a bearing).
        assertEquals(DirectionType.ANY, rows[1].directionType)
        // A hand import names its categories by the source's own code (§5).
        assertEquals(listOf("tram"), db.categories().forSet(set.id).map { it.name })
    }

    @Test fun aZipOfSeveralFilesMakesOneSetPerFileWithItsOwnIcon() = runBlocking<Unit> {
        val zip = DataFixtures.zip(File(dir, "my_pois.zip"), listOf(
            "schools.gpx" to gpx(wpt(52.1, -1.2, "School 20"), wpt(52.2, -1.3, "School two")).toByteArray(),
            "schools.png" to DataFixtures.png(),
            "fuel_stations.txt" to DataFixtures.igo("-1.50000,52.30000,1,30,1,360", "-1.60000,52.40000,4,50,2,180").toByteArray(),
            "readme.txt" to "Made-up readme".toByteArray(),
            "Licence.txt" to "Made-up licence".toByteArray(),
        ))

        val out = repo.importFile(uri(zip))

        assertFalse(out.message, out.failed)
        assertEquals(4, out.imported)
        assertEquals("Imported 4 POIs from my_pois.zip into 2 sets", out.message)
        val sets = db.poiSets().active().associateBy { it.name }
        assertEquals(setOf("schools", "fuel stations"), sets.keys)
        assertEquals("user:imp_schools", sets.getValue("schools").defaultIconId)
        assertTrue(File(context.filesDir, "icons/imp_schools.png").exists())
        assertEquals(IconRepository.PIN, sets.getValue("fuel stations").defaultIconId)
        // Keyed by zip and file, so another zip's file of the same name is a different set (§5.8).
        assertEquals("GPX:file:my_pois.zip/schools.gpx", sets.getValue("schools").format)
        assertEquals("IGO_TXT:file:my_pois.zip/fuel_stations.txt", sets.getValue("fuel stations").format)
        // Never merged: each set holds its own file's rows only.
        assertEquals(2, poisOf(sets.getValue("schools").id).size)
        val fuel = poisOf(sets.getValue("fuel stations").id)
        assertEquals(listOf(0f, 180f), fuel.map { it.bearing })
        assertEquals(listOf(DirectionType.ONE_WAY, DirectionType.BOTH), fuel.map { it.directionType })
        // A hand-imported iGO file isn't PGW: no zone role from the code (zones come from PGW categories, §11 step 8).
        assertTrue(db.categories().forSet(sets.getValue("fuel stations").id).all { it.zoneRole == ZoneRole.NONE })
        assertEquals(listOf("schools.gpx", "fuel_stations.txt").sorted(), repo.zipPoiFileNames(uri(zip)).sorted())
    }

    @Test fun reimportingTheSameZipUpdatesItsSetsAndKeepsTheUsersChoices() = runBlocking<Unit> {
        val entries = listOf(
            "a.gpx" to gpx(wpt(52.1, -1.2, "One", type = "x")).toByteArray(),
            "b.gpx" to gpx(wpt(52.2, -1.3, "Two")).toByteArray(),
        )
        val zip = DataFixtures.zip(File(dir, "pack.zip"), entries)
        repo.importFile(uri(zip))
        val first = db.poiSets().active().associateBy { it.name }
        val a = first.getValue("a")
        repo.updateSet(a.copy(name = "Renamed by me", soundEnabled = false, alertsEnabled = false, defaultIconId = "builtin:star"))
        val cat = db.categories().forSet(a.id).single()
        db.openHelper.writableDatabase.execSQL("UPDATE poi_category SET iconId = 'builtin:school', firstTemplate = 'Mine {distance}' WHERE id = ${cat.id}")

        DataFixtures.zip(zip, entries.map { (n, b) -> if (n == "a.gpx") n to gpx(wpt(52.1, -1.2, "One", type = "x"), wpt(52.3, -1.4, "New", type = "x")).toByteArray() else n to b })
        val out = repo.importFile(uri(zip))

        assertFalse(out.message, out.failed)
        val after = db.poiSets().active()
        assertEquals(2, after.size)
        val renamed = after.single { it.format == a.format }
        assertNotEquals(a.id, renamed.id)
        assertEquals("Renamed by me", renamed.name)
        assertFalse(renamed.soundEnabled)
        assertFalse(renamed.alertsEnabled)
        assertEquals("builtin:star", renamed.defaultIconId)
        assertEquals(2, renamed.poiCount)
        val newCat = db.categories().forSet(renamed.id).single()
        assertEquals("builtin:school", newCat.iconId)
        assertEquals("Mine {distance}", newCat.firstTemplate)
        // The old set and its rows are gone (the swap's delete cascades).
        assertNull(db.poiSets().get(a.id))
        assertEquals(3, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM poi").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun aZipWithOnePoiFileIsImportedAsThatFile() = runBlocking<Unit> {
        val zip = DataFixtures.zip(File(dir, "single.zip"), listOf(
            "Speed_Bumps.gpx" to gpx(wpt(52.1, -1.2, "Bump")).toByteArray(),
            "readme.txt" to "ignored".toByteArray(),
        ))
        val out = repo.importFile(uri(zip))
        assertFalse(out.message, out.failed)
        val set = db.poiSets().get(out.setId!!)!!
        assertEquals("Speed Bumps", set.name)
        // The one-file path: the bare format as the key, and no icon search.
        assertEquals("GPX", set.format)
        assertEquals("Imported 1 POIs", out.message)
    }

    @Test fun reimportOfAPickedFileReplacesTheSetItWasAskedTo() = runBlocking<Unit> {
        val f = file("cams.gpx", gpx(wpt(52.1, -1.2, "A")))
        val first = repo.importFile(uri(f)).setId!!
        val stage = StageCols(true, DistanceMode.FIXED, 500, null)
        repo.updateSet(db.poiSets().get(first)!!.copy(name = "My cams", firstAlert = stage, minZoom = 13f))
        f.writeText(gpx(wpt(52.1, -1.2, "A"), wpt(52.2, -1.2, "B")))

        val out = repo.importFile(uri(f), replaceSetId = first)

        val set = db.poiSets().active().single()
        assertEquals(out.setId, set.id)
        assertEquals("My cams", set.name)
        assertEquals(stage, set.firstAlert)
        assertEquals(13f, set.minZoom)
        assertEquals(2, poisOf(set.id).size)
    }

    @Test fun filesThatCantBeImportedStoreNothing() = runBlocking<Unit> {
        val unknown = repo.importFile(uri(File(dir, "blob.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }))
        assertTrue(unknown.failed)
        assertEquals("Unknown file format", unknown.message)

        val csv = repo.importFile(uri(file("points.csv", "lat,lon\n52,-1\n")))
        assertTrue(csv.failed)
        assertEquals("CSV files need a column mapping", csv.message)

        val noPoi = repo.importFile(uri(DataFixtures.zip(File(dir, "empty.zip"), listOf("Licence.txt" to "x".toByteArray()))))
        assertTrue(noPoi.failed)
        assertEquals("No POI file found in the zip", noPoi.message)

        // Over 20% bad rows (§5.1): the whole file fails and the samples name the lines.
        val bad = repo.importFile(uri(file("bad.txt", DataFixtures.igo("-1.5,52.3,1,30,1,90", "x,y,1,30,1,90", "-1.5,999,1,30,1,90"))))
        assertTrue(bad.failed)
        assertEquals(1, bad.imported)
        assertEquals(2, bad.skipped)
        assertTrue(bad.message, bad.message.startsWith("Import failed: 2 bad rows out of 3"))
        assertEquals(2, bad.errorSamples.size)
        assertTrue(bad.errorSamples.first(), bad.errorSamples.first().startsWith("Line 3:"))

        assertTrue(db.poiSets().active().isEmpty())
        assertEquals(0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM poi_set").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun aZipWithSomeBadFilesKeepsTheGoodOnesAndListsTheRest() = runBlocking<Unit> {
        val zip = DataFixtures.zip(File(dir, "mixed.zip"), listOf(
            "good.gpx" to gpx(wpt(52.1, -1.2, "Fine")).toByteArray(),
            "broken.txt" to DataFixtures.igo("nonsense,row,1,30,1,90").toByteArray(),
        ))
        val out = repo.importFile(uri(zip))
        assertFalse(out.failed)
        assertEquals(listOf("good"), db.poiSets().active().map { it.name })
        assertEquals(1, out.errorSamples.size)
        assertTrue(out.errorSamples.single(), out.errorSamples.single().startsWith("broken.txt: Import failed"))
        assertTrue(out.message, out.message.contains("Not imported: broken.txt"))
    }

    @Test fun setNamesComeFromFileNames() {
        assertEquals("Croydon Tramlink Stop", repo.setNameFor("Croydon_Tramlink_Stop_PGPSW.ov2"))
        assertEquals("speed cams", repo.setNameFor("speed_cams.kml"))
        assertEquals("Schools-pgpsw extra", repo.setNameFor("Schools-pgpsw_extra.gpx"))
        assertEquals("Fuel", repo.setNameFor("Fuel-pgpsw.csv"))
        assertEquals(".gpx", repo.setNameFor(".gpx"))
    }

    @Test fun cellLookupsAreChunkedUnderSqlitesVariableLimit() = runBlocking<Unit> {
        val set = db.poiSets().insert(DataFixtures.set("Live"))
        db.pois().insert(DataFixtures.poi(set, 52.4801, -1.9025))
        // 1,500 cells: two statements of at most 900, the POI's cell in the second.
        val cells = (0L until 1_499L).map { it + 10 } + Grid.cellOf(52.4801, -1.9025)
        assertEquals(1, repo.poisInCells(cells).size)
        assertTrue(repo.poisInCells(emptyList()).isEmpty())
    }

    @Test fun reportsSetIsMadeOnceAndStartupTidiesStagingAndExpiredPois() = runBlocking<Unit> {
        val reports = repo.reportsSetId()
        assertEquals(reports, repo.reportsSetId())
        val set = db.poiSets().get(reports)!!
        assertEquals("My reports", set.name)
        assertEquals(PoiSourceType.REPORT, set.source)
        assertEquals(IconRepository.CAMERA_MOBILE, set.defaultIconId)

        val expired = repo.addReportPoi(DataFixtures.poi(reports, 52.0, -1.0, expiresAt = System.currentTimeMillis() - 1))
        val permanent = repo.addReportPoi(DataFixtures.poi(reports, 52.0, -1.0))
        db.poiSets().insert(DataFixtures.set("Interrupted", state = SetState.STAGING))
        repo.purgeExpired()
        repo.cleanupStaging()
        assertNull(repo.poi(expired))
        assertEquals(permanent, repo.poi(permanent)!!.id)
        assertEquals(listOf("My reports"), db.poiSets().active().map { it.name })
        assertEquals(1, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM poi_set").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun everyImportSignalsTheCachesToReload() = runBlocking<Unit> {
        val signal = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { repo.changes.first() } }
        repo.importFile(uri(file("one.gpx", gpx(wpt(52.1, -1.2, "A")))))
        signal.await()
        val again = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { repo.changes.first() } }
        repo.deleteSet(db.poiSets().active().single().id)
        again.await()
        assertTrue(db.poiSets().active().isEmpty())
    }
}

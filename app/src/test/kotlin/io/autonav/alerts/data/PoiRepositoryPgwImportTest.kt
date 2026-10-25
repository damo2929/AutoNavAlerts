// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.core.poi.ZoneRole
import io.autonav.alerts.testing.DataFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * `PoiRepository.importPgwZip` against an in-memory database (DESIGN.md §5.2 the iGO-by-type table, §5.2a the
 * OV2 package and its icons, §4.4 the swap that keeps the user's choices across syncs). Hard rule 9: one set per
 * file, never merged; hard rule 6: every zip here is made up in the shape of PGW's, never real data; hard rule 1:
 * each camera's bearing and direction type come through as the file states them. Runs on the test thread.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PoiRepositoryPgwImportTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var icons: IconRepository
    private lateinit var repo: PoiRepository
    private lateinit var dir: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = DataFixtures.memoryDb(context)
        icons = IconRepository(context)
        repo = PoiRepository(context, db, icons)
        dir = File(context.cacheDir, "pgw-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() = db.close()

    /** A made-up iGO "consolidated by type" download (§5.2): gatso with a type 11, specs, pending mobiles, Release.txt, licence. */
    private fun igoZip(name: String = "igo.zip", gatsoRows: List<String> = listOf("-1.50000,52.30000,1,30,1,360", "-1.51000,52.31000,11,40,2,90")) =
        DataFixtures.zip(File(dir, name), listOf(
            "_Need_Help.htm" to "<html>help</html>".toByteArray(),
            "pocketgps_uk_gatso.txt" to DataFixtures.igo(*gatsoRows.toTypedArray()).toByteArray(),
            "pocketgps_uk_specs.txt" to DataFixtures.igo("-1.60000,52.40000,4,50,1,180", "-1.61000,52.41000,4,50,1,180").toByteArray(),
            "pocketgps_uk_pmobile.txt" to DataFixtures.igo("-1.70000,52.50000,5,30,,").toByteArray(),
            "Release.txt" to "Version 99.001 (01 Jan 99)\r\n".toByteArray(),
            "Licence.txt" to "Made-up licence".toByteArray(),
        ))

    private fun sets() = runBlocking { db.poiSets().active().associateBy { it.name } }
    private fun poisOf(setId: Long): List<PoiEntity> = runBlocking {
        db.openHelper.readableDatabase.query("SELECT id FROM poi WHERE setId = $setId ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(db.pois().get(c.getLong(0))!!) }
        }
    }

    @Test fun theIgoPackageGivesOneSetPerTypeFile() = runBlocking<Unit> {
        val out = repo.importPgwZip(igoZip(), "99.1")

        assertFalse(out.message, out.failed)
        assertEquals("Imported 5 cameras in 3 sets", out.message)
        val sets = sets()
        assertEquals(setOf("PGW fixed cameras", "PGW average speed cameras (SPECS)", "PGW pending mobile sites (unverified)"), sets.keys)
        val fixed = sets.getValue("PGW fixed cameras")
        assertEquals(PoiSourceType.PGW, fixed.source)
        assertEquals("IGO_TXT:gatso", fixed.format)
        assertEquals("99.1", fixed.version)
        assertEquals(IconRepository.CAMERA_FIXED, fixed.defaultIconId)
        // §5.2: type 11 is a fixed-camera subtype by name; its icon is red light + speed (ISSUES N4).
        val cats = db.categories().forSet(fixed.id).associateBy { it.sourceCode }
        assertEquals("Fixed camera", cats.getValue("1").name)
        assertEquals("Fixed camera (type 11)", cats.getValue("11").name)
        assertEquals(IconRepository.CAMERA_FIXED, cats.getValue("1").iconId)
        assertEquals(IconRepository.CAMERA_REDLIGHT_SPEED, cats.getValue("11").iconId)
        // Direction=360 is 0; DirType 2 is BOTH; limits in mph (§5.2 column table).
        val rows = poisOf(fixed.id)
        assertEquals(listOf(0f, 90f), rows.map { it.bearing })
        assertEquals(listOf(DirectionType.ONE_WAY, DirectionType.BOTH), rows.map { it.directionType })
        assertEquals(listOf(SpeedUnit.MPH, SpeedUnit.MPH), rows.map { it.speedUnit })
        // SPECS points chain into zones (§11 step 8).
        val specs = sets.getValue("PGW average speed cameras (SPECS)")
        assertEquals(ZoneRole.ZONE_POINT, db.categories().forSet(specs.id).single().zoneRole)
        // Pending sites: their own source, alerts on by default (owner, 2026-10-23), the "P" icon, no direction given → ANY.
        val pending = sets.getValue("PGW pending mobile sites (unverified)")
        assertEquals(PoiSourceType.PGW_PENDING, pending.source)
        assertTrue(pending.alertsEnabled)
        assertEquals(IconRepository.CAMERA_PENDING, db.categories().forSet(pending.id).single().iconId)
        assertEquals(DirectionType.ANY, poisOf(pending.id).single().directionType)
        assertNull(poisOf(pending.id).single().bearing)
    }

    @Test fun aSyncKeepsTheUsersChoicesAndReplacesTheRows() = runBlocking<Unit> {
        repo.importPgwZip(igoZip(), "99.1")
        val fixed = sets().getValue("PGW fixed cameras")
        val pending = sets().getValue("PGW pending mobile sites (unverified)")
        repo.updateSets(listOf(
            fixed.copy(name = "Fixed (mine)", soundEnabled = false, showOnMap = false),
            pending.copy(alertsEnabled = false),
        ))
        val cat = db.categories().forSet(fixed.id).single { it.sourceCode == "1" }
        db.openHelper.writableDatabase.execSQL("UPDATE poi_category SET iconId = 'builtin:star', alertsEnabled = 0 WHERE id = ${cat.id}")

        val out = repo.importPgwZip(igoZip("igo2.zip", gatsoRows = listOf("-1.50000,52.30000,1,30,1,360", "-1.52000,52.32000,1,30,1,45", "-1.53000,52.33000,1,30,1,46")), "99.2")

        assertFalse(out.message, out.failed)
        val after = db.poiSets().active().associateBy { it.format }
        assertEquals(3, after.size)
        val newFixed = after.getValue("IGO_TXT:gatso")
        assertEquals("Fixed (mine)", newFixed.name)
        assertFalse(newFixed.soundEnabled)
        assertFalse(newFixed.showOnMap)
        assertEquals("99.2", newFixed.version)
        assertEquals(3, poisOf(newFixed.id).size)
        val newCat = db.categories().forSet(newFixed.id).single { it.sourceCode == "1" }
        assertEquals("builtin:star", newCat.iconId)
        assertFalse(newCat.alertsEnabled)
        // The user's "off" on pending sites survives the sync; only a first import defaults it on.
        assertFalse(after.getValue("IGO_TXT:pmobile").alertsEnabled)
        assertNull(db.poiSets().get(fixed.id))
    }

    @Test fun setsFromAnotherLayoutOrPackageAreRemovedButHandImportsStay() = runBlocking<Unit> {
        val oldOv2 = db.poiSets().insert(DataFixtures.set("PGW fixed cameras · 30 mph", source = PoiSourceType.PGW, format = "OV2:gatso_30"))
        val oldSingle = db.poiSets().insert(DataFixtures.set("PocketGPSWorld cameras (all)", source = PoiSourceType.PGW, format = "IGO_TXT:sc"))
        val mine = db.poiSets().insert(DataFixtures.set("My cams", source = PoiSourceType.FILE, format = "GPX"))
        val reports = repo.reportsSetId()

        repo.importPgwZip(igoZip(), "99.1")

        assertNull(db.poiSets().get(oldOv2))
        assertNull(db.poiSets().get(oldSingle))
        assertEquals("My cams", db.poiSets().get(mine)!!.name)
        assertEquals("My reports", db.poiSets().get(reports)!!.name)
    }

    @Test fun theOv2PackageUsesTheDownloadsIconsNamesAndCameraIds() = runBlocking<Unit> {
        val zip = DataFixtures.zip(File(dir, "ov2.zip"), listOf(
            "pocketgps_uk_gatso_30.ov2" to DataFixtures.ov2(
                Triple(52.30000, -1.50000, "E-R-MONITRON:3209@30"),
                Triple(52.31000, -1.51000, "NW-GATSO:3210@30"),
            ),
            "pocketgps_uk_gatso_30_X2.bmp" to DataFixtures.png(),
            "pocketgps_uk_gatso_30.bmp" to DataFixtures.png(android.graphics.Color.BLUE),
            "pocketgps_uk_specs_var.ov2" to DataFixtures.ov2(Triple(52.4, -1.6, "S-tSPECS:4001@0")),
            "pocketgps_uk_redlight.ov2" to DataFixtures.ov2(Triple(52.5, -1.7, "N-REDLIGHT:5001")),
            "licence_X2_icons.txt" to "Made-up icon licence text".toByteArray(),
            "Release.txt" to "Version 99.001 (01 Jan 99)".toByteArray(),
        ))

        val out = repo.importPgwZip(zip, "99.1")

        assertFalse(out.message, out.failed)
        val sets = sets()
        assertEquals(setOf("PGW fixed cameras · 30 mph", "PGW average speed cameras (SPECS) · variable limit", "PGW red light cameras"), sets.keys)
        val gatso = sets.getValue("PGW fixed cameras · 30 mph")
        assertEquals("OV2:gatso_30", gatso.format)
        // §5.2a: the download's own icon is the set's, and its categories don't override it.
        assertEquals("user:pgw_gatso_30", gatso.defaultIconId)
        assertTrue(db.categories().forSet(gatso.id).all { it.iconId == null })
        assertEquals(setOf("Monitron", "Gatso"), db.categories().forSet(gatso.id).map { it.name }.toSet())
        val rows = poisOf(gatso.id)
        assertEquals(listOf("3209", "3210"), rows.map { it.sourceId })
        assertEquals(listOf(DirectionType.BOTH, DirectionType.ONE_WAY), rows.map { it.directionType })
        assertEquals(listOf(90f, 315f), rows.map { it.bearing })
        assertEquals(listOf(30, 30), rows.map { it.speedLimit })
        // A file with no icon of its own falls back to the built-in for its family.
        val red = sets.getValue("PGW red light cameras")
        assertEquals(IconRepository.CAMERA_REDLIGHT, red.defaultIconId)
        assertEquals(IconRepository.CAMERA_REDLIGHT, db.categories().forSet(red.id).single().iconId)
        // Temporary SPECS still chain into zones; @0 is a variable limit, stored as no limit.
        val specsCat = db.categories().forSet(sets.getValue("PGW average speed cameras (SPECS) · variable limit").id).single()
        assertEquals("Temporary Average speed (SPECS) (roadworks)", specsCat.name)
        assertEquals(ZoneRole.ZONE_POINT, specsCat.zoneRole)
        assertNull(poisOf(sets.getValue("PGW average speed cameras (SPECS) · variable limit").id).single().speedLimit)
        // The icon licence travels with the icons (§5.2a).
        assertEquals("Made-up icon licence text", icons.pgwIconLicence())
    }

    @Test fun aSyncRefreshesASuppliedIconButNotOneTheUserPicked() = runBlocking<Unit> {
        fun zip(n: String) = DataFixtures.zip(File(dir, n), listOf(
            "pocketgps_uk_gatso_30.ov2" to DataFixtures.ov2(Triple(52.3, -1.5, "E-GATSO:1@30")),
            "pocketgps_uk_gatso_30.bmp" to DataFixtures.png(),
            "pocketgps_uk_mobile_40.ov2" to DataFixtures.ov2(Triple(52.4, -1.6, "W-MOBILE:2@40")),
            "pocketgps_uk_mobile_40.bmp" to DataFixtures.png(),
        ))
        repo.importPgwZip(zip("one.zip"), "1.1")
        val mobile = sets().getValue("PGW mobile camera sites · 40 mph")
        repo.updateSet(mobile.copy(defaultIconId = "user:my-own-picture"))

        repo.importPgwZip(zip("two.zip"), "1.2")

        assertEquals("user:pgw_gatso_30", sets().getValue("PGW fixed cameras · 30 mph").defaultIconId)
        assertEquals("user:my-own-picture", sets().getValue("PGW mobile camera sites · 40 mph").defaultIconId)
    }

    @Test fun aDownloadWithNoCameraFilesFailsAndKeepsTheOldSets() = runBlocking<Unit> {
        repo.importPgwZip(igoZip(), "99.1")
        val before = db.poiSets().active().map { it.id }.toSet()
        val empty = DataFixtures.zip(File(dir, "empty.zip"), listOf("_Need_Help.htm" to "<html/>".toByteArray(), "Release.txt" to "Version 1.2".toByteArray()))

        val out = repo.importPgwZip(empty, "99.2")

        assertTrue(out.failed)
        assertTrue(out.message, out.message.startsWith("No camera files in the download. The zip contained: "))
        assertTrue(out.message, out.message.contains("_Need_Help.htm"))
        assertEquals(before, db.poiSets().active().map { it.id }.toSet())
    }

    @Test fun aBadFileInADownloadFailsAloneAndTheOthersAreStillImported() = runBlocking<Unit> {
        val zip = DataFixtures.zip(File(dir, "partial.zip"), listOf(
            "pocketgps_uk_gatso.txt" to DataFixtures.igo("-1.5,52.3,1,30,1,90").toByteArray(),
            "pocketgps_uk_mobile.txt" to DataFixtures.igo("bad,row,5,30,,", "bad,row,5,30,,").toByteArray(),
        ))
        val out = repo.importPgwZip(zip, "99.1")
        assertFalse(out.failed)
        assertEquals("Imported 1 cameras in 1 sets (1 failed)", out.message)
        assertEquals(listOf("PGW fixed cameras"), db.poiSets().active().map { it.name })
        assertTrue(out.errorSamples.single(), out.errorSamples.single().startsWith("PGW mobile camera sites: Import failed"))
    }
}

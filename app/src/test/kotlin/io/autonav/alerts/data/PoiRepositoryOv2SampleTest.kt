// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.importers.Ov2Samples
import io.autonav.alerts.testing.DataFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * OV2 files with TomTom's skipper blocks (DESIGN.md §5.6) through both of the app's imports: a picked zip of 22
 * free-POI-style files (§5, one set per file with its `.bmp` icon, hard rule 9), a picked single `.ov2`, and a
 * speed-camera download (§5.2a), which reads its records through the same `Ov2Importer`. All data is made up
 * (`Ov2Samples`; hard rule 6). Bench, 2026-10-25: before the skipper fix a real free-POI zip imported as
 * "Imported 0 POIs … into 0 sets". Robolectric with native graphics, so the BMP icons decode; the test thread.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PoiRepositoryOv2SampleTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: PoiRepository
    private lateinit var dir: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = DataFixtures.memoryDb(context)
        repo = PoiRepository(context, db, IconRepository(context))
        dir = File(context.cacheDir, "ov2-sample-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() = db.close()

    private fun countIn(setId: Long): Int = DataFixtures.countIn(db, setId)

    private fun packZip(name: String = "Sample POIs (2).zip"): Pair<File, List<Ov2Samples.PackFile>> {
        val pack = Ov2Samples.pack()
        val entries = pack.flatMap { f -> listOf(f.fileName to f.bytes, f.fileName.replace(".ov2", ".bmp") to f.icon) }
        return DataFixtures.zip(File(dir, name), entries) to pack
    }

    @Test fun aPickedZipOfSkipperFilesMakesOneFullSetPerFileWithItsIcon() = runBlocking<Unit> {
        val (zip, pack) = packZip()
        val total = pack.sumOf { it.pois.size }

        val out = repo.importFile(Uri.fromFile(zip))

        assertFalse(out.message, out.failed)
        assertEquals("Imported $total POIs from Sample POIs (2).zip into 22 sets", out.message)
        val sets = db.poiSets().active().associateBy { it.name }
        assertEquals(22, sets.size)
        pack.forEach { f ->
            val set = sets.getValue(PoiRepository.setNameFor(f.fileName))
            assertEquals(f.fileName, f.pois.size, countIn(set.id))
            // The matching .bmp is the set's icon (§5).
            assertTrue(f.fileName, set.defaultIconId!!.startsWith("user:imp_"))
            assertEquals("OV2:file:Sample POIs (2).zip/${f.fileName}", set.format)
        }
    }

    @Test fun reimportingThePackUpdatesTheSameSets() = runBlocking<Unit> {
        val (zip, _) = packZip()
        repo.importFile(Uri.fromFile(zip))
        val ids = db.poiSets().active().map { it.id }.toSet()

        val again = repo.importFile(Uri.fromFile(zip))

        assertFalse(again.message, again.failed)
        assertEquals(22, db.poiSets().active().size)
        // The staging swap makes new rows for each set, but never a second set for the same file (§4.4).
        assertEquals(22, db.poiSets().active().map { it.format }.toSet().size)
        assertTrue(ids.isNotEmpty())
    }

    @Test fun aPickedSingleOv2FileWithSkippersIsReadInFull() = runBlocking<Unit> {
        val pois = Ov2Samples.grid(120, 54.9, -1.6, "Westerholm Metro", withLimits = true)
        val file = File(dir, "Westerholm_Metro_Sample.ov2").apply { writeBytes(Ov2Samples.file(pois, chain = 15)) }

        val out = repo.importFile(Uri.fromFile(file))

        assertFalse(out.message, out.failed)
        assertEquals(120, out.imported)
        val set = db.poiSets().active().single()
        assertEquals("Westerholm Metro Sample", set.name)
        assertEquals(120, countIn(set.id))
    }

    @Test fun aSpeedCameraDownloadWithSkipperBlocksKeepsEveryCameraAndItsDirection() = runBlocking<Unit> {
        val gatso = Ov2Samples.cameras(40, 52.3, -1.5, "GATSO", mph = 30)
        val specs = Ov2Samples.cameras(12, 52.6, -1.9, "SPECS", mph = 50, firstId = 95_001)
        val watermark = Ov2Samples.Poi(51.5, 0.7, "Copyright Example")
        val zip = DataFixtures.zip(File(dir, "ov2-download.zip"), listOf(
            "pocketgps_uk_gatso_30.ov2" to Ov2Samples.file(gatso + watermark, leaf = 5, chain = 3),
            "pocketgps_uk_specs_50.ov2" to Ov2Samples.file(specs + watermark, leaf = 4),
            "Release.txt" to "Version 99.001 (01 Jan 99)".toByteArray(),
        ))

        val out = repo.importPgwZip(zip, "99.1")

        assertFalse(out.message, out.failed)
        assertEquals("Imported 52 cameras in 2 sets", out.message)
        val sets = db.poiSets().active().associateBy { it.format }
        assertEquals(40, countIn(sets.getValue("OV2:gatso_30").id))
        assertEquals(12, countIn(sets.getValue("OV2:specs_50").id))
        // Hard rule 1: every camera keeps the direction its name gives; a reversible one is BOTH, never ANY.
        val dirs = db.openHelper.readableDatabase.query("SELECT directionType, bearing FROM poi WHERE setId = ${sets.getValue("OV2:gatso_30").id}").use { c ->
            buildList { while (c.moveToNext()) add(DirectionType.valueOf(c.getString(0)) to c.getFloat(1)) }
        }
        assertEquals(40, dirs.size)
        assertTrue(dirs.none { it.first == DirectionType.ANY })
        assertEquals(gatso.count { "-R-" in it.name }, dirs.count { it.first == DirectionType.BOTH })
    }
}

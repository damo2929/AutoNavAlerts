// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * OV2 files laid out as TomTom writes them (DESIGN.md §5.6): a skipper over the whole file, nested skipper
 * blocks, chains of single-child skippers, deleted records and type-3 records inside blocks, an outer block over
 * 1 MiB. Every POI must come out in file order with its place, name and limit, and a file cut anywhere must
 * give a prefix of those rows with at most one error, never a crash. The samples are made up (`Ov2Samples`;
 * AGENTS.md §3.1 rule 6); `realPoiZipIfProvided` reads a real zip only when `OV2_POI_ZIP` names one locally.
 * Bench, 2026-10-25: before the skipper fix every file of a real free-POI zip imported as 0 rows.
 */
class Ov2SampleDataTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun assertAllRead(pois: List<Ov2Samples.Poi>, bytes: ByteArray, leaf: Int = 8) {
        val r = Ov2Importer().parse(bytes)
        val want = Ov2Samples.expectedOrder(pois, leaf)
        assertEquals(want.map { it.name }, r.rows.map { it.name })
        want.zip(r.rows).forEach { (p, row) ->
            assertEquals(p.lat, row.lat, 1e-5)
            assertEquals(p.lon, row.lon, 1e-5)
            assertEquals(p.name, Ov2Samples.limitOf(p.name), row.speed)
            assertEquals(DirectionType.ANY, row.dirType)
        }
        assertEquals(0, r.errors.size)
        assertFalse(r.failed)
    }

    @Test
    fun aWholeFileSkipperTreeIsReadInFull() {
        val pois = Ov2Samples.grid(200, 51.0, -2.0, "Ashbridge Tram Stop", withLimits = true)
        assertAllRead(pois, Ov2Samples.file(pois))
    }

    @Test
    fun theFirstRecordIsASkipperCoveringTheWholeFile() {
        val bytes = Ov2Samples.file(Ov2Samples.grid(40, 51.0, -2.0, "Kestermoor Metro"))
        assertEquals(1, bytes[0].toInt())
        val block = java.nio.ByteBuffer.wrap(bytes, 1, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(bytes.size, block)
    }

    @Test
    fun fifteenChainedSkippersBeforeTheFirstPoi() {
        val pois = Ov2Samples.grid(36, 57.1, -2.1, "Larkhaven Zone")
        val bytes = Ov2Samples.file(pois, chain = 15)
        // The outer skipper, the split's own skippers, then 15 more: none of them may swallow the POIs.
        assertTrue((0 until 17).all { bytes[it * 21].toInt() == 1 })
        assertAllRead(pois, bytes)
    }

    @Test
    fun deletedAndTypeThreeRecordsInsideBlocks() {
        val pois = Ov2Samples.grid(60, 52.4, -1.9, "Marrowgate Stop")
        assertTrue(pois.any { it.type == 3 })
        assertAllRead(pois, Ov2Samples.file(pois, deletedEvery = 3))
    }

    @Test
    fun leafSizesFromOneUpward() {
        val pois = Ov2Samples.grid(37, 53.0, -1.0, "Quarlby Crossing")
        for (leaf in listOf(1, 2, 5, 37, 100)) assertAllRead(pois, Ov2Samples.file(pois, leaf = leaf), leaf)
    }

    @Test
    fun anOuterBlockOverAMebibyteIsNotMistakenForCorruption() {
        val pois = Ov2Samples.grid(40_000, 50.0, -5.0, "Northwold Level Crossing", spacing = 0.0002)
        val bytes = Ov2Samples.file(pois, leaf = 50)
        assertTrue(bytes.size > (1 shl 20))
        val r = Ov2Importer().parse(bytes.inputStream())
        assertEquals(40_000, r.rows.size)
        assertEquals(0, r.errors.size)
    }

    @Test
    fun aFileCutAnywhereGivesAPrefixAndAtMostOneErrorNeverACrash() {
        val pois = Ov2Samples.grid(30, 51.5, -0.5, "Rushcombe Zone", withLimits = true)
        val bytes = Ov2Samples.file(pois, leaf = 4, chain = 2, deletedEvery = 5)
        val want = Ov2Samples.expectedOrder(pois, 4).map { it.name }
        for (cut in 0..bytes.size) {
            val r = Ov2Importer().parse(bytes.copyOf(cut))
            assertEquals("cut $cut", want.take(r.rows.size), r.rows.map { it.name })
            assertTrue("cut $cut", r.errors.size <= 1)
        }
    }

    @Test
    fun aSkipperFirstFileIsSniffedAsOv2() {
        val bytes = Ov2Samples.file(Ov2Samples.grid(10, 51.0, -2.0, "Ivydale Stop"))
        assertEquals(PoiFormat.OV2, FormatSniffer.detect("Ivydale_Tram_Stops.ov2", bytes.copyOf(512)))
        assertEquals(PoiFormat.OV2, FormatSniffer.detect("IVYDALE.OV2", bytes.copyOf(512)))
    }

    @Test
    fun theSamplePackHasTheRealDownloadsShapeAndEveryFileReadsInFull() {
        val pack = Ov2Samples.pack()
        assertEquals(22, pack.size)
        assertEquals(22, pack.map { it.fileName }.toSet().size)
        assertTrue(pack.any { it.pois.size >= 1_800 } && pack.any { it.pois.size <= 8 })
        pack.forEach { f ->
            val r = Ov2Importer().parse(f.bytes)
            assertEquals(f.fileName, f.pois.size, r.rows.size)
            assertEquals(f.fileName, Ov2Samples.expectedOrder(f.pois).map { it.name }, r.rows.map { it.name })
            assertEquals(f.fileName, 0, r.errors.size)
            assertEquals('B'.code.toByte(), f.icon[0])
            assertEquals('M'.code.toByte(), f.icon[1])
        }
    }

    @Test
    fun theSamplePackReadsBackThroughTheZipLayerOneEntryAtATime() {
        val pack = Ov2Samples.pack()
        val zip = tmp.newFile("pack.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            pack.forEach { f ->
                z.putNextEntry(ZipEntry(f.fileName)); z.write(f.bytes); z.closeEntry()
                z.putNextEntry(ZipEntry(f.fileName.replace(".ov2", ".bmp"))); z.write(f.icon); z.closeEntry()
            }
        }
        ZipEntries.read(zip).use { entries ->
            assertEquals(44, entries.size)
            val ov2 = LazyBytes.select(entries) { it.takeIf { n -> n.endsWith(".ov2") } }
            assertEquals(pack.map { it.fileName }, ov2.keys.toList())
            pack.forEach { f -> assertEquals(f.pois.size, Ov2Importer().parse(ov2.getValue(f.fileName)).rows.size) }
        }
    }

    @Test
    fun speedCameraFilesUseTheSameReaderSoSkipperBlocksKeepEveryCamera() {
        // PgwOv2 (the speed-camera download, §5.2a) reads its records through Ov2Importer: one OV2 reader for both.
        val cams = Ov2Samples.cameras(45, 52.3, -1.5, "GATSO", mph = 30)
        val watermark = Ov2Samples.Poi(51.5, 0.7, "Copyright Example")
        val bytes = Ov2Samples.file(cams + watermark, leaf = 6, chain = 4)
        val r = PgwOv2.parse(bytes)
        val want = Ov2Samples.expectedOrder(cams + watermark, 6).filter { it !== watermark }
        assertEquals(want.map { it.name }, r.rows.map { it.name })
        assertEquals(0, r.errors.size)
        assertEquals(1, r.silentlySkipped)
        want.zip(r.rows).forEach { (p, row) ->
            val compass = p.name.substringBefore('-')
            // Hard rule 1: the bearing and direction type come through as the name states them.
            assertEquals(p.name, Ov2Samples.COMPASS.indexOf(compass) * 45f, row.bearing)
            assertEquals(p.name, if ("-R-" in p.name) DirectionType.BOTH else DirectionType.ONE_WAY, row.dirType)
            assertEquals(30, row.speed)
            assertEquals("GATSO", row.categoryCode)
            assertEquals(p.name.substringAfter(':').substringBefore('@'), row.extra["pgwId"])
        }
    }

    /** Optional: OV2_POI_ZIP=/path/to/a/real/free-POI.zip (never committed; DESIGN.md §6.4). */
    @Test
    fun realPoiZipIfProvided() {
        val path = System.getenv("OV2_POI_ZIP")
        assumeTrue("OV2_POI_ZIP not set", !path.isNullOrBlank())
        ZipEntries.read(File(path!!)).use { entries ->
            val files = entries.keys.filter { it.lowercase().endsWith(".ov2") }
            assertTrue(files.isNotEmpty())
            val summary = files.associateWith { name ->
                val bytes = entries.getValue(name)
                val r = Ov2Importer().parse(bytes)
                assertEquals(name, 0, r.errors.size)
                assertEquals(name, poiRecordsIn(bytes), r.rows.size)
                r.rows.size
            }
            println("OV2 POI files: ${files.size} total=${summary.values.sum()} $summary")
            assertTrue(summary.values.all { it > 0 })
        }
    }

    /** An independent count of the type 2 and 3 records, stepping skippers as 21 bytes and others by length. */
    private fun poiRecordsIn(b: ByteArray): Int {
        var i = 0
        var n = 0
        while (i + 5 <= b.size) {
            val t = b[i].toInt()
            val len = java.nio.ByteBuffer.wrap(b, i + 1, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (t == 2 || t == 3) n++
            i += if (t == 1) 21 else len
        }
        return n
    }
}

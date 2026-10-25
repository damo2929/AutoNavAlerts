// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zip handling (DESIGN.md §5.8, §5.2 "Reading the zip", "Never all in memory"; §1.3 Memory):
 * zip-slip names and folders skipped, nested zips opened only to the depth asked, every file kept
 * under its own key (hard rule 9: never merged), the 200 MB cap, PGW's type files picked by name
 * and header, and [LazyBytes] reading a value only when it is used. Made-up contents only.
 */
class ZipEntriesSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val igo = "X,Y,Type,Speed,DirType,Direction\n-1.5,53.8,1,30,1,90\n".toByteArray()

    private fun zipBytes(vararg files: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> files.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun zipFile(vararg files: Pair<String, ByteArray>): File = tmp.newFile().apply { writeBytes(zipBytes(*files)) }

    @Test
    fun anyNameWithDotDotAndFoldersAreSkipped() {
        val f = zipFile(
            "../up.txt" to igo, "a/../../b.txt" to igo, "..\\win.txt" to igo, "dir/" to ByteArray(0),
            "notes..txt" to igo, "ok.txt" to igo,
        )
        // Conservative: a name with ".." anywhere is dropped, even one that would stay inside.
        assertEquals(listOf("ok.txt"), ZipEntries.names(f))
    }

    @Test
    fun nestedZipsAreOpenedOnlyToTheDepthAsked() {
        val c = zipBytes("deepest.txt" to igo)
        val b = zipBytes("third.txt" to igo, "c.zip" to c)
        val a = zipBytes("second.txt" to igo, "b.zip" to b)
        val f = zipFile("first.txt" to igo, "a.zip" to a)
        assertEquals(listOf("first.txt", "second.txt", "third.txt", "c.zip"), ZipEntries.names(f))
        assertEquals(listOf("first.txt", "a.zip"), ZipEntries.names(f, maxDepth = 0))
        assertEquals(listOf("first.txt", "second.txt", "third.txt", "deepest.txt"), ZipEntries.names(f, maxDepth = 3))
        // The nested copies are temp files, deleted on close.
        val opened = ZipEntries.read(f)
        assertEquals(2, opened.temps.size)
        assertEquals(igo.toList(), opened.getValue("third.txt").toList())
        opened.close()
        assertTrue(opened.temps.none(File::exists))
    }

    @Test
    fun everyFileKeepsItsOwnKeyWhateverItsName() {
        val f = zipFile(
            "a/cams.ov2" to "1".toByteArray(), "cams.ov2" to "2".toByteArray(), "b/cams.ov2" to "3".toByteArray(),
            "/lead/x.ov2" to "4".toByteArray(), "x.ov2" to "5".toByteArray(),
        )
        ZipEntries.read(f).use { e ->
            // The second "cams.ov2" has no folder to tell it apart, so it gets a number.
            assertEquals(listOf("cams.ov2", "cams.ov2 (2)", "b/cams.ov2", "x.ov2", "x.ov2 (2)"), e.keys.toList())
            assertEquals(listOf("1", "2", "3", "4", "5"), e.keys.map { String(e.getValue(it)) })
        }
    }

    @Test
    fun aDownloadDeclaringMoreThanTheCapIsRefused() {
        // Zeros deflate to almost nothing, so the zip is small on disk but declares 210 MB.
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            val chunk = ByteArray(1 shl 20)
            for (name in listOf("a.txt", "b.txt")) {
                z.putNextEntry(ZipEntry(name)); repeat(105) { z.write(chunk) }; z.closeEntry()
            }
        }
        assertTrue(f.length() < 2_000_000)
        val e = runCatching { ZipEntries.read(f) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertEquals("Zip too large", e!!.message)
    }

    @Test
    fun pgwTypeFilesNeedThePgwNameAndAnIgoHeader() {
        val entries = linkedMapOf(
            "POCKETGPS_UK_GATSO.TXT" to igo,
            "pocketgps_uk_readme.txt" to "Read me first".toByteArray(),
            "pocketgps_uk_specs.csv" to igo,
            "Release.txt" to "Version 24.101".toByteArray(),
            "pocketgps_ie_mobile.txt" to igo,
            "pocketgps_uk_sc.txt" to ByteArray(0),
        )
        val types = ZipEntries.pgwTypeFiles(entries)
        assertEquals(listOf("gatso", "mobile"), types.keys.toList())
        assertEquals(igo.toList(), types.getValue("gatso").toList())
    }

    /** A lazy source whose reads are counted per key. */
    private class Counting(vararg values: Pair<String, String>) {
        val reads = HashMap<String, Int>()
        val map = LazyBytes(LinkedHashMap<String, () -> ByteArray>().apply {
            for ((k, v) in values) put(k) { reads[k] = (reads[k] ?: 0) + 1; v.toByteArray() }
        })
    }

    @Test
    fun selectKeepsOrderAndReadsOnlyWhatIsUsedEachTimeItIsUsed() {
        val src = Counting("a1" to "first", "b" to "bee", "a2" to "second", "skip" to "x")
        val sel = LazyBytes.select(src.map) { name -> name.firstOrNull()?.takeIf { it != 's' }?.toString() }
        // A later name with the same key replaces the earlier one's loader in its place.
        assertEquals(listOf("a", "b"), sel.keys.toList())
        assertEquals(2, sel.size)
        assertTrue(sel.containsKey("a"))
        assertFalse(sel.containsKey("skip"))
        assertTrue(src.reads.isEmpty())
        assertEquals("second", String(sel.getValue("a")))
        assertEquals("second", String(sel.getValue("a")))
        assertEquals(mapOf("a2" to 2), src.reads)
        // Walking the entries reads nothing until a value is asked for.
        assertEquals(listOf("a", "b"), sel.entries.map { it.key })
        assertEquals(mapOf("a2" to 2), src.reads)
        assertEquals("bee", String(sel.entries.last().value))
        assertEquals(1, src.reads["b"])
        assertEquals(null, sel["nope"])
    }
}

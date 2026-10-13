// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipEntriesTest {
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> files.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    /** Zips are read from a file on disk; the tests build theirs in memory. */
    private fun ZipEntries.read(bytes: ByteArray) =
        read(java.io.File.createTempFile("test", ".zip").apply { writeBytes(bytes); deleteOnExit() })

    @Test
    fun valuesAreReadWhenUsedAndNamesNeedNoInflating() {
        val f = java.io.File.createTempFile("test", ".zip").apply { writeBytes(zip("a.txt" to igo, "b.bmp" to ByteArray(10))); deleteOnExit() }
        assertEquals(listOf("a.txt", "b.bmp"), ZipEntries.names(f))
        ZipEntries.read(f).use { e ->
            assertEquals(igo.toList(), e["a.txt"]!!.toList())
            assertEquals(10, e.getValue("b.bmp").size)
            assertEquals(null, e["c.txt"])
        }
    }

    private val igo = "X,Y,Type,Speed,DirType,Direction\n-1.5,53.8,1,30,1,90\n".toByteArray()

    @Test
    fun readsAllEntriesInOrder() {
        val bytes = zip(
            "_Need_Help.htm" to "<html/>".toByteArray(),
            "Release.txt" to "Version 24.101 (07 Oct 26)".toByteArray(),
            "pocketgps_uk_sc.txt" to igo,
            "pocketgps_uk_pmobile.txt" to igo,
        )
        val entries = ZipEntries.read(bytes)
        assertEquals(listOf("_Need_Help.htm", "Release.txt", "pocketgps_uk_sc.txt", "pocketgps_uk_pmobile.txt"), entries.keys.toList())
    }

    @Test
    fun sameFileNameInTwoFoldersKeepsBoth() {
        val bytes = zip("UK/cameras.ov2" to "a".toByteArray(), "IE/cameras.ov2" to "b".toByteArray())
        val entries = ZipEntries.read(bytes)
        assertEquals(listOf("cameras.ov2", "IE/cameras.ov2"), entries.keys.toList())
        assertEquals("b", String(entries.getValue("IE/cameras.ov2")))
    }

    @Test
    fun opensNestedZipAndSkipsZipSlip() {
        val inner = zip("pocketgps_uk_sc.txt" to igo)
        val entries = ZipEntries.read(zip("cams.zip" to inner, "../evil.txt" to "x".toByteArray()))
        assertTrue("pocketgps_uk_sc.txt" in entries)
        assertTrue(entries.keys.none { it.contains("evil") })
    }

    @Test
    fun consolidatedByTypeFilesAreAllFound() {
        val entries = ZipEntries.read(zip(
            "_Need_Help.htm" to "<html/>".toByteArray(), "Licence.txt" to "x".toByteArray(),
            "pocketgps_uk_gatso.txt" to igo, "pocketgps_uk_mobile.txt" to igo, "pocketgps_uk_pmobile.txt" to igo,
            "pocketgps_uk_redlight.txt" to igo, "pocketgps_uk_specs.txt" to igo,
        ))
        assertEquals(listOf("gatso", "mobile", "pmobile", "redlight", "specs"), ZipEntries.pgwTypeFiles(entries).keys.toList())
    }

    /** Optional: PGW_ZIP=/path/to/pgpsw-speed-cams-….zip (your own download, never committed). */
    @Test
    fun realPgwZipIfProvided() {
        val path = System.getenv("PGW_ZIP")
        assumeTrue("PGW_ZIP not set", !path.isNullOrBlank())
        val entries = ZipEntries.read(File(path!!))
        println("PGW zip entries: ${entries.map { "${it.key} (${it.value.size})" }}")
        val types = ZipEntries.pgwTypeFiles(entries)
        println("PGW type files: ${types.keys}")
        assertTrue("camera files", types.isNotEmpty())
    }
}

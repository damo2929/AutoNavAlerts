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

    private val igo = "X,Y,Type,Speed,DirType,Direction\n-1.5,53.8,1,30,1,90\n".toByteArray()

    @Test
    fun readsAllEntriesAndPicksPgwFiles() {
        val bytes = zip(
            "_Need_Help.htm" to "<html/>".toByteArray(),
            "Release.txt" to "Version 24.101 (07 Oct 26)".toByteArray(),
            "pocketgps_uk_sc.txt" to igo,
            "pocketgps_uk_pmobile.txt" to igo,
        )
        val entries = ZipEntries.read(bytes)
        assertEquals(listOf("_Need_Help.htm", "Release.txt", "pocketgps_uk_sc.txt", "pocketgps_uk_pmobile.txt"), entries.keys.toList())
        val (main, pending) = ZipEntries.pgwFiles(entries)
        assertNotNull(main)
        assertNotNull(pending)
    }

    @Test
    fun matchesOtherRegionsAndCase() {
        val (main, pending) = ZipEntries.pgwFiles(mapOf("PocketGPS_EUR_SC.TXT" to igo, "pocketgps_eur_PMOBILE.txt" to igo))
        assertNotNull(main)
        assertNotNull(pending)
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

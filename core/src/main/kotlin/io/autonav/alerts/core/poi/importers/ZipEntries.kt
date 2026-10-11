// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import java.io.File
import java.util.zip.ZipFile

/**
 * Reads every file in a zip via its **central directory** (java.util.zip.ZipFile).
 *
 * Don't use ZipInputStream for POI downloads: PocketGPSWorld's zips make the streaming reader stop
 * after the first entry (it returns only `_Need_Help.htm`), while the central directory lists all
 * five files. Nested zips are opened up to [maxDepth] levels. Entries with ".." are ignored
 * (zip-slip), and the total size is capped.
 */
object ZipEntries {
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    /** File name (without folders) → contents, in archive order. */
    fun read(zip: File, maxDepth: Int = 2): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        read(zip, out, 0, maxDepth, longArrayOf(0))
        return out
    }

    private fun read(zip: File, out: MutableMap<String, ByteArray>, depth: Int, maxDepth: Int, total: LongArray) {
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory || e.name.contains("..")) continue
                val bytes = zf.getInputStream(e).use { it.readBytes() }
                total[0] += bytes.size
                check(total[0] <= MAX_TOTAL_BYTES) { "Zip too large" }
                val name = e.name.substringAfterLast('/').substringAfterLast('\\')
                if (name.lowercase().endsWith(".zip") && depth < maxDepth) {
                    val tmp = File.createTempFile("nested", ".zip")
                    try {
                        tmp.writeBytes(bytes)
                        read(tmp, out, depth + 1, maxDepth, total)
                    } finally {
                        tmp.delete()
                    }
                } else {
                    out[name] = bytes
                }
            }
        }
    }

    /** Same as [read] for zip bytes held in memory (e.g. a file picked by the user). */
    fun read(bytes: ByteArray, maxDepth: Int = 2): LinkedHashMap<String, ByteArray> {
        val tmp = File.createTempFile("import", ".zip")
        return try {
            tmp.writeBytes(bytes)
            read(tmp, maxDepth)
        } finally {
            tmp.delete()
        }
    }

    private val PGW_FILE = Regex("""^pocketgps_[a-z]+_([a-z0-9]+)\.txt$""", RegexOption.IGNORE_CASE)

    /**
     * PGW camera files keyed by type stem, e.g. "gatso", "mobile", "redlight", "specs", "pmobile"
     * (consolidated by type) or "sc" (single file). Licence/Release/help files are left out.
     */
    fun pgwTypeFiles(entries: Map<String, ByteArray>): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for ((name, bytes) in entries) {
            val stem = PGW_FILE.matchEntire(name)?.groupValues?.get(1)?.lowercase() ?: continue
            if (IgoTxtImporter().sniff(String(bytes, 0, minOf(200, bytes.size), Charsets.ISO_8859_1))) out[stem] = bytes
        }
        return out
    }

    /** Picks the PGW main and pending-mobile files by name pattern, ignoring case. */
    fun pgwFiles(entries: Map<String, ByteArray>): Pair<ByteArray?, ByteArray?> {
        val main = entries.entries.firstOrNull { it.key.lowercase().endsWith("_sc.txt") }?.value
            ?: entries.entries.firstOrNull { (k, v) ->
                k.lowercase().endsWith(".txt") && "pmobile" !in k.lowercase() &&
                    IgoTxtImporter().sniff(String(v, 0, minOf(200, v.size), Charsets.ISO_8859_1))
            }?.value
        val pending = entries.entries.firstOrNull { it.key.lowercase().let { k -> "pmobile" in k && k.endsWith(".txt") } }?.value
        return main to pending
    }
}

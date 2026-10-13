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

    /**
     * File name (without folders) → contents, in archive order. Nothing is read up front: each
     * file is read from the zip on disk when its value is used, and not kept, so a zip's icons,
     * readmes and other files never sit in memory together. Close it when done.
     */
    fun read(zip: File, maxDepth: Int = 2): Opened {
        val opened = Opened()
        try { index(zip, opened, 0, maxDepth, longArrayOf(0)) } catch (t: Throwable) { opened.close(); throw t }
        return opened
    }

    /** The open zip(s) behind [read]'s map. */
    class Opened internal constructor() : LazyBytes(LinkedHashMap()), java.io.Closeable {
        internal val zips = ArrayList<ZipFile>()
        internal val temps = ArrayList<File>()
        internal fun put(name: String, load: () -> ByteArray) { loaders[name] = load }
        override fun close() {
            zips.forEach { runCatching { it.close() } }
            temps.forEach { it.delete() }
        }
    }

    private fun index(zip: File, out: Opened, depth: Int, maxDepth: Int, total: LongArray) {
        val zf = ZipFile(zip).also { out.zips += it }
        val entries = zf.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.isDirectory || e.name.contains("..")) continue
            total[0] += e.size.coerceAtLeast(0)
            check(total[0] <= MAX_TOTAL_BYTES) { "Zip too large" }
            val name = e.name.substringAfterLast('/').substringAfterLast('\\')
            if (name.lowercase().endsWith(".zip") && depth < maxDepth) {
                // A nested zip is copied to a temp file (streamed) so its own directory can be read.
                val tmp = File.createTempFile("nested", ".zip").also { out.temps += it }
                zf.getInputStream(e).use { i -> tmp.outputStream().use { i.copyTo(it) } }
                index(tmp, out, depth + 1, maxDepth, total)
            } else {
                out.put(name) { zf.getInputStream(e).use { capped(it, e.size) } }
            }
        }
    }

    /** Reads an entry, refusing one that inflates past [MAX_TOTAL_BYTES] whatever its header says. */
    private fun capped(i: java.io.InputStream, declared: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(declared.coerceIn(0, 1L shl 20).toInt())
        val buf = ByteArray(1 shl 16)
        var n = 0L
        while (true) {
            val k = i.read(buf); if (k < 0) break
            n += k; check(n <= MAX_TOTAL_BYTES) { "Zip too large" }
            out.write(buf, 0, k)
        }
        return out.toByteArray()
    }

    /** Names of the files in a zip (and nested zips), from the directory only: nothing is inflated. */
    fun names(zip: File, maxDepth: Int = 2): List<String> = read(zip, maxDepth).use { it.keys.toList() }

    private val PGW_FILE = Regex("""^pocketgps_[a-z]+_([a-z0-9]+)\.txt$""", RegexOption.IGNORE_CASE)

    /**
     * PGW camera files keyed by type stem, e.g. "gatso", "mobile", "redlight", "specs", "pmobile"
     * (consolidated by type) or "sc" (single file). Licence/Release/help files are left out.
     */
    fun pgwTypeFiles(entries: Map<String, ByteArray>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, () -> ByteArray>()
        for (name in entries.keys) {
            val stem = PGW_FILE.matchEntire(name)?.groupValues?.get(1)?.lowercase() ?: continue
            val bytes = entries.getValue(name)
            if (IgoTxtImporter().sniff(String(bytes, 0, minOf(200, bytes.size), Charsets.ISO_8859_1))) out[stem] = { entries.getValue(name) }
        }
        return LazyBytes(out)
    }

}

/** A map whose values are read when used (each time) and not kept: see [ZipEntries.read]. */
open class LazyBytes(protected val loaders: LinkedHashMap<String, () -> ByteArray>) : AbstractMap<String, ByteArray>() {
    override val keys: Set<String> get() = loaders.keys
    override val size: Int get() = loaders.size
    override fun containsKey(key: String): Boolean = key in loaders
    override fun get(key: String): ByteArray? = loaders[key]?.invoke()
    override val entries: Set<Map.Entry<String, ByteArray>>
        get() = object : AbstractSet<Map.Entry<String, ByteArray>>() {
            override val size: Int get() = loaders.size
            override fun iterator(): Iterator<Map.Entry<String, ByteArray>> = loaders.entries.asSequence().map { (k, load) ->
                object : Map.Entry<String, ByteArray> {
                    override val key = k
                    override val value: ByteArray get() = load()
                }
            }.iterator()
        }
}

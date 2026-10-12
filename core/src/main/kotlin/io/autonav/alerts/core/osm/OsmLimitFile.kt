// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The compact on-phone file of OpenStreetMap limits (DESIGN.md §8.4a): a sorted tile index, then
 * each tile's roads as a limit, a point count, a first point in micro-degrees and 16-bit steps.
 */
object OsmLimitFile {
    private const val MAGIC = 0x414E534C // "ANSL"
    private const val VERSION = 1
    private const val MAX_STEP = Short.MAX_VALUE.toInt()

    /**
     * Collects roads by tile, then writes the file. Not thread-safe. Roads are spilled to [spill]
     * on disk as they arrive; only each tile's byte count is kept in memory, and [write] builds the
     * file in passes of at most [passBytes], so memory stays small however big the country.
     */
    class Writer(private val spill: File, private val passBytes: Int = 4 shl 20) : AutoCloseable {
        private val sizes = HashMap<Long, Int>()
        private val out = DataOutputStream(spill.outputStream().buffered(1 shl 16))
        private val run = ByteArrayOutputStream()
        var roads = 0L
            private set

        /** Adds one road (lon/lat pairs, as the shapefile gives them) with its limit. */
        fun add(mph: Int, xy: DoubleArray) {
            val n = xy.size / 2
            if (n < 2) return
            val lat = IntArray(n) { (xy[2 * it + 1] * 1e6).roundToInt() }
            val lon = IntArray(n) { (xy[2 * it] * 1e6).roundToInt() }
            // A step too long for 16 bits (rare: > ~3.6 km) is written as its own two-point road.
            fun big(i: Int) = kotlin.math.abs(lat[i + 1] - lat[i]) > MAX_STEP || kotlin.math.abs(lon[i + 1] - lon[i]) > MAX_STEP
            var start = 0
            while (start < n - 1) {
                if (big(start)) { writeRun(mph, lat, lon, start, start + 1, twoPointsWide = true); start++; continue }
                var end = start + 1
                while (end < n - 1 && !big(end) && end - start < 60_000) end++
                writeRun(mph, lat, lon, start, end, twoPointsWide = false)
                start = end
            }
        }

        private fun writeRun(mph: Int, lat: IntArray, lon: IntArray, from: Int, to: Int, twoPointsWide: Boolean) {
            // Every tile a point of the run is in gets a copy, so lookups near a tile edge find it.
            val keys = (from..to).mapTo(HashSet()) { key(lat[it], lon[it]) }
            run.reset()
            DataOutputStream(run).let { o ->
                o.writeByte(if (twoPointsWide) 0x80 or mph else mph)
                o.writeShort(to - from + 1)
                o.writeInt(lat[from]); o.writeInt(lon[from])
                if (twoPointsWide) { o.writeInt(lat[to]); o.writeInt(lon[to]) }
                else for (i in from + 1..to) { o.writeShort(lat[i] - lat[i - 1]); o.writeShort(lon[i] - lon[i - 1]) }
            }
            for (k in keys) {
                out.writeLong(k); out.writeInt(run.size()); run.writeTo(out)
                sizes[k] = (sizes[k] ?: 0) + run.size()
            }
            roads++
        }

        /** Writes the file (to [file], replacing it). [dataDate] is the extract's date, e.g. "2026-10-11". */
        fun write(file: File, dataDate: String) {
            out.close()
            val keys = sizes.keys.sorted()
            val dateBytes = dataDate.toByteArray(Charsets.US_ASCII)
            val headerLen = 4 + 4 + 8 + 2 + dateBytes.size + 4 + keys.size * 16
            val offsets = HashMap<Long, Long>(keys.size * 2)
            RandomAccessFile(file, "rw").use { f ->
                f.setLength(0)
                val head = ByteBuffer.allocate(headerLen).order(ByteOrder.BIG_ENDIAN)
                head.putInt(MAGIC).putInt(VERSION).putLong(roads).putShort(dateBytes.size.toShort()).put(dateBytes).putInt(keys.size)
                var off = headerLen.toLong()
                for (k in keys) {
                    val len = sizes.getValue(k)
                    head.putLong(k).putInt(off.toInt()).putInt(len)
                    offsets[k] = off
                    off += len
                }
                f.write(head.array())
                // Tiles in file order, a few MB at a time: each pass reads the spill once and
                // keeps only the records of that pass's tiles.
                var i = 0
                while (i < keys.size) {
                    var bytes = 0L
                    var j = i
                    while (j < keys.size && (j == i || bytes + sizes.getValue(keys[j]) <= passBytes)) { bytes += sizes.getValue(keys[j]); j++ }
                    val first = offsets.getValue(keys[i])
                    val buf = ByteArray(bytes.toInt())
                    val fill = HashMap<Long, Int>((j - i) * 2)
                    for (t in i until j) fill[keys[t]] = (offsets.getValue(keys[t]) - first).toInt()
                    java.io.DataInputStream(spill.inputStream().buffered(1 shl 16)).use { r ->
                        val rec = ByteArray(1 shl 16)
                        while (true) {
                            val k = try { r.readLong() } catch (e: java.io.EOFException) { break }
                            val len = r.readInt()
                            val at = fill[k]
                            if (at == null) { var left = len; while (left > 0) { val k2 = r.skipBytes(left); if (k2 <= 0) throw java.io.EOFException(); left -= k2 }; continue }
                            val dst = if (len <= rec.size) rec else ByteArray(len)
                            r.readFully(dst, 0, len)
                            System.arraycopy(dst, 0, buf, at, len)
                            fill[k] = at + len
                        }
                    }
                    f.seek(first)
                    f.write(buf)
                    i = j
                }
            }
            spill.delete()
        }

        override fun close() { runCatching { out.close() }; spill.delete() }

        private fun key(latE6: Int, lonE6: Int): Long = OsmSpeedLimits.tileKey(latE6 / 1e6, lonE6 / 1e6)
    }

    /** Reads tiles from a written file. Keeps the index in memory; the roads are read on demand. */
    class Reader(private val file: File) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        private val keys: LongArray
        private val offsets: IntArray
        private val lengths: IntArray
        val roads: Long
        val dataDate: String

        init {
            val magic = raf.readInt()
            require(magic == MAGIC && raf.readInt() == VERSION) { "Not a speed limits file" }
            roads = raf.readLong()
            val dl = raf.readShort().toInt()
            dataDate = ByteArray(dl).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
            val n = raf.readInt()
            val idx = ByteArray(n * 16).also { raf.readFully(it) }
            val b = ByteBuffer.wrap(idx)
            keys = LongArray(n); offsets = IntArray(n); lengths = IntArray(n)
            for (i in 0 until n) { keys[i] = b.long; offsets[i] = b.int; lengths[i] = b.int }
        }

        val tiles: Int get() = keys.size

        /** The key of the [i]th tile in the file (for sampling and tools). */
        fun tileKeyAt(i: Int): Long = keys[i]

        /** Whether the file has roads in this tile. */
        fun has(tile: Long): Boolean = keys.binarySearch(tile) >= 0

        /**
         * Calls [fn] with each straight piece of one tile's roads: ends in micro-degrees and the
         * limit in mph. Nothing is kept; the tile's bytes are read once.
         */
        fun forEachSegment(tile: Long, fn: (lat1: Int, lon1: Int, lat2: Int, lon2: Int, mph: Int) -> Unit) {
            val bytes = synchronized(this) {
                val i = keys.binarySearch(tile)
                if (i < 0) return
                ByteArray(lengths[i]).also { raf.seek(offsets[i].toLong()); raf.readFully(it) }
            }
            val b = ByteBuffer.wrap(bytes)
            while (b.hasRemaining()) {
                val flag = b.get().toInt() and 0xff
                val mph = flag and 0x7f
                val n = b.short.toInt() and 0xffff
                var lat = b.int; var lon = b.int
                if (flag and 0x80 != 0) { fn(lat, lon, b.int, b.int, mph); continue }
                for (k in 1 until n) {
                    val nl = lat + b.short; val nn = lon + b.short
                    fn(lat, lon, nl, nn, mph)
                    lat = nl; lon = nn
                }
            }
        }

        /** The road pieces of one tile, as segments (empty if the tile has none). Tests and tools. */
        fun segments(tile: Long): List<LimitSegment> {
            val out = ArrayList<LimitSegment>()
            forEachSegment(tile) { a, b, c, d, mph -> out += LimitSegment(a / 1e6, b / 1e6, c / 1e6, d / 1e6, mph.toDouble(), LimitSegment.BOTH) }
            return out
        }

        override fun close() = raf.close()
    }

}

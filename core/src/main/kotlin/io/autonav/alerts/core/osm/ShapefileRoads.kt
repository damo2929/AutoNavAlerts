// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Streaming readers for Geofabrik's roads shapefile (DESIGN.md §8.4a): the `.dbf` gives each
 * road's `maxspeed`, the `.shp` its line. Both are read front to back, never seeked, so they can
 * come straight from an inflating network stream.
 *
 * Stateless. The app's `SpeedLimitWorker` calls [readLimits] first (one byte per road to a temp
 * file), then [readRoads] with that file alongside the `.shp`, then [simplify] and
 * [NorthernIreland.keeps] per road, feeding [OsmLimitFile.Writer]; all on WorkManager's thread,
 * with the progress callbacks checking for Cancel. Nothing here holds more than one record.
 * Coordinates are lon/lat degrees as the file orders them; limits come out in whole mph.
 */
object ShapefileRoads {
    /** One byte per road record: its limit in whole mph, 0 if none. Small files and tests only. */
    fun readLimits(dbf: InputStream): ByteArray =
        java.io.ByteArrayOutputStream().also { readLimits(dbf, it) }.toByteArray()

    /**
     * Writes one byte per road record to [out] (its limit in whole mph, 0 if none), streaming, so
     * England's millions of roads never sit in memory. Returns the record count.
     */
    fun readLimits(dbf: InputStream, out: java.io.OutputStream, onProgress: (Long) -> Unit = {}): Int {
        val d = DataInputStream(dbf.buffered(1 shl 16))
        // dBASE header: record count at byte 4, header length at 8, record length at 10 (little-endian),
        // then 32-byte field descriptors (name in bytes 0–10, length at 16) ended by 0x0D.
        val head = ByteArray(32).also(d::readFully)
        val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.getInt(4)
        val headerLen = bb.getShort(8).toInt() and 0xffff
        val recordLen = bb.getShort(10).toInt() and 0xffff
        var pos = 32L
        var fieldOff = 1 // the deletion flag comes first
        var msOff = -1
        var msLen = 0
        while (true) {
            val f = ByteArray(32)
            f[0] = d.readByte(); pos++
            if (f[0].toInt() == 0x0D) break
            d.readFully(f, 1, 31); pos += 31
            val name = String(f, 0, 11, Charsets.US_ASCII).substringBefore('\u0000')
            val len = f[16].toInt() and 0xff
            if (name.equals("maxspeed", ignoreCase = true)) { msOff = fieldOff; msLen = len }
            fieldOff += len
        }
        require(msOff > 0) { "No maxspeed field in the roads .dbf" }
        d.skipFully(headerLen - pos)
        val rec = ByteArray(recordLen)
        for (i in 0 until count) {
            d.readFully(rec)
            // The field is numeric text, space padded; digits only, so a blank reads as 0 (no limit).
            var kmh = 0
            for (k in msOff until msOff + msLen) {
                val c = rec[k].toInt()
                if (c in '0'.code..'9'.code) kmh = kmh * 10 + (c - '0'.code)
            }
            out.write(mphFromKmh(kmh))
            if (i % 100_000 == 0) onProgress(i.toLong() * recordLen)
        }
        out.flush()
        return count
    }

    /** Geofabrik stores km/h converted from the mph tag; back to whole mph (48 → 30). 0 = none. */
    // 5–90 mph: outside that it isn't a road limit (§8.4a "Limits").
    fun mphFromKmh(kmh: Int): Int {
        if (kmh <= 0) return 0
        val mph = (kmh / 1.609344).roundToInt()
        return if (mph < 5 || mph > 90) 0 else mph
    }

    /**
     * Calls [onRoad] with each polyline part (lon/lat pairs, as in the file) of every record whose
     * limit in [limits] isn't 0. [onProgress] gets the bytes read so far.
     */
    fun readRoads(shp: InputStream, limits: ByteArray, onProgress: (Long) -> Unit = {}, onRoad: (mph: Int, xy: DoubleArray) -> Unit) =
        readRoads(shp, java.io.ByteArrayInputStream(limits), onProgress, onRoad)

    /** As above, with the limits read alongside from a stream (one byte per record, in order). */
    fun readRoads(shp: InputStream, limits: InputStream, onProgress: (Long) -> Unit = {}, onRoad: (mph: Int, xy: DoubleArray) -> Unit) {
        val lim = limits.buffered(1 shl 16)
        val d = DataInputStream(shp.buffered(1 shl 16))
        // Shapefile: a 100-byte file header, then records of an 8-byte big-endian header (number, content
        // length in 16-bit words) and little-endian content: shape type at 0 (3 = polyline), part count
        // at 36, point count at 40, part start indexes, then the points as lon/lat doubles.
        d.skipFully(100)
        var read = 100L
        var lastReport = 0L
        val header = ByteArray(8)
        while (true) {
            try { d.readFully(header) } catch (e: EOFException) { break }
            val contentBytes = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(4) * 2
            read += 8 + contentBytes
            val mph = lim.read().coerceAtLeast(0)
            if (mph == 0) { d.skipFully(contentBytes.toLong()); continue }
            val body = ByteArray(contentBytes).also(d::readFully)
            val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(0) != 3) continue // not a polyline
            val parts = b.getInt(36)
            val points = b.getInt(40)
            val starts = IntArray(parts + 1) { if (it == parts) points else b.getInt(44 + 4 * it) }
            val base = 44 + 4 * parts
            for (p in 0 until parts) {
                val n = starts[p + 1] - starts[p]
                if (n < 2) continue
                val xy = DoubleArray(n * 2) { k -> b.getDouble(base + 8 * (starts[p] * 2 + k)) }
                onRoad(mph, xy)
            }
            if (read - lastReport > (4 shl 20)) { lastReport = read; onProgress(read) }
        }
        onProgress(read)
    }

    /** Douglas-Peucker on lon/lat pairs, tolerance in metres. Keeps the ends. */
    // The worker passes 3 m (§8.4a "Processed as it streams"), which keeps bends and drops the rest.
    fun simplify(xy: DoubleArray, toleranceM: Double): DoubleArray {
        val n = xy.size / 2
        if (n < 3) return xy
        val kx = 111_320.0 * cos(Math.toRadians(xy[1]))
        val ky = 110_540.0
        val keep = BooleanArray(n).also { it[0] = true; it[n - 1] = true }
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to n - 1) }
        while (stack.isNotEmpty()) {
            val (a, z) = stack.removeLast()
            val ax = xy[2 * a] * kx; val ay = xy[2 * a + 1] * ky
            val zx = xy[2 * z] * kx; val zy = xy[2 * z + 1] * ky
            val dx = zx - ax; val dy = zy - ay
            val len = hypot(dx, dy).takeIf { it > 0 } ?: 1e-9
            var worst = 0.0
            var wi = -1
            for (i in a + 1 until z) {
                val px = xy[2 * i] * kx; val py = xy[2 * i + 1] * ky
                val dist = abs(dy * px - dx * py + zx * ay - zy * ax) / len
                if (dist > worst) { worst = dist; wi = i }
            }
            if (worst > toleranceM && wi > 0) { keep[wi] = true; stack.add(a to wi); stack.add(wi to z) }
        }
        val out = DoubleArray(keep.count { it } * 2)
        var k = 0
        for (i in 0 until n) if (keep[i]) { out[k++] = xy[2 * i]; out[k++] = xy[2 * i + 1] }
        return out
    }

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) { if (read() < 0) throw EOFException(); left-- } else left -= s
        }
    }
}

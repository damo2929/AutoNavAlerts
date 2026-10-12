// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.geo.Geo
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The road pieces of a few tiles around the car, in plain arrays (DESIGN.md §8.4a): ends in
 * micro-degrees, the limit, and a sorted index of 0.002° cells. Looking a fix up allocates
 * nothing; the tiles stay on disk until the car comes near them.
 */
class LimitIndex private constructor(
    /** The tiles this index was built from. */
    val tiles: Set<Long>,
    private val ends: IntArray, // lat1, lon1, lat2, lon2 per piece
    private val mph: ByteArray,
    private val cells: LongArray, // (cell << IDX_BITS) | piece, sorted
    private val wide: IntArray, // pieces spanning too many cells to index: always checked
) {
    val pieces: Int get() = mph.size

    /** As [OsmSpeedLimits.limitAt]: the nearest piece within 20 m running within 30° of the heading. */
    fun limitAt(lat: Double, lon: Double, headingDeg: Double?): Double? {
        val h = headingDeg ?: return null
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        var best = -1
        var bestD = OsmSpeedLimits.MATCH_M
        val row = cellRow(lat); val col = cellCol(lon)
        for (dr in -1..1) for (dc in -1..1) {
            val key = cellKey(row + dr, col + dc) shl IDX_BITS
            var i = lowerBound(key)
            while (i < cells.size && (cells[i] ushr IDX_BITS) == (key ushr IDX_BITS)) {
                val p = (cells[i] and IDX_MASK).toInt()
                val d = distanceM(p, lat, lon, kx)
                if (d <= bestD && headingFits(p, h)) { best = p; bestD = d }
                i++
            }
        }
        for (p in wide) {
            val d = distanceM(p, lat, lon, kx)
            if (d <= bestD && headingFits(p, h)) { best = p; bestD = d }
        }
        return if (best < 0) null else (mph[best].toInt() and 0xff).toDouble()
    }

    private fun lowerBound(key: Long): Int {
        var lo = 0; var hi = cells.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (cells[m] < key) lo = m + 1 else hi = m }
        return lo
    }

    private fun distanceM(p: Int, lat: Double, lon: Double, kx: Double): Double {
        val o = 4 * p
        val ax = (ends[o + 1] / 1e6 - lon) * kx; val ay = (ends[o] / 1e6 - lat) * KY
        val bx = (ends[o + 3] / 1e6 - lon) * kx; val by = (ends[o + 2] / 1e6 - lat) * KY
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        return sqrt(px * px + py * py)
    }

    private fun headingFits(p: Int, h: Double): Boolean {
        val o = 4 * p
        val b = Geo.bearingDeg(ends[o] / 1e6, ends[o + 1] / 1e6, ends[o + 2] / 1e6, ends[o + 3] / 1e6)
        return Geo.absAngleDiffDeg(b, h) <= OsmSpeedLimits.MATCH_DEG || Geo.absAngleDiffDeg(b + 180.0, h) <= OsmSpeedLimits.MATCH_DEG
    }

    companion object {
        val EMPTY = LimitIndex(emptySet(), IntArray(0), ByteArray(0), LongArray(0), IntArray(0))

        private const val CELL_DEG = 0.002
        private const val KY = 110_540.0
        private const val IDX_BITS = 26 // up to 67 million pieces
        private const val IDX_MASK = (1L shl IDX_BITS) - 1
        private const val MAX_CELLS = 64

        private fun cellRow(lat: Double) = floor(lat / CELL_DEG).toInt()
        private fun cellCol(lon: Double) = floor(lon / CELL_DEG).toInt()
        // Row and column offset to be positive: 17 + 18 bits, under the piece index.
        private fun cellKey(row: Int, col: Int): Long = ((row + 45_000).toLong() shl 18) or (col + 90_000).toLong()

        /** Builds the index for [tiles] from the file, reading each tile once. */
        fun build(reader: OsmLimitFile.Reader, tiles: Collection<Long>): LimitIndex {
            var ends = IntArray(4096)
            var mph = ByteArray(1024)
            var n = 0
            for (t in tiles) reader.forEachSegment(t) { a, b, c, d, m ->
                if (n == mph.size) { mph = mph.copyOf(n * 2); ends = ends.copyOf(n * 8) }
                ends[4 * n] = a; ends[4 * n + 1] = b; ends[4 * n + 2] = c; ends[4 * n + 3] = d
                mph[n] = m.toByte()
                n++
            }
            var cells = LongArray(n + n / 2)
            var c = 0
            val wide = ArrayList<Int>()
            for (p in 0 until n) {
                val o = 4 * p
                val r0 = cellRow(min(ends[o], ends[o + 2]) / 1e6); val r1 = cellRow(max(ends[o], ends[o + 2]) / 1e6)
                val c0 = cellCol(min(ends[o + 1], ends[o + 3]) / 1e6); val c1 = cellCol(max(ends[o + 1], ends[o + 3]) / 1e6)
                if ((r1 - r0 + 1L) * (c1 - c0 + 1L) > MAX_CELLS) { wide += p; continue }
                for (row in r0..r1) for (col in c0..c1) {
                    if (c == cells.size) cells = cells.copyOf(c * 2)
                    cells[c++] = (cellKey(row, col) shl IDX_BITS) or p.toLong()
                }
            }
            cells = cells.copyOf(c)
            cells.sort()
            return LimitIndex(tiles.toSet(), ends.copyOf(4 * n), mph.copyOf(n), cells, wide.toIntArray())
        }
    }
}

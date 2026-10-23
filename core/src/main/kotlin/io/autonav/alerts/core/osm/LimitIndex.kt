// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.geo.LatLngPoint
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

    /**
     * As [OsmSpeedLimits.limitAt]: the nearest piece within 20 m running within 30° of the heading.
     * With no heading (standing still) the nearest piece within 20 m either way (DESIGN.md §8.4).
     */
    fun limitAt(lat: Double, lon: Double, headingDeg: Double?): Double? {
        val best = pieceAt(lat, lon, headingDeg) ?: return null
        return mphOf(best).toDouble()
    }

    /** The piece [limitAt] would use (its index), or null. For the lookahead (DESIGN.md §8.4a). */
    fun pieceAt(lat: Double, lon: Double, headingDeg: Double?): Int? {
        val h = headingDeg
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        var best = -1
        var bestD = OsmSpeedLimits.MATCH_M
        forEachNear(lat, lon) { p ->
            val d = distanceM(p, lat, lon, kx)
            if (d <= bestD && (h == null || headingFits(p, h))) { best = p; bestD = d }
        }
        return if (best < 0) null else best
    }

    /** The limit of a piece, mph. */
    fun mphOf(piece: Int): Int = mph[piece].toInt() and 0xff

    /** The piece's first end (its drawing direction). */
    fun startOf(piece: Int): LatLngPoint = LatLngPoint(ends[4 * piece] / 1e6, ends[4 * piece + 1] / 1e6)

    /** The piece's end in its drawing direction when [forward], else its start. */
    fun endOf(piece: Int, forward: Boolean): LatLngPoint =
        if (forward) LatLngPoint(ends[4 * piece + 2] / 1e6, ends[4 * piece + 3] / 1e6) else startOf(piece)

    /** Bearing from the piece's start to its end, degrees. */
    fun bearingOf(piece: Int): Double {
        val o = 4 * piece
        return Geo.bearingDeg(ends[o] / 1e6, ends[o + 1] / 1e6, ends[o + 2] / 1e6, ends[o + 3] / 1e6)
    }

    fun lengthOf(piece: Int): Double {
        val o = 4 * piece
        return Geo.distanceM(ends[o] / 1e6, ends[o + 1] / 1e6, ends[o + 2] / 1e6, ends[o + 3] / 1e6)
    }

    /** A join from [joinFrom]: the next piece, whether it is walked in its drawn direction, and whether this was a fork. */
    class Join(val piece: Int, val forward: Boolean, val fork: Boolean)

    /** As [joinFrom], the piece only. */
    fun connectedFrom(lat: Double, lon: Double, headingDeg: Double, exclude: Int = -1): Int? =
        joinFrom(lat, lon, headingDeg, exclude)?.piece

    /**
     * The piece that continues the road from the point (lat, lon) in direction [headingDeg]: one
     * end within [JOIN_M] of the point and, leaving that end, running within [JOIN_DEG] of the
     * heading; the straightest wins (DESIGN.md §8.4a). [Join.fork] says another branch, walked the
     * same way round (along its drawing or against it), left within [FORK_MIN_DEG]–[FORK_DEG] of the
     * chosen one (a slip easing off, a split, a roundabout exit): either could be the road on. [exclude] is the piece we came along.
     */
    fun joinFrom(lat: Double, lon: Double, headingDeg: Double, exclude: Int = -1): Join? {
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        var best = -1
        var bestDiff = JOIN_DEG
        var bestOut = 0.0
        var bestForward = true
        // Every branch's leaving bearing and drawn direction, for the fork test (a node has a handful).
        val outs = ArrayList<Pair<Double, Boolean>>(4)
        forEachNear(lat, lon) { p ->
            if (p == exclude) return@forEachNear
            val o = 4 * p
            val dA = pointDistanceM(ends[o], ends[o + 1], lat, lon, kx)
            val dB = pointDistanceM(ends[o + 2], ends[o + 3], lat, lon, kx)
            val forward = when {
                dA <= JOIN_M && dA <= dB -> true
                dB <= JOIN_M -> false
                else -> return@forEachNear
            }
            val out = if (forward) bearingOf(p) else Geo.normalizeDeg(bearingOf(p) + 180.0)
            val diff = Geo.absAngleDiffDeg(out, headingDeg)
            if (diff >= JOIN_DEG) return@forEachNear
            outs += out to forward
            if (diff < bestDiff) { best = p; bestDiff = diff; bestOut = out; bestForward = forward }
        }
        if (best < 0) return null
        // Only branches walked the same way round as ours: on a dual carriageway the other
        // carriageway, walked backwards, isn't a road we could take.
        val fork = outs.any { (out, fwd) -> fwd == bestForward && Geo.absAngleDiffDeg(out, bestOut) in FORK_MIN_DEG..FORK_DEG }
        return Join(best, bestForward, fork)
    }

    /** Every indexed piece in the 3×3 cells around the point, then the wide ones. */
    private inline fun forEachNear(lat: Double, lon: Double, fn: (Int) -> Unit) {
        val row = cellRow(lat); val col = cellCol(lon)
        for (dr in -1..1) for (dc in -1..1) {
            val key = cellKey(row + dr, col + dc) shl IDX_BITS
            var i = lowerBound(key)
            while (i < cells.size && (cells[i] ushr IDX_BITS) == (key ushr IDX_BITS)) {
                fn((cells[i] and IDX_MASK).toInt())
                i++
            }
        }
        for (p in wide) fn(p)
    }

    private fun pointDistanceM(latE6: Int, lonE6: Int, lat: Double, lon: Double, kx: Double): Double {
        val x = (lonE6 / 1e6 - lon) * kx; val y = (latE6 / 1e6 - lat) * KY
        return sqrt(x * x + y * y)
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

        /** Two pieces join when an end of one is within this of the other's (DESIGN.md §8.4a). */
        const val JOIN_M = 40.0
        /** Two branches leaving this close in angle are a fork, either could be the road on (§8.4a). */
        const val FORK_DEG = 25.0
        /** Closer than this they're the same line drawn twice, not a fork. */
        const val FORK_MIN_DEG = 3.0
        /** Heading continuity across a join. */
        const val JOIN_DEG = 45.0

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

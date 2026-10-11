// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi

import kotlin.math.floor

/**
 * 0.01° grid cells (DESIGN.md §4.3). `cell = row * COLS + col` with
 * row = floor((lat + 90) / 0.01), col = floor((lon + 180) / 0.01).
 */
object Grid {
    const val CELL_DEG = 0.01
    const val ROWS = 18_000L
    const val COLS = 36_000L
    const val MAX_VIEWPORT_CELLS = 400

    fun row(lat: Double): Long = floor((lat + 90.0) / CELL_DEG).toLong().coerceIn(0, ROWS - 1)
    fun col(lon: Double): Long = floor((lon + 180.0) / CELL_DEG).toLong().mod(COLS)

    fun cellOf(lat: Double, lon: Double): Long = row(lat) * COLS + col(lon)

    fun rowOf(cell: Long): Long = cell / COLS
    fun colOf(cell: Long): Long = cell % COLS

    /** The (2r+1)×(2r+1) block of cells centred on [cell]; columns wrap at the antimeridian. */
    fun neighbourhood(cell: Long, radius: Int = 2): List<Long> {
        val r0 = rowOf(cell)
        val c0 = colOf(cell)
        val out = ArrayList<Long>((2 * radius + 1) * (2 * radius + 1))
        for (dr in -radius..radius) {
            val r = r0 + dr
            if (r < 0 || r >= ROWS) continue
            for (dc in -radius..radius) {
                out += r * COLS + (c0 + dc).mod(COLS)
            }
        }
        return out
    }

    /**
     * Cells covering a bounding box, or null if more than [MAX_VIEWPORT_CELLS] would be needed
     * (zoomed too far out to query). Handles boxes crossing the antimeridian (west > east).
     */
    fun cellsInBounds(south: Double, west: Double, north: Double, east: Double): List<Long>? {
        val rMin = row(minOf(south, north))
        val rMax = row(maxOf(south, north))
        val cMin = col(west)
        val cMax = col(east)
        val colCount = if (cMax >= cMin) cMax - cMin + 1 else (COLS - cMin) + cMax + 1
        val total = (rMax - rMin + 1) * colCount
        if (total > MAX_VIEWPORT_CELLS) return null
        val out = ArrayList<Long>(total.toInt())
        for (r in rMin..rMax) {
            var c = cMin
            repeat(colCount.toInt()) {
                out += r * COLS + c
                c = (c + 1).mod(COLS)
            }
        }
        return out
    }
}

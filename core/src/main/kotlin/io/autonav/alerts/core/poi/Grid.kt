// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi

import kotlin.math.floor

/**
 * 0.01° grid cells (DESIGN.md §4.3). `cell = row * COLS + col` with
 * row = floor((lat + 90) / 0.01), col = floor((lon + 180) / 0.01). A cell is about 1.11 km
 * north–south everywhere and about 0.69 km east–west at 52°N; the id fits a Long with no collisions.
 *
 * Stateless. The app's `PoiRepository` stamps `Poi.gridCell` on import and queries by cell lists;
 * `AlertCoordinator`'s cell cache keeps the block of cells around the car that covers
 * the engine's reach ([reachCells] over the 5 km zone gap); `CameraReportRepository` stamps
 * reports the same way. Degrees in.
 */
object Grid {
    const val CELL_DEG = 0.01
    /** 180° / 0.01° rows and 360° / 0.01° columns. */
    const val ROWS = 18_000L
    const val COLS = 36_000L

    /** Row from latitude, clamped at the poles; column from longitude, wrapping at the antimeridian. */
    fun row(lat: Double): Long = floor((lat + 90.0) / CELL_DEG).toLong().coerceIn(0, ROWS - 1)
    fun col(lon: Double): Long = floor((lon + 180.0) / CELL_DEG).toLong().mod(COLS)

    /** The cell id of a point (the `gridCell` column of §4.1). */
    fun cellOf(lat: Double, lon: Double): Long = row(lat) * COLS + col(lon)

    /** The row and column back out of a cell id. */
    fun rowOf(cell: Long): Long = cell / COLS
    fun colOf(cell: Long): Long = cell % COLS

    /** The (2r+1)×(2r+1) block of cells centred on [cell]; columns wrap at the antimeridian. */
    fun neighbourhood(cell: Long, radius: Int = 2): List<Long> = neighbourhood(cell, radius, radius)

    /** The (2·rows+1)×(2·cols+1) block of cells centred on [cell]; columns wrap at the antimeridian. */
    fun neighbourhood(cell: Long, rows: Int, cols: Int): List<Long> {
        val r0 = rowOf(cell)
        val c0 = colOf(cell)
        val out = ArrayList<Long>((2 * rows + 1) * (2 * cols + 1))
        for (dr in -rows..rows) {
            val r = r0 + dr
            if (r < 0 || r >= ROWS) continue
            for (dc in -cols..cols) {
                out += r * COLS + (c0 + dc).mod(COLS)
            }
        }
        return out
    }

    /**
     * Cells needed each way to reach [metres] from a point at [lat] (DESIGN.md §4.3): a cell is
     * 0.01° tall (≈ 1.11 km) and 0.01° × cos(lat) wide, so the column count grows towards the poles.
     */
    fun reachCells(lat: Double, metres: Double): Pair<Int, Int> {
        val cellNs = CELL_DEG * 111_320.0
        val cellEw = cellNs * kotlin.math.cos(Math.toRadians(lat.coerceIn(-85.0, 85.0)))
        return kotlin.math.ceil(metres / cellNs).toInt() to kotlin.math.ceil(metres / cellEw).toInt()
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0.01° grid of DESIGN.md §4.3: the cell id formula, rows clamped at the poles and columns
 * wrapping at the antimeridian, and the alert cache's reach (`reachCells` over the 5 km zone gap:
 * about ±5 cells north–south and ±8 east–west in the UK), widening towards the poles but capped.
 */
class GridMoreTest {
    @Test
    fun cellIdIsRowTimesColumnsPlusColumn() {
        // Mid-cell values, so no floating-point edge: row 14200, column 17899.
        assertEquals(14_200L * 36_000L + 17_899L, Grid.cellOf(52.005, -1.005))
        assertEquals(511_217_899L, Grid.cellOf(52.005, -1.005))
        assertEquals(0L, Grid.cellOf(-90.0, -180.0))
    }

    @Test
    fun rowsClampAtThePolesAndColumnsWrap() {
        assertEquals(17_999L, Grid.row(90.0))
        assertEquals(17_999L, Grid.row(95.0))
        assertEquals(0L, Grid.row(-90.0))
        assertEquals(0L, Grid.row(-91.0))
        assertEquals(Grid.col(-180.0), Grid.col(180.0))
        assertEquals(Grid.col(-170.005), Grid.col(189.995))
        assertEquals(35_999L, Grid.col(179.995))
    }

    @Test
    fun theUkReachIsFiveCellsByEight() {
        assertEquals(5 to 8, Grid.reachCells(53.0, 5_000.0))
        assertEquals(5 to 5, Grid.reachCells(0.0, 5_000.0))
        assertEquals(3 to 5, Grid.reachCells(53.0, 3_000.0))
        assertEquals(0 to 0, Grid.reachCells(53.0, 0.0))
        // The cache block that reach gives: 11 × 17 cells.
        val (rows, cols) = Grid.reachCells(53.0, 5_000.0)
        assertEquals(11 * 17, Grid.neighbourhood(Grid.cellOf(53.005, -1.005), rows, cols).size)
    }

    @Test
    fun columnsGrowTowardsThePolesUntilTheCap() {
        val (_, at60) = Grid.reachCells(60.0, 5_000.0)
        assertEquals(9, at60)
        // Beyond 85° the latitude is held at 85°, so the block stays finite at the pole.
        assertEquals(Grid.reachCells(85.0, 5_000.0), Grid.reachCells(89.99, 5_000.0))
        assertEquals(Grid.reachCells(85.0, 5_000.0), Grid.reachCells(-90.0, 5_000.0))
        assertEquals(52, Grid.reachCells(85.0, 5_000.0).second)
    }

    @Test
    fun neighbourhoodsWrapEastAndWestAndClipAtTheSouthPole() {
        val west = Grid.neighbourhood(Grid.cellOf(10.005, -179.995), 1)
        val cols = west.map(Grid::colOf).toSet()
        assertEquals(setOf(35_999L, 0L, 1L), cols)
        assertEquals(9, west.size)
        assertEquals(15, Grid.neighbourhood(Grid.cellOf(-89.999, 0.0)).size)
        assertEquals(listOf(Grid.cellOf(53.0, -1.0)), Grid.neighbourhood(Grid.cellOf(53.0, -1.0), 0))
        // Row by row, west to east, the centre in the middle.
        val n = Grid.neighbourhood(Grid.cellOf(53.005, -1.005), 1, 2)
        assertEquals(15, n.size)
        assertEquals(Grid.cellOf(53.005, -1.005), n[7])
        assertTrue(n.zipWithNext().take(4).all { (a, b) -> b == a + 1 })
    }
}

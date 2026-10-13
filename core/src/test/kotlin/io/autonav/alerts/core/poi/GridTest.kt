// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridTest {
    @Test
    fun cellRoundTrips() {
        val cell = Grid.cellOf(53.8, -1.55)
        assertEquals(Grid.row(53.8), Grid.rowOf(cell))
        assertEquals(Grid.col(-1.55), Grid.colOf(cell))
    }

    @Test
    fun nearbyPointsShareCellAndDistantOnesDoNot() {
        assertEquals(Grid.cellOf(53.801, -1.551), Grid.cellOf(53.809, -1.559))
        assertNotEquals(Grid.cellOf(53.801, -1.551), Grid.cellOf(53.811, -1.551))
    }

    @Test
    fun neighbourhoodIs5x5AndWrapsAtAntimeridian() {
        assertEquals(25, Grid.neighbourhood(Grid.cellOf(0.0, 0.0)).size)
        val edge = Grid.neighbourhood(Grid.cellOf(10.0, 179.995))
        assertEquals(25, edge.size)
        assertTrue(edge.any { Grid.colOf(it) < 5 })
    }

    @Test
    fun nearPolesNeighbourhoodIsClipped() {
        assertEquals(15, Grid.neighbourhood(Grid.cellOf(89.999, 0.0)).size)
    }
}

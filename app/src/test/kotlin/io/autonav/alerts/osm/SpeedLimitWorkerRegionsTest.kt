// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.osm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN.md §8.4a "Source": the four Geofabrik extracts the UK speed-limit download is built from (England last,
 * as the largest; Northern Ireland from the Ireland file kept to its border) and the data date read from each
 * extract's dated file name (the newest is shown on the Settings card).
 */
class SpeedLimitWorkerRegionsTest {
    @Test fun fourRegionsWithEnglandLast() {
        assertEquals(listOf("Wales", "Scotland", "Northern Ireland", "England"), SpeedLimitWorker.REGIONS.map { it.name })
        val base = "https://download.geofabrik.de/europe/united-kingdom/"
        assertEquals(base + "wales-latest-free.shp.zip", SpeedLimitWorker.REGIONS[0].url)
        assertEquals(base + "scotland-latest-free.shp.zip", SpeedLimitWorker.REGIONS[1].url)
        assertEquals("https://download.geofabrik.de/europe/ireland-and-northern-ireland-latest-free.shp.zip", SpeedLimitWorker.REGIONS[2].url)
        assertEquals(base + "england-latest-free.shp.zip", SpeedLimitWorker.REGIONS[3].url)
        // All over HTTPS from Geofabrik only (one bulk download, never live lookups, §8.4).
        assertTrue(SpeedLimitWorker.REGIONS.all { it.url.startsWith("https://download.geofabrik.de/") })
    }

    @Test fun onlyTheIrelandFileIsFiltered() {
        // A road in the Republic (Dublin), as lon/lat pairs, with a UK-looking limit.
        val dublin = doubleArrayOf(-6.27, 53.34, -6.26, 53.35, -6.25, 53.36)
        val belfast = doubleArrayOf(-5.94, 54.59, -5.93, 54.60, -5.92, 54.61)
        val ni = SpeedLimitWorker.REGIONS.single { it.name == "Northern Ireland" }
        assertFalse(ni.keeps(30, dublin))
        assertTrue(ni.keeps(30, belfast))
        // A km/h limit that isn't a multiple of 5 mph is the Republic's, even inside the border.
        assertFalse(ni.keeps(31, belfast))
        // Great Britain's files keep every road they hold.
        for (r in SpeedLimitWorker.REGIONS - ni) assertTrue(r.name, r.keeps(31, dublin))
    }

    @Test fun dataDateFromTheDatedFileName() {
        assertEquals("2026-10-11", SpeedLimitWorker.dateOf("https://download.geofabrik.de/europe/united-kingdom/england-261011-free.shp.zip"))
        assertEquals("2025-01-02", SpeedLimitWorker.dateOf("https://download.geofabrik.de/europe/ireland-and-northern-ireland-250102-free.shp.zip"))
        // The "latest" link (no redirect followed) has no date.
        assertEquals("", SpeedLimitWorker.dateOf("https://download.geofabrik.de/europe/united-kingdom/wales-latest-free.shp.zip"))
        assertEquals("", SpeedLimitWorker.dateOf("https://example.org/england-2610-free.shp.zip"))
    }

    @Test fun datesCompareAsTextSoTheNewestWins() {
        // The worker keeps maxOf over the four extracts' dates; "" (undated) never wins.
        val dates = listOf("england-261011", "wales-261009", "scotland-261012", "x-latest").map { SpeedLimitWorker.dateOf("https://g/$it-free.shp.zip") }
        assertEquals("2026-10-12", dates.fold("") { a, b -> maxOf(a, b) })
    }
}

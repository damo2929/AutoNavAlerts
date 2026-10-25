// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

/**
 * Northern Ireland's border (DESIGN.md §8.4a), for keeping only its roads from Geofabrik's
 * "Ireland and Northern Ireland" file. From OpenStreetMap relation 156393 (coastal waters
 * included), simplified to 50 m; © OpenStreetMap contributors, ODbL.
 *
 * Built into the app; fetched once when the code was written and never looked up at run time.
 * Stateless after the ring is decoded. The app's `SpeedLimitWorker` calls [keeps] for every road
 * of the Ireland file on WorkManager's thread. Degrees in.
 */
object NorthernIreland {
    /**
     * Lon/lat pairs in 1e-4 degrees, "lon,lat lon,lat …", one closed ring, read from the
     * `ni_ring.txt` resource beside this class (about 1,500 points, 1,478 as fetched).
     */
    private val lon: DoubleArray
    private val lat: DoubleArray
    // Bounding box of the ring: a quick reject before the ray test, since most of the file is the Republic.
    private const val MIN_LAT = 53.87; private const val MAX_LAT = 55.45
    private const val MIN_LON = -8.18; private const val MAX_LON = -5.07

    init {
        val pts = javaClass.getResourceAsStream("ni_ring.txt")!!.bufferedReader().use { it.readText() }.trim().split(Regex("\\s+"))
        lon = DoubleArray(pts.size) { pts[it].substringBefore(',').toInt() / 1e4 }
        lat = DoubleArray(pts.size) { pts[it].substringAfter(',').toInt() / 1e4 }
    }

    /** True if the point is inside Northern Ireland (even-odd ray test). */
    fun contains(latitude: Double, longitude: Double): Boolean {
        if (latitude !in MIN_LAT..MAX_LAT || longitude !in MIN_LON..MAX_LON) return false
        var inside = false
        var j = lon.size - 1
        for (i in lon.indices) {
            if ((lat[i] > latitude) != (lat[j] > latitude) &&
                longitude < (lon[j] - lon[i]) * (latitude - lat[i]) / (lat[j] - lat[i]) + lon[i]
            ) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Whether a road from the Ireland file belongs to Northern Ireland: its middle point is inside
     * the border and its limit is a UK one (a whole multiple of 5 mph).
     */
    // The 5 mph multiple test (§8.4a) drops a stray Republic road (km/h limits) that the 50 m
    // simplification lets in at the border.
    fun keeps(mph: Int, xy: DoubleArray): Boolean {
        if (mph % 5 != 0) return false
        val m = (xy.size / 2) / 2
        return contains(xy[2 * m + 1], xy[2 * m])
    }
}

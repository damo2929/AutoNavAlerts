// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Optional (DESIGN.md §8.4a): set GEOFABRIK_ROADS=<dir> holding a Geofabrik
 * gis_osm_roads_free_1.dbf and .shp (e.g. Wales) to build the limits file from real data.
 */
class RealGeofabrikTest {
    @Test
    fun buildsTheLimitsFileFromARealExtract() {
        val dir = System.getenv("GEOFABRIK_ROADS")?.let(::File)
        assumeTrue(dir != null && File(dir, "gis_osm_roads_free_1.shp").exists())
        val t0 = System.currentTimeMillis()
        val limits = File(dir, "gis_osm_roads_free_1.dbf").inputStream().use { ShapefileRoads.readLimits(it) }
        val w = OsmLimitFile.Writer(File(dir, "osm_limits.spill"))
        File(dir, "gis_osm_roads_free_1.shp").inputStream().use { s ->
            ShapefileRoads.readRoads(s, limits) { mph, xy -> w.add(mph, ShapefileRoads.simplify(xy, 3.0)) }
        }
        val out = File(dir, "osm_limits.bin")
        w.write(out, "test")
        OsmLimitFile.Reader(out).use { r ->
            println("roads with a limit ${limits.count { it.toInt() != 0 }}, written ${r.roads}, tiles ${r.tiles}, ${out.length() / 1000} kB, ${System.currentTimeMillis() - t0} ms")
            // Cardiff, A4161 Newport Road near the centre: expect a limit nearby.
            val segs = OsmSpeedLimits.around(51.4860, -3.1600).flatMap(r::segments)
            println("segments around Cardiff: ${segs.size}, limits ${segs.map { it.mph }.groupingBy { it }.eachCount()}")
        }
    }
}

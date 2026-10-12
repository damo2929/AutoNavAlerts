// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShapefileRoadsTest {
    /** A minimal roads .dbf: fields osm_id (C10) and maxspeed (N3). */
    private fun dbf(speeds: List<Int>): ByteArray {
        val fields = listOf("osm_id" to 10, "maxspeed" to 3)
        val recLen = 1 + fields.sumOf { it.second }
        val headerLen = 32 + 32 * fields.size + 1
        val b = ByteBuffer.allocate(headerLen + recLen * speeds.size + 1).order(ByteOrder.LITTLE_ENDIAN)
        b.put(3).put(126).put(10).put(12).putInt(speeds.size).putShort(headerLen.toShort()).putShort(recLen.toShort())
        b.position(32)
        for ((name, len) in fields) {
            val f = ByteArray(32); name.toByteArray().copyInto(f); f[11] = if (name == "maxspeed") 'N'.code.toByte() else 'C'.code.toByte(); f[16] = len.toByte()
            b.put(f)
        }
        b.put(0x0D)
        speeds.forEachIndexed { i, s -> b.put(' '.code.toByte()); b.put("%-10d".format(i).toByteArray()); b.put("%3d".format(s).toByteArray()) }
        b.put(0x1A)
        return b.array()
    }

    /** A minimal .shp of single-part polylines (lon/lat pairs). */
    private fun shp(lines: List<DoubleArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(100))
        lines.forEachIndexed { i, xy ->
            val n = xy.size / 2
            val content = ByteBuffer.allocate(44 + 4 + 16 * n).order(ByteOrder.LITTLE_ENDIAN)
            content.putInt(3); repeat(4) { content.putDouble(0.0) }; content.putInt(1).putInt(n).putInt(0)
            xy.forEach { content.putDouble(it) }
            out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(i + 1).putInt(content.capacity() / 2).array())
            out.write(content.array())
        }
        return out.toByteArray()
    }

    @Test
    fun readsLimitsInMphFromKmh() {
        val limits = ShapefileRoads.readLimits(ByteArrayInputStream(dbf(listOf(48, 0, 32, 112, 3))))
        assertArrayEquals(byteArrayOf(30, 0, 20, 70, 0), limits)
    }

    @Test
    fun readsOnlyRoadsWithALimit() {
        val lines = listOf(doubleArrayOf(-1.0, 53.0, -1.0, 53.001), doubleArrayOf(-1.1, 53.0, -1.1, 53.001), doubleArrayOf(-1.2, 53.0, -1.2, 53.001))
        val got = ArrayList<Pair<Int, DoubleArray>>()
        ShapefileRoads.readRoads(ByteArrayInputStream(shp(lines)), byteArrayOf(30, 0, 40)) { mph, xy -> got += mph to xy }
        assertEquals(listOf(30, 40), got.map { it.first })
        assertArrayEquals(lines[2], got[1].second, 0.0)
    }

    @Test
    fun simplifyDropsPointsOnAStraightLine() {
        val xy = DoubleArray(20) { if (it % 2 == 0) -1.0 else 53.0 + it * 0.0001 }
        val s = ShapefileRoads.simplify(xy, 3.0)
        assertEquals(4, s.size)
    }

    @Test
    fun fileRoundTripsAndFindsTheLimit() {
        // A tiny pass size, so the write takes several passes over the spill file.
        val w = OsmLimitFile.Writer(File.createTempFile("limits", ".spill"), passBytes = 16)
        w.add(30, doubleArrayOf(-1.4634, 53.3700, -1.4634, 53.3800)) // north-south, 30
        w.add(20, doubleArrayOf(-1.4700, 53.3750, -1.4600, 53.3750)) // east-west, 20
        w.add(70, doubleArrayOf(-1.0, 53.0, -1.0, 53.2))              // a 22 km step: stored wide
        val f = File.createTempFile("limits", ".bin").apply { deleteOnExit() }
        w.write(f, "2026-10-11")
        OsmLimitFile.Reader(f).use { r ->
            assertEquals("2026-10-11", r.dataDate)
            assertEquals(3L, r.roads)
            val segs = OsmSpeedLimits.around(53.3750, -1.4634).flatMap(r::segments)
            assertEquals(30.0, OsmSpeedLimits.limitAt(segs, 53.3760, -1.46341, 0.0)!!, 0.01)
            assertEquals(20.0, OsmSpeedLimits.limitAt(segs, 53.37501, -1.4650, 90.0)!!, 0.01)
            val far = r.segments(OsmSpeedLimits.tileKey(53.05, -1.0))
            assertTrue(far.any { it.mph == 70.0 && it.lat2 == 53.2 })

            // The compact index gives the same answers, from just the tile the car is in.
            val idx = LimitIndex.build(r, listOf(OsmSpeedLimits.tileKey(53.3750, -1.4634)))
            assertEquals(30.0, idx.limitAt(53.3760, -1.46341, 0.0)!!, 0.01)
            assertEquals(30.0, idx.limitAt(53.3760, -1.46341, 180.0)!!, 0.01)
            assertEquals(20.0, idx.limitAt(53.37501, -1.4650, 90.0)!!, 0.01)
            assertEquals(null, idx.limitAt(53.3760, -1.46341, 45.0))  // crossing, not along
            assertEquals(null, idx.limitAt(53.3760, -1.4600, 0.0))    // 230 m away
            // Standing still (no heading): the nearest road piece.
            assertEquals(30.0, idx.limitAt(53.3760, -1.46341, null)!!, 0.01)
        }
    }

    @Test
    fun limitsStreamAlongsideTheShapes() {
        val out = java.io.ByteArrayOutputStream()
        val n = ShapefileRoads.readLimits(ByteArrayInputStream(dbf(listOf(48, 0, 32))), out)
        assertEquals(3, n)
        assertArrayEquals(byteArrayOf(30, 0, 20), out.toByteArray())
    }
}

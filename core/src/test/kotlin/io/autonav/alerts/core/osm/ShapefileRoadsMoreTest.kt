// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import io.autonav.alerts.core.geo.Geo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Geofabrik roads readers of DESIGN.md §8.4a "Processed as it streams" and "Limits": km/h
 * back to whole mph inside 5–90, the `maxspeed` field found wherever it sits, multi-part lines
 * split, non-lines and unlimited roads skipped without losing step, and the 3 m simplification.
 */
class ShapefileRoadsMoreTest {
    /** A .dbf with the given (name, length) character fields and one row of values per record. */
    private fun dbf(fields: List<Pair<String, Int>>, rows: List<List<String>>): ByteArray {
        val recLen = 1 + fields.sumOf { it.second }
        val headerLen = 32 + 32 * fields.size + 1
        val b = ByteBuffer.allocate(headerLen + recLen * rows.size + 1).order(ByteOrder.LITTLE_ENDIAN)
        b.put(3).put(126).put(10).put(25).putInt(rows.size).putShort(headerLen.toShort()).putShort(recLen.toShort())
        b.position(32)
        for ((name, len) in fields) {
            val f = ByteArray(32); name.toByteArray().copyInto(f); f[11] = 'C'.code.toByte(); f[16] = len.toByte()
            b.put(f)
        }
        b.put(0x0D)
        for (row in rows) {
            b.put(' '.code.toByte())
            fields.forEachIndexed { i, (_, len) -> b.put(row[i].padStart(len).take(len).toByteArray()) }
        }
        b.put(0x1A)
        return b.array()
    }

    /** One .shp record: [type], then the parts (lon/lat pairs each). */
    private fun record(n: Int, type: Int, parts: List<DoubleArray>): ByteArray {
        val points = parts.sumOf { it.size / 2 }
        val content = ByteBuffer.allocate(44 + 4 * parts.size + 16 * points).order(ByteOrder.LITTLE_ENDIAN)
        content.putInt(type); repeat(4) { content.putDouble(0.0) }; content.putInt(parts.size).putInt(points)
        var start = 0
        for (p in parts) { content.putInt(start); start += p.size / 2 }
        parts.forEach { p -> p.forEach { content.putDouble(it) } }
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(n).putInt(content.capacity() / 2).array() + content.array()
    }

    private fun shp(vararg records: ByteArray) = ByteArrayOutputStream().apply { write(ByteArray(100)); records.forEach(::write) }.toByteArray()

    private fun roads(shp: ByteArray, limits: ByteArray) = ArrayList<Pair<Int, DoubleArray>>().also { out ->
        ShapefileRoads.readRoads(ByteArrayInputStream(shp), limits) { mph, xy -> out += mph to xy }
    }

    @Test
    fun kmhRoundsBackToWholeMphInsideTheRoadWindow() {
        // The design's examples, then the edges of 5..90 mph.
        assertEquals(listOf(30, 20, 60, 70), listOf(48, 32, 96, 112).map(ShapefileRoads::mphFromKmh))
        assertEquals(listOf(40, 50, 70), listOf(64, 80, 113).map(ShapefileRoads::mphFromKmh))
        assertEquals(5, ShapefileRoads.mphFromKmh(8))   // 4.97 mph
        assertEquals(0, ShapefileRoads.mphFromKmh(7))   // 4.35 mph: not a road limit
        assertEquals(90, ShapefileRoads.mphFromKmh(145)) // 90.1 mph
        assertEquals(0, ShapefileRoads.mphFromKmh(146))  // 90.7 → 91 mph
        assertEquals(0, ShapefileRoads.mphFromKmh(0))
        assertEquals(0, ShapefileRoads.mphFromKmh(-48))
        // Every whole mph sign value the UK uses survives the shapefile's km/h and back.
        for (mph in listOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 70)) {
            assertEquals(mph, ShapefileRoads.mphFromKmh(Math.round(mph * Geo.KMH_PER_MPH).toInt()))
        }
    }

    @Test
    fun maxspeedIsFoundAmongOtherFieldsInAnyCase() {
        val fields = listOf("osm_id" to 10, "MAXSPEED" to 3, "name" to 12)
        val bytes = dbf(fields, listOf(listOf("1", "48", "High St"), listOf("2", "", "Lane"), listOf("3", "112", "M1")))
        assertArrayEquals(byteArrayOf(30, 0, 70), ShapefileRoads.readLimits(ByteArrayInputStream(bytes)))
    }

    @Test
    fun aDbfWithoutMaxspeedIsRefused() {
        val bytes = dbf(listOf("osm_id" to 10, "name" to 12), listOf(listOf("1", "x")))
        val e = runCatching { ShapefileRoads.readLimits(ByteArrayInputStream(bytes)) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun progressStartsAtTheFirstRecord() {
        val seen = ArrayList<Long>()
        val n = ShapefileRoads.readLimits(ByteArrayInputStream(dbf(listOf("maxspeed" to 3), listOf(listOf("48")))), ByteArrayOutputStream(), seen::add)
        assertEquals(1, n)
        assertEquals(listOf(0L), seen)
    }

    @Test
    fun eachPartOfAMultiPartLineIsItsOwnRoad() {
        val a = doubleArrayOf(-1.0, 53.0, -1.0, 53.001, -1.0, 53.002)
        val b = doubleArrayOf(-1.1, 53.0, -1.1, 53.001)
        val single = doubleArrayOf(-1.2, 53.0) // one point: not a line
        val got = roads(shp(record(1, 3, listOf(a, single, b))), byteArrayOf(40))
        assertEquals(listOf(40, 40), got.map { it.first })
        assertArrayEquals(a, got[0].second, 0.0)
        assertArrayEquals(b, got[1].second, 0.0)
    }

    @Test
    fun skippedRecordsKeepTheLimitsInStep() {
        val line = { lon: Double -> doubleArrayOf(lon, 53.0, lon, 53.001) }
        val bytes = shp(
            record(1, 3, listOf(line(-1.0))), // no limit: skipped unread
            record(2, 5, listOf(line(-1.1))), // a polygon: read, not a line
            record(3, 3, listOf(line(-1.2))),
            record(4, 3, listOf(line(-1.3))), // the limits run out: no limit
        )
        val got = roads(bytes, byteArrayOf(0, 30, 50))
        assertEquals(1, got.size)
        assertEquals(50, got[0].first)
        assertEquals(-1.2, got[0].second[0], 0.0)
    }

    @Test
    fun theLastProgressIsTheWholeFile() {
        val bytes = shp(record(1, 3, listOf(doubleArrayOf(-1.0, 53.0, -1.0, 53.001))), record(2, 3, listOf(doubleArrayOf(-1.1, 53.0, -1.1, 53.001))))
        val seen = ArrayList<Long>()
        ShapefileRoads.readRoads(ByteArrayInputStream(bytes), byteArrayOf(30, 0), onProgress = { seen += it }) { _, _ -> }
        assertEquals(bytes.size.toLong(), seen.last())
    }

    /** Lon/lat pairs from metres east/north of 53°N, 1°W. */
    private fun en(vararg m: Double): DoubleArray = DoubleArray(m.size) { i ->
        if (i % 2 == 0) -1.0 + m[i] / Geo.metresPerDegLon(53.0) else 53.0 + m[i] / Geo.METRES_PER_DEG_LAT
    }

    @Test
    fun simplifyKeepsBendsAndEndsAndDropsWobblesUnderTheTolerance() {
        // Two points, or one, come back as they are.
        val two = en(0.0, 0.0, 0.0, 100.0)
        assertTrue(ShapefileRoads.simplify(two, 3.0) === two)
        // A 2 m wobble half-way along 200 m goes; a 5 m one stays.
        assertEquals(4, ShapefileRoads.simplify(en(0.0, 0.0, 2.0, 100.0, 0.0, 200.0), 3.0).size)
        assertEquals(6, ShapefileRoads.simplify(en(0.0, 0.0, 5.0, 100.0, 0.0, 200.0), 3.0).size)
        // An L-shaped road keeps its corner and drops the points along each arm.
        val l = en(0.0, 0.0, 0.0, 50.0, 0.0, 100.0, 50.0, 100.0, 100.0, 100.0)
        val s = ShapefileRoads.simplify(l, 3.0)
        assertArrayEquals(doubleArrayOf(l[0], l[1], l[4], l[5], l[8], l[9]), s, 0.0)
        // A tolerance of zero keeps every point off the straight line.
        assertEquals(l.size, ShapefileRoads.simplify(en(0.0, 0.0, 1.0, 50.0, 0.0, 100.0, 1.0, 150.0, 0.0, 200.0), 0.0).size)
    }
}

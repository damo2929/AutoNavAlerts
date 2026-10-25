// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.osm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.File

/**
 * The compact limit file of DESIGN.md §8.4a "File" and "Low memory": the 16-bit step split, a
 * road copied into every tile it touches, a build in passes that gives the same bytes whatever
 * the pass size, and a reader that refuses anything that isn't the file. Made-up roads only.
 */
class OsmLimitFileWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun build(passBytes: Int = 4 shl 20, date: String = "2026-10-11", roads: OsmLimitFile.Writer.() -> Unit): File {
        val w = OsmLimitFile.Writer(tmp.newFile(), passBytes)
        w.roads()
        return tmp.newFile().also { w.write(it, date) }
    }

    /** Every segment of every tile, as (lat1, lon1, lat2, lon2, mph) in micro-degrees. */
    private fun OsmLimitFile.Reader.all(): List<List<Int>> = (0 until tiles).flatMap { i ->
        val out = ArrayList<List<Int>>()
        forEachSegment(tileKeyAt(i)) { a, b, c, d, mph -> out += listOf(a, b, c, d, mph) }
        out
    }

    @Test
    fun aStepTooLongForSixteenBitsIsSplitIntoItsOwnWideRun() {
        // Short step, a 22 km step (two tiles), short step: three runs, the middle one stored wide.
        val f = build { add(40, doubleArrayOf(-1.0, 53.05, -1.0, 53.051, -1.0, 53.25, -1.0, 53.251)) }
        OsmLimitFile.Reader(f).use { r ->
            assertEquals(3L, r.roads)
            val south = OsmSpeedLimits.tileKey(53.05, -1.0)
            val north = OsmSpeedLimits.tileKey(53.25, -1.0)
            assertEquals(2, r.tiles)
            val s = r.segments(south).map { listOf(it.lat1, it.lat2, it.mph) }
            val n = r.segments(north).map { listOf(it.lat1, it.lat2, it.mph) }
            // The wide run is in both tiles, so a lookup at either end finds it.
            assertEquals(listOf(listOf(53.05, 53.051, 40.0), listOf(53.051, 53.25, 40.0)), s)
            assertEquals(listOf(listOf(53.051, 53.25, 40.0), listOf(53.25, 53.251, 40.0)), n)
        }
    }

    @Test
    fun theLargestStepThatFitsStaysNarrowAndOneMoreGoesWide() {
        // Header: magic, version, roads, date length + "2026-10-11", tile count, one 16-byte index entry = 48 bytes.
        // A narrow two-point run is 1 + 2 + 8 + 4 = 15 bytes; a wide one carries the second point in full, 19.
        val fits = build { add(30, doubleArrayOf(-1.0, 53.05, -1.0, 53.05 + 32_767e-6)) }
        val wide = build { add(30, doubleArrayOf(-1.0, 53.05, -1.0, 53.05 + 32_768e-6)) }
        assertEquals(63L, fits.length())
        assertEquals(67L, wide.length())
        OsmLimitFile.Reader(fits).use { assertEquals(listOf(listOf(53_050_000, -1_000_000, 53_082_767, -1_000_000, 30)), it.all()) }
        OsmLimitFile.Reader(wide).use { assertEquals(listOf(listOf(53_050_000, -1_000_000, 53_082_768, -1_000_000, 30)), it.all()) }
    }

    @Test
    fun negativeStepsRoundTripThroughTheSignedDifferences() {
        val xy = doubleArrayOf(-1.4634, 53.3800, -1.4700, 53.3750, -1.4650, 53.3701, -1.4800, 53.3702)
        val f = build { add(20, xy) }
        OsmLimitFile.Reader(f).use { r ->
            val pts = r.all().map { listOf(it[0], it[1]) } + listOf(r.all().last().let { listOf(it[2], it[3]) })
            assertEquals(listOf(listOf(53_380_000, -1_463_400), listOf(53_375_000, -1_470_000), listOf(53_370_100, -1_465_000), listOf(53_370_200, -1_480_000)), pts)
            assertTrue(r.all().all { it[4] == 20 })
        }
    }

    @Test
    fun aRoadAcrossATileEdgeIsInBothTiles() {
        val f = build { add(50, doubleArrayOf(-1.01, 53.05, -0.99, 53.05)) }
        OsmLimitFile.Reader(f).use { r ->
            assertEquals(1L, r.roads)
            assertTrue(r.has(OsmSpeedLimits.tileKey(53.05, -1.01)))
            assertTrue(r.has(OsmSpeedLimits.tileKey(53.05, -0.99)))
            assertFalse(r.has(OsmSpeedLimits.tileKey(53.15, -0.99)))
            assertEquals(1, r.segments(OsmSpeedLimits.tileKey(53.05, -1.01)).size)
            assertEquals(1, r.segments(OsmSpeedLimits.tileKey(53.05, -0.99)).size)
        }
    }

    @Test
    fun theFileIsTheSameWhateverThePassSizeAndTheSpillGoes() {
        val roads: OsmLimitFile.Writer.() -> Unit = {
            for (i in 0 until 25) add(20 + (i % 6) * 10, doubleArrayOf(-1.987 + i * 0.13, 52.013 + i * 0.07, -1.986 + i * 0.13, 52.015 + i * 0.07))
        }
        val spill = tmp.newFile()
        val w = OsmLimitFile.Writer(spill, passBytes = 1).apply(roads)
        val onePerPass = tmp.newFile().also { w.write(it, "2026-10-11") }
        assertFalse(spill.exists())
        val onePass = build(roads = roads)
        assertArrayEquals(onePass.readBytes(), onePerPass.readBytes())
        OsmLimitFile.Reader(onePerPass).use { r ->
            assertEquals(25L, r.roads)
            val keys = (0 until r.tiles).map(r::tileKeyAt)
            assertEquals(keys.sorted(), keys)
            assertEquals(25, r.all().size)
        }
    }

    @Test
    fun aSinglePointRoadIsNotWrittenAndAnEmptyFileReads() {
        val f = build(date = "2027-01-02") { add(30, doubleArrayOf(-1.0, 53.0)); add(30, DoubleArray(0)) }
        OsmLimitFile.Reader(f).use { r ->
            assertEquals(0L, r.roads)
            assertEquals(0, r.tiles)
            assertEquals("2027-01-02", r.dataDate)
            assertFalse(r.has(OsmSpeedLimits.tileKey(53.0, -1.0)))
            var calls = 0
            r.forEachSegment(OsmSpeedLimits.tileKey(53.0, -1.0)) { _, _, _, _, _ -> calls++ }
            assertEquals(0, calls)
        }
    }

    @Test
    fun closingWithoutWritingDeletesTheSpill() {
        val spill = tmp.newFile()
        OsmLimitFile.Writer(spill).use { it.add(30, doubleArrayOf(-1.0, 53.0, -1.0, 53.001)) }
        assertFalse(spill.exists())
    }

    @Test
    fun aFileThatIsNotALimitFileIsRefused() {
        val notOurs = tmp.newFile().apply { writeBytes("PK\u0003\u0004 not a limit file at all".toByteArray()) }
        assertTrue(runCatching { OsmLimitFile.Reader(notOurs) }.exceptionOrNull() is IllegalArgumentException)
        // Right magic, a version this reader doesn't know.
        val v2 = tmp.newFile().apply { writeBytes(byteArrayOf(0x41, 0x4E, 0x53, 0x4C, 0, 0, 0, 2) + ByteArray(20)) }
        assertTrue(runCatching { OsmLimitFile.Reader(v2) }.exceptionOrNull() is IllegalArgumentException)
        // Cut short inside the header or the index.
        val good = build { add(30, doubleArrayOf(-1.0, 53.05, -1.0, 53.051)) }
        val short = tmp.newFile().apply { writeBytes(good.readBytes().copyOf(40)) }
        assertTrue(runCatching { OsmLimitFile.Reader(short) }.exceptionOrNull() is EOFException)
        val tiny = tmp.newFile().apply { writeBytes(byteArrayOf(0x41, 0x4E)) }
        assertTrue(runCatching { OsmLimitFile.Reader(tiny) }.exceptionOrNull() is EOFException)
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TomTom OV2 records (DESIGN.md §5.6): little-endian lengths and coordinates, POI types 2 and 3,
 * skipper (1) records stepped over as 21 bytes whatever their block size, deleted (0) and unknown types
 * skipped by their length, and a corrupt or truncated
 * stream ending the file with at most one error, never a crash. Made-up names and places.
 */
class Ov2ImporterTest {
    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    // The records come from the shared made-up samples (Ov2Samples), in the hundred-thousandths OV2 stores.
    private fun poi(lonE5: Int, latE5: Int, name: String, type: Int = 2) =
        Ov2Samples.record(Ov2Samples.Poi(latE5 / 100_000.0, lonE5 / 100_000.0, name, type))

    private fun other(type: Int, payload: Int) = Ov2Samples.other(type, payload)

    @Test
    fun coordinatesAreLittleEndianHundredThousandths() {
        val bytes = poi(-150_001, 5_380_000, "A") + poi(17, 4_999_999, "B")
        // The bytes themselves: type, then the length low byte first.
        assertEquals(listOf<Byte>(2, 15, 0, 0, 0), bytes.take(5))
        val r = Ov2Importer().parse(bytes)
        assertEquals(-1.50001, r.rows[0].lon, 1e-12)
        assertEquals(53.8, r.rows[0].lat, 1e-12)
        assertEquals(0.00017, r.rows[1].lon, 1e-12)
        assertEquals(49.99999, r.rows[1].lat, 1e-12)
    }

    @Test
    fun deletedSkipperAndUnknownRecordsAreSkippedByTheirLength() {
        val bytes = other(0, 8) + poi(-150_000, 5_380_000, "One") + other(1, 16) + other(9, 3) + poi(-160_000, 5_390_000, "Two")
        val r = Ov2Importer().parse(bytes)
        assertEquals(listOf("One", "Two"), r.rows.map { it.name })
        assertEquals(0, r.errors.size)
        assertEquals(0, r.silentlySkipped)
    }

    @Test
    fun aSkipperIs21BytesAndItsBlockSizeCoversTheRecordsThatFollow() {
        // As TomTom and PGW write it: the skipper's int is the size of the whole block (itself and the POIs in it),
        // here the whole file, and nested skippers split it into areas (DESIGN.md §5.6; bench 2026-10-25).
        val inner = poi(-394_000, 5_163_000, "Swansea, Quadrant") + poi(-396_000, 5_158_000, "Oystermouth Rd")
        fun skipper(block: Int) = le(21).put(1).putInt(block).putInt(-390_000).putInt(5_165_000).putInt(-400_000).putInt(5_155_000).array()
        val nested = skipper(21 + inner.size) + inner
        val file = skipper(21 + nested.size + 5) + nested + other(0, 0)
        val r = Ov2Importer().parse(file)
        assertEquals(listOf("Swansea, Quadrant", "Oystermouth Rd"), r.rows.map { it.name })
        assertEquals(0, r.errors.size)
        // A block size over the 1 MiB record cap is normal for a big file's outer skipper, not corruption.
        val big = Ov2Importer().parse(skipper((1 shl 20) + 5) + inner)
        assertEquals(2, big.rows.size)
        assertEquals(0, big.errors.size)
    }

    @Test
    fun aLengthUnderTheHeaderOrOverAMebibyteEndsTheFileWithOneError() {
        for (len in listOf(4, 0, -1, (1 shl 20) + 1)) {
            val bad = le(5).put(2).putInt(len).array()
            val r = Ov2Importer().parse(poi(-150_000, 5_380_000, "Kept") + bad + poi(-160_000, 5_390_000, "Never read"))
            assertEquals("len $len", listOf("Kept"), r.rows.map { it.name })
            assertEquals(1, r.errors.size)
            assertEquals("bad record length $len", r.errors[0].reason)
            assertEquals(2, r.errors[0].lineNumber)
            assertEquals("type 2", r.errors[0].line)
        }
    }

    @Test
    fun aTruncatedNameIsOneErrorAndKeepsWhatCameBefore() {
        val full = poi(-160_000, 5_390_000, "A long camera name 40")
        val r = Ov2Importer().parse(poi(-150_000, 5_380_000, "Kept") + full.copyOf(full.size - 6))
        assertEquals(listOf("Kept"), r.rows.map { it.name })
        assertEquals(listOf("truncated record"), r.errors.map { it.reason })
    }

    @Test
    fun aStreamEndingInsideAHeaderOrASkipperStopsQuietly() {
        val p = poi(-150_000, 5_380_000, "Kept")
        // A type byte and half a length.
        assertEquals(0, Ov2Importer().parse(p + byteArrayOf(2, 30)).errors.size)
        // A skipper claiming more bytes than are left.
        val r = Ov2Importer().parse(p + other(1, 16).copyOf(10))
        assertEquals(1, r.rows.size)
        assertEquals(0, r.errors.size)
        // A POI cut inside its coordinates.
        val cut = Ov2Importer().parse(p + poi(-160_000, 5_390_000, "B").copyOf(9))
        assertEquals(1, cut.rows.size)
        assertEquals(0, cut.errors.size)
    }

    @Test
    fun badCoordinatesAreRowErrorsAndReadingGoesOn() {
        val r = Ov2Importer().parse(poi(0, 0, "Blank") + poi(-150_000, 9_500_000, "Pole") + poi(-150_000, 5_380_000, "Good"))
        assertEquals(listOf("Good"), r.rows.map { it.name })
        assertEquals(listOf(1, 2), r.errors.map { it.lineNumber })
        assertEquals(listOf("Blank", "Pole"), r.errors.map { it.line })
        assertTrue(r.failed)
    }

    @Test
    fun namesAreLatin1TrimmedAndCarryTheirLimit() {
        val r = Ov2Importer(SpeedUnit.KMH).parse(
            poi(-150_000, 5_380_000, "  Café cam 50  ") + poi(-150_000, 5_380_000, "Radar 70 mph") + poi(-150_000, 5_380_000, "Shop"),
        )
        assertEquals(listOf("Café cam 50", "Radar 70 mph", "Shop"), r.rows.map { it.name })
        assertEquals(listOf(50, 70, null), r.rows.map { it.speed })
        assertEquals(listOf(SpeedUnit.KMH, SpeedUnit.MPH, null), r.rows.map { it.speedUnit })
        assertTrue(r.rows.all { it.dirType == DirectionType.ANY && it.bearing == null && it.categoryCode == null })
    }

    @Test
    fun anEmptyFileImportsNothingAndFails() {
        val r = Ov2Importer().parse(ByteArray(0))
        assertEquals(0, r.rows.size)
        assertEquals(0, r.errors.size)
        assertTrue(r.failed)
    }

    @Test
    fun streamAndBytesReadTheSame() {
        val bytes = other(1, 16) + poi(-150_000, 5_380_000, "One", type = 3) + poi(-160_000, 5_390_000, "Two")
        assertEquals(Ov2Importer().parse(bytes), Ov2Importer().parse(bytes.inputStream()))
    }
}

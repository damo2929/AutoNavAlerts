// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Names follow PGW's scheme; IDs and positions here are made up. */
class PgwOv2Test {
    @Test
    fun oneWayCompassAndSpeed() {
        val p = PgwOv2.parseName("W-GATSO:1234@20")!!
        assertEquals(270f, p.bearing)
        assertEquals(DirectionType.ONE_WAY, p.directionType)
        assertEquals("GATSO", p.type)
        assertEquals("1234", p.pgwId)
        assertEquals(20, p.speed)
        assertFalse(p.temporary)
    }

    @Test
    fun reversibleMeansBoth() {
        val p = PgwOv2.parseName("NE-R-MONITRON:99@30")!!
        assertEquals(45f, p.bearing)
        assertEquals(DirectionType.BOTH, p.directionType)
    }

    @Test
    fun temporaryAndPending() {
        assertEquals("tSPECS", PgwOv2.categoryCode(PgwOv2.parseName("N-tSPECS:5@70")!!))
        assertEquals("Temporary Average speed (SPECS) (roadworks)", PgwOv2.categoryName("tSPECS"))
        val pm = PgwOv2.parseName("SE-R-pMOBILE:7@50")!!
        assertEquals("pMOBILE", pm.type)
        assertEquals("Pending mobile site", PgwOv2.categoryName("pMOBILE"))
        assertTrue(PgwOv2.isAverageSpeed("tSPECS"))
    }

    @Test
    fun variableAndUnknownSpeedAndMissingDirection() {
        val v = PgwOv2.parseName("SW-GATSO:1@0")!!
        assertNull(v.speed); assertTrue(v.variable)
        val tba = PgwOv2.parseName("S-GATSO:2")!!
        assertNull(tba.speed); assertFalse(tba.variable)
        val noDir = PgwOv2.parseName("R-pMOBILE:3@70")!!
        assertEquals(DirectionType.ANY, noDir.directionType)
        assertNull(noDir.bearing)
    }

    @Test
    fun watermarkIsNotACamera() {
        assertNull(PgwOv2.parseName("Copyright PocketGPSWorld.com"))
    }

    @Test
    fun parseFileSkipsWatermark() {
        fun rec(lon: Double, lat: Double, name: String): ByteArray {
            val n = (name + "\u0000").toByteArray(Charsets.ISO_8859_1)
            return ByteBuffer.allocate(13 + n.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(2).putInt(13 + n.size).putInt((lon * 1e5).toInt()).putInt((lat * 1e5).toInt()).put(n).array()
        }
        val r = PgwOv2.parse(rec(-1.5, 53.8, "E-GATSO:1@30") + rec(0.7, 51.5, "Copyright Example"))
        assertEquals(1, r.rows.size)
        assertEquals("1", r.rows[0].extra["pgwId"])
        assertEquals(90f, r.rows[0].bearing)
        assertEquals(1, r.silentlySkipped)
    }

    @Test
    fun familiesMergeSpeedFiles() {
        val f = PgwOv2.families(mapOf(
            "pocketgps_uk_gatso_30.ov2" to ByteArray(1), "pocketgps_uk_gatso_tba.ov2" to ByteArray(1),
            "pocketgps_uk_redlight.ov2" to ByteArray(1), "pocketgps_uk_gatso_30.bmp" to ByteArray(1),
        ))
        assertEquals(listOf("gatso", "redlight"), f.keys.toList())
        assertEquals(2, f["gatso"]!!.size)
    }

    /** Optional: PGW_OV2_ZIP=/path/to/your/ov2 download (never committed). */
    @Test
    fun realOv2ZipIfProvided() {
        val path = System.getenv("PGW_OV2_ZIP")
        assumeTrue("PGW_OV2_ZIP not set", !path.isNullOrBlank())
        val fams = PgwOv2.families(ZipEntries.read(File(path!!)))
        val summary = fams.mapValues { (_, files) -> files.sumOf { PgwOv2.parse(it).rows.size } }
        println("PGW OV2 families: $summary total=${summary.values.sum()}")
        assertTrue(summary.values.sum() > 10_000)
    }
}

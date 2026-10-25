// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.SpeedUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PGW's OV2 download (DESIGN.md §5.2a): one set per file, never merged, with a second region's
 * file of a type kept apart; each set's shipped icon; the stem, speed and category labels; and
 * the entry-name scheme's edges. Names follow PGW's scheme; ids and positions are made up.
 */
class PgwOv2MoreTest {
    private val one = byteArrayOf(1)

    @Test
    fun aSecondRegionsFileOfTheSameTypeKeepsItsRegion() {
        val f = PgwOv2.files(linkedMapOf(
            "pocketgps_uk_gatso_30.ov2" to one, "pocketgps_ie_gatso_30.ov2" to one,
            "UK/POCKETGPS_UK_SPECS_50.OV2" to one, "pocketgps_uk_gatso_30_extra.ov2" to one,
            "pocketgps_uk_redlight.txt" to one, "readme.ov2" to one,
        ))
        assertEquals(listOf("gatso_30", "ie_gatso_30", "specs_50"), f.keys.toList())
    }

    @Test
    fun theIconIsFoundInAnyCaseAndOnlyAsABitmap() {
        val e = linkedMapOf(
            "POCKETGPS_UK_SPECS_VAR_x2.BMP" to byteArrayOf(7),
            "pocketgps_uk_redlight.png" to byteArrayOf(8),
            "pocketgps_ie_mobile.bmp" to byteArrayOf(9),
        )
        assertArrayEquals(byteArrayOf(7), PgwOv2.iconFor("specs_var", e))
        assertNull(PgwOv2.iconFor("redlight", e))
        // Any region's icon serves the stem.
        assertArrayEquals(byteArrayOf(9), PgwOv2.iconFor("mobile", e))
        // The stem is matched literally, not as a pattern.
        assertNull(PgwOv2.iconFor("specs.var", mapOf("pocketgps_uk_specsXvar.bmp" to one)))
    }

    @Test
    fun stemsSplitAtTheFirstUnderscoreAndSpeedPartsReadAsWords() {
        assertEquals("specs" to "var", PgwOv2.splitStem("specs_var"))
        assertEquals("pmobile" to "70", PgwOv2.splitStem("pmobile_70"))
        assertEquals("a" to "b_c", PgwOv2.splitStem("a_b_c"))
        assertEquals("limit to be confirmed", PgwOv2.speedLabel("tba"))
        assertNull(PgwOv2.speedLabel(null))
        assertEquals("70 mph", PgwOv2.speedLabel("070"))
        assertEquals("odd", PgwOv2.speedLabel("odd"))
    }

    @Test
    fun everyTypeWordHasItsName() {
        val names = listOf("GATSO", "MONITRON", "TRUVELO", "REDSPEED", "REDLIGHT", "SPECS", "MOBILE", "pMOBILE").map(PgwOv2::categoryName)
        assertEquals(
            listOf("Gatso", "Monitron", "Truvelo", "RedSpeed (red light + speed)", "Red light camera", "Average speed (SPECS)", "Mobile camera site", "Pending mobile site"),
            names,
        )
        assertEquals("Temporary Gatso (roadworks)", PgwOv2.categoryName("tGATSO"))
        assertEquals("Vector", PgwOv2.categoryName("VECTOR"))
        assertTrue(PgwOv2.isAverageSpeed("SPECS"))
        assertFalse(PgwOv2.isAverageSpeed("GATSO"))
        assertFalse(PgwOv2.isAverageSpeed("tGATSO"))
    }

    @Test
    fun namesOutsideTheSchemeAreNotCameras() {
        assertNull(PgwOv2.parseName("n-GATSO:1@30"))   // compass in lower case
        assertNull(PgwOv2.parseName("NNE-GATSO:1@30")) // not one of the eight points
        assertNull(PgwOv2.parseName("N-GATSO:1@"))     // "@" without a speed
        assertNull(PgwOv2.parseName("N-GATSO"))        // no id
        assertNull(PgwOv2.parseName("N-GATSO:x1@30"))
        val p = PgwOv2.parseName("  NW-tMOBILE:42@40 ")!!
        assertEquals(315f, p.bearing)
        assertTrue(p.temporary)
        assertEquals("MOBILE", p.type)
        assertEquals("tMOBILE", PgwOv2.categoryCode(p))
    }

    private fun rec(lon: Double, lat: Double, name: String): ByteArray {
        val n = (name + "\u0000").toByteArray(Charsets.ISO_8859_1)
        return ByteBuffer.allocate(13 + n.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(2).putInt(13 + n.size).putInt(Math.round(lon * 1e5).toInt()).putInt(Math.round(lat * 1e5).toInt()).put(n).array()
    }

    @Test
    fun parsedRowsCarryTypeDirectionAndVariableLimit() {
        val bytes = rec(-1.5, 53.8, "S-R-tSPECS:77@0") + rec(-1.6, 53.9, "pMOBILE:5@30") + rec(0.0, 0.0, "E-GATSO:1@30")
        val r = PgwOv2.parse(bytes)
        assertEquals(2, r.rows.size)
        val specs = r.rows[0]
        assertEquals("tSPECS", specs.categoryCode)
        assertEquals(DirectionType.BOTH, specs.dirType)
        assertEquals(180f, specs.bearing)
        assertNull(specs.speed)
        assertEquals(mapOf("pgwId" to "77", "variableLimit" to "true"), specs.extra)
        assertEquals("S-R-tSPECS:77@0", specs.name)
        val pending = r.rows[1]
        assertEquals(DirectionType.ANY, pending.dirType)
        assertEquals(30, pending.speed)
        assertEquals(SpeedUnit.MPH, pending.speedUnit)
        assertEquals(mapOf("pgwId" to "5"), pending.extra)
        // The (0, 0) record stays a row error from the OV2 reader.
        assertEquals(1, r.errors.size)
        assertEquals(0, r.silentlySkipped)
    }
}

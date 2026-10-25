// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Format detection for a picked file (DESIGN.md §5, "FormatSniffer.detect(name, first bytes)"):
 * the extension in any case, the content when the extension says nothing, OV2 by name alone, and
 * iGO's header over a plain .txt.
 */
class FormatSnifferTest {
    private val igoHead = "X,Y,Type,Speed,DirType,Direction\n-1.5,53.8,1,30,1,90\n".toByteArray()

    @Test
    fun extensionsInAnyCase() {
        assertEquals(PoiFormat.GPX, FormatSniffer.detect("Trip.GPX", ByteArray(0)))
        assertEquals(PoiFormat.KML, FormatSniffer.detect("cams.Kml", ByteArray(0)))
        assertEquals(PoiFormat.CSV, FormatSniffer.detect("points.TXT", "lat,lon\n".toByteArray()))
    }

    @Test
    fun contentDecidesWhenTheExtensionDoesNot() {
        assertEquals(PoiFormat.KML, FormatSniffer.detect("export.xml", "<?xml version=\"1.0\"?><kml xmlns=\"x\">".toByteArray()))
        assertEquals(PoiFormat.GPX, FormatSniffer.detect("export.xml", "<?xml version=\"1.0\"?><gpx version=\"1.1\">".toByteArray()))
        assertEquals(PoiFormat.IGO_TXT, FormatSniffer.detect("cameras.dat", igoHead))
        assertNull(FormatSniffer.detect("photo.jpg", byteArrayOf(-1, -40, -1, -32)))
        assertNull(FormatSniffer.detect("noext", "lat,lon".toByteArray()))
    }

    @Test
    fun ov2IsTakenFromTheNameWhateverTheBytes() {
        assertEquals(PoiFormat.OV2, FormatSniffer.detect("cams.ov2", igoHead))
        assertEquals(PoiFormat.OV2, FormatSniffer.detect("cams.ov2", "<kml>".toByteArray()))
    }

    @Test
    fun anIgoHeaderInATxtOrCsvIsIgo() {
        assertEquals(PoiFormat.IGO_TXT, FormatSniffer.detect("pocketgps_uk_gatso.txt", igoHead))
        assertEquals(PoiFormat.IGO_TXT, FormatSniffer.detect("cams.csv", "x, y, type, speed, dirtype, direction\r\n".toByteArray()))
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import io.autonav.alerts.core.pgw.PgwPages
import io.autonav.alerts.core.pgw.PgwSubmission
import io.autonav.alerts.core.poi.DirectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OtherImportersTest {
    @Test
    fun kmlWithHeadingAndFolder() {
        val kml = """<?xml version="1.0"?><kml><Document><Folder><name>Fixed</name>
            <Placemark><name>Camera 40mph</name><ExtendedData><Data name="heading"><value>270</value></Data></ExtendedData>
              <Point><coordinates>-1.5,53.8,0</coordinates></Point></Placemark>
            <Placemark><name>No dir</name><Point><coordinates>-1.6,53.9</coordinates></Point></Placemark>
            </Folder></Document></kml>"""
        val r = KmlImporter().parse(kml.byteInputStream())
        assertEquals(2, r.rows.size)
        assertEquals(270f, r.rows[0].bearing)
        assertEquals(DirectionType.ONE_WAY, r.rows[0].dirType)
        assertEquals(40, r.rows[0].speed)
        assertEquals("Fixed", r.rows[0].categoryCode)
        assertEquals(DirectionType.ANY, r.rows[1].dirType)
    }

    @Test
    fun gpxWaypointsWithCourse() {
        val gpx = """<gpx><wpt lat="53.8" lon="-1.5"><name>Cam 30</name><course>90</course><type>cam</type></wpt>
            <wpt lat="53.9" lon="-1.6"><name>Shop</name></wpt></gpx>"""
        val r = GpxImporter().parse(gpx.byteInputStream())
        assertEquals(2, r.rows.size)
        assertEquals(90f, r.rows[0].bearing)
        assertEquals(30, r.rows[0].speed)
        assertNull(r.rows[1].bearing)
    }

    @Test
    fun ov2RecordsIncludingSkipper() {
        fun poi(lon: Double, lat: Double, name: String): ByteArray {
            val n = (name + "\u0000").toByteArray(Charsets.ISO_8859_1)
            return ByteBuffer.allocate(13 + n.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(2).putInt(13 + n.size).putInt((lon * 100_000).toInt()).putInt((lat * 100_000).toInt()).put(n).array()
        }
        val skipper = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN).put(1).putInt(21).putInt(0).putInt(0).putInt(0).putInt(0).array()
        val bytes = skipper + poi(-1.5, 53.8, "[SPECS] 50") + poi(-1.6, 53.9, "Shop")
        val r = Ov2Importer().parse(bytes)
        assertEquals(2, r.rows.size)
        assertEquals(53.8, r.rows[0].lat, 1e-5)
        assertEquals(50, r.rows[0].speed)
        assertEquals(DirectionType.ANY, r.rows[0].dirType)
    }

    @Test
    fun roadAndJunctionNumbersAreNotLimits() {
        assertNull(speedFromText("M25 J10 Gatso"))
        assertNull(speedFromText("B1234 camera"))
        assertEquals(50 to null, speedFromText("A303 Gatso 50"))
        assertEquals(40, speedFromText("A61 Chesterfield Rd 40mph")?.first)
        assertEquals(40 to io.autonav.alerts.core.poi.SpeedUnit.MPH, speedFromText("Camera 30 then 40 mph"))
        assertEquals(50, speedFromText("[SPECS] 50")?.first)
    }

    @Test
    fun ov2ShortOrTypeThreeRecordsDontBreakTheImport() {
        val bad = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).put(2).putInt(10).putInt(0).put(0).array()
        val n = "[SPECS] 40\u0000123\u0000extra\u0000".toByteArray(Charsets.ISO_8859_1)
        val type3 = ByteBuffer.allocate(13 + n.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(3).putInt(13 + n.size).putInt(-150_000).putInt(5_380_000).put(n).array()
        val r = Ov2Importer().parse(bad + type3)
        assertEquals(1, r.errors.size)
        assertEquals(1, r.rows.size)
        assertEquals("[SPECS] 40", r.rows[0].name)
        assertEquals(40, r.rows[0].speed)
    }

    @Test
    fun kmlZeroSpeedIsNoLimit() {
        val kml = """<kml><Placemark><name>Cam</name><ExtendedData><Data name="speed"><value>0</value></Data></ExtendedData>
            <Point><coordinates>-1.5,53.8</coordinates></Point></Placemark></kml>"""
        assertNull(KmlImporter().parse(kml.byteInputStream()).rows[0].speed)
    }

    @Test
    fun csvWithProfileAndQuotes() {
        val csv = "lat;lon;name;speed;heading;dir\n53.8;-1.5;\"Cam; A\";30;180;both\n53.9;-1.6;B;;;\n"
        val profile = CsvProfile(delimiter = ';', latCol = 0, lonCol = 1, nameCol = 2, speedCol = 3, bearingCol = 4, dirTypeCol = 5)
        val r = CsvImporter(profile).parse(csv.reader().buffered())
        assertEquals(2, r.rows.size)
        assertEquals("Cam; A", r.rows[0].name)
        assertEquals(DirectionType.BOTH, r.rows[0].dirType)
        assertEquals(180f, r.rows[0].bearing)
        assertEquals(DirectionType.ANY, r.rows[1].dirType)
        assertEquals(';', CsvImporter.detectDelimiter(csv))
    }

    @Test
    fun formatSniffing() {
        assertEquals(PoiFormat.IGO_TXT, FormatSniffer.detect("cams.txt", "X,Y,Type,Speed,DirType,Direction\n".toByteArray()))
        assertEquals(PoiFormat.KML, FormatSniffer.detect("a.kml", ByteArray(0)))
        assertEquals(PoiFormat.OV2, FormatSniffer.detect("a.OV2", ByteArray(0)))
        assertEquals(PoiFormat.CSV, FormatSniffer.detect("a.csv", "lat,lon".toByteArray()))
    }

    @Test
    fun pgwPagesParsing() {
        val loggedOut = """<form><input name="username"><input type="password" name="user_password"></form>"""
        val loggedIn = """<a href="x">Logout</a><p>My Account</p>"""
        assertEquals(PgwPages.LoginState.LOGGED_OUT, PgwPages.loginState(loggedOut))
        assertEquals(PgwPages.LoginState.LOGGED_IN, PgwPages.loginState(loggedIn))
        val page = """<a href="/temp/users/pgpsw-speed-cams-aBcDe-(24-101).zip">Download</a>"""
        assertEquals("https://www.pocketgpsworld.com/temp/users/pgpsw-speed-cams-aBcDe-(24-101).zip", PgwPages.findZipLink(page))
        assertNull(PgwPages.findZipLink("<p>No subscription</p>"))
    }

    @Test
    fun submissionUsesSpeedCodes() {
        val s = PgwSubmission(1, null, 53.8, -1.5, 2, 30, 274, DirectionType.ONE_WAY, "test")
        val f = s.formFields().toMap()
        assertEquals("2", f["Speed"]) // 30 mph is code 2
        assertEquals("0", f["Direction"])
        assertEquals("53.80000", f["Latitude"])
        assertTrue(f.containsKey("SendEntry"))
        assertEquals("0", PgwPages.speedCode(null))
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import io.autonav.alerts.data.PoiRepository.PgwFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hard rule 9 (AGENTS.md §3.1; DESIGN.md §5, §5.2, §5.2a): every file becomes its own set named from the file,
 * never a prompt ([PoiRepository.setNameFor]); which zip entries count as POI files (licence and readme texts
 * don't); and what each PocketGPSWorld file stem becomes ([PgwFile.of]: set name, source, built-in icon,
 * category names, alerts on by default). Names are made up; no PGW data (hard rule 6).
 */
class PoiRepositoryNamingTest {
    @Test fun setNameDropsTheExtensionThePgpswSuffixAndUnderscores() {
        assertEquals("Croydon Tramlink Stop", PoiRepository.setNameFor("Croydon_Tramlink_Stop_PGPSW.ov2"))
        assertEquals("Croydon Tramlink Stop", PoiRepository.setNameFor("Croydon_Tramlink_Stop-pgpsw.ov2"))
        assertEquals("Croydon Tramlink Stop", PoiRepository.setNameFor("Croydon_Tramlink_Stop pgpsw.ov2"))
        assertEquals("School crossings", PoiRepository.setNameFor("School_crossings.csv"))
        // Only the last extension goes; dots inside the name stay.
        assertEquals("Trip v1.2", PoiRepository.setNameFor("Trip_v1.2.gpx"))
        // "PGPSW" in the middle of a name is kept: only a trailing suffix is dropped.
        assertEquals("PGPSW extras", PoiRepository.setNameFor("PGPSW_extras.kml"))
        // Surrounding spaces from leading/trailing underscores are trimmed.
        assertEquals("Mobile", PoiRepository.setNameFor("_Mobile_.txt"))
    }

    @Test fun aNameThatWouldBeEmptyKeepsTheFileName() {
        assertEquals("PGPSW.ov2", PoiRepository.setNameFor("PGPSW.ov2"))
        assertEquals(".gpx", PoiRepository.setNameFor(".gpx"))
        assertEquals("___.csv", PoiRepository.setNameFor("___.csv"))
        // No extension at all: the whole name is used.
        assertEquals("Cameras", PoiRepository.setNameFor("Cameras"))
    }

    @Test fun poiFilesAreKnownExtensionsCaseInsensitively() {
        for (n in listOf("a.txt", "b.CSV", "c.kml", "d.GPX", "e.Ov2", "dir/gatso_30.ov2")) assertTrue(n, PoiRepository.isPoiFile(n))
        for (n in listOf("icon.bmp", "icon.png", "cameras.zip", "notes.doc", "ov2", "x.ov2.bak")) assertFalse(n, PoiRepository.isPoiFile(n))
    }

    @Test fun licenceReleaseAndReadmeTextsAreNotPoiFiles() {
        for (n in listOf("LICENCE.txt", "license.txt", "Release.txt", "README.TXT", "pgw_licence_X2_icons.txt", "readme_first.csv")) {
            assertFalse(n, PoiRepository.isPoiFile(n))
        }
        // Any name containing those words is left out, wherever the word is.
        assertFalse(PoiRepository.isPoiFile("prerelease_cameras.ov2"))
    }

    @Test fun igoStemsBecomeTheirOwnNamedSets() {
        val gatso = PgwFile.of("gatso")
        assertEquals("PGW fixed cameras", gatso.setName)
        assertEquals(PoiSourceType.PGW, gatso.source)
        assertEquals(IconRepository.CAMERA_FIXED, gatso.icon)
        assertEquals("Fixed camera", gatso.categoryName("1"))
        assertEquals("Fixed camera (type 11)", gatso.categoryName("11"))
        assertTrue(gatso.alertsByDefault)

        val expected = mapOf(
            "mobile" to Triple("PGW mobile camera sites", IconRepository.CAMERA_MOBILE, "Mobile camera site"),
            "redlight" to Triple("PGW red light cameras", IconRepository.CAMERA_REDLIGHT, "Red light camera"),
            "specs" to Triple("PGW average speed cameras (SPECS)", IconRepository.CAMERA_AVERAGE, "Average speed camera"),
        )
        for ((stem, e) in expected) {
            val f = PgwFile.of(stem)
            assertEquals(stem, e.first, f.setName)
            assertEquals(stem, PoiSourceType.PGW, f.source)
            assertEquals(stem, e.second, f.icon)
            // The family names every code the same.
            assertEquals(stem, e.third, f.categoryName("anything"))
        }
    }

    @Test fun pendingMobileSitesAreTheirOwnSetAndAlertByDefault() {
        // Owner, 2026-10-23 (§5.2): pending sites alert by default but stay a separate set so they can be turned off.
        val p = PgwFile.of("pmobile")
        assertEquals("PGW pending mobile sites (unverified)", p.setName)
        assertEquals(PoiSourceType.PGW_PENDING, p.source)
        assertEquals(IconRepository.CAMERA_PENDING, p.icon)
        assertEquals("Pending mobile site", p.categoryName("x"))
        assertTrue(p.alertsByDefault)
    }

    @Test fun theOldSingleFilePackageAndUnknownStems() {
        val sc = PgwFile.of("sc")
        assertEquals("PocketGPSWorld cameras (all)", sc.setName)
        assertEquals(IconRepository.CAMERA_FIXED, sc.icon)
        // Unknown codes use the iGO naming: "Type N" (§5.2).
        assertEquals("Type 99", sc.categoryName("99"))
        val other = PgwFile.of("tunnels")
        assertEquals("PGW Tunnels", other.setName)
        assertEquals(PoiSourceType.PGW, other.source)
        assertEquals(IconRepository.CAMERA_FIXED, other.icon)
        assertEquals("Type 42", other.categoryName("42"))
    }

    @Test fun ov2StemsNameTheSetBySpeedAndUseTheOv2CategoryNames() {
        assertEquals("PGW fixed cameras · 30 mph", PgwFile.of("gatso_30", ov2 = true).setName)
        assertEquals("PGW average speed cameras (SPECS) · variable limit", PgwFile.of("specs_var", ov2 = true).setName)
        assertEquals("PGW mobile camera sites · limit to be confirmed", PgwFile.of("mobile_tba", ov2 = true).setName)
        // No speed part: the family's own name.
        assertEquals("PGW red light cameras", PgwFile.of("redlight", ov2 = true).setName)
        val ov2 = PgwFile.of("gatso_30", ov2 = true)
        assertEquals(IconRepository.CAMERA_FIXED, ov2.icon)
        assertEquals(PoiSourceType.PGW, ov2.source)
        assertEquals(io.autonav.alerts.core.poi.importers.PgwOv2.categoryName("GATSO"), ov2.categoryName("GATSO"))
        // The pending family keeps its own source and icon in the OV2 layout too.
        val pending = PgwFile.of("pmobile_30", ov2 = true)
        assertEquals(PoiSourceType.PGW_PENDING, pending.source)
        assertEquals(IconRepository.CAMERA_PENDING, pending.icon)
        assertEquals("PGW pending mobile sites (unverified) · 30 mph", pending.setName)
        // ov2 = false ignores the speed part: the whole stem is looked up.
        assertEquals("PGW Gatso_30", PgwFile.of("gatso_30").setName)
    }
}

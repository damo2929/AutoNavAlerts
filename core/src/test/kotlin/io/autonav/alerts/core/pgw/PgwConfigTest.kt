// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.pgw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PocketGPSWorld form fields and downloads (DESIGN.md §6.1 steps 2.1–2.3, §5.2/§5.2a): the
 * login and download POST bodies exactly as the site's own forms send them, the two packages the
 * user can pick with OV2 the default, and the link pattern. No credentials: made-up values only.
 */
class PgwConfigTest {
    @Test
    fun loginFormIsThePhpNukeFormWithEmptyHiddenFields() {
        assertEquals(
            listOf("username" to "u", "user_password" to "p w", "op" to "login", "redirect" to "", "mode" to "", "f" to "", "t" to ""),
            PgwConfig.loginForm("u", "p w"),
        )
    }

    @Test
    fun downloadFormDefaultsToTheOv2PackageWithPendingMobiles() {
        assertEquals(
            listOf("op" to "DownloadPackage", "idPackage" to "1", "getPmob" to "yes", "getFrance" to "no", "getSwiss" to "no"),
            PgwConfig.downloadForm(),
        )
        val igo = PgwConfig.downloadForm(PgwDownload.IGO.idPackage, pendingMobiles = false).toMap()
        assertEquals("288", igo["idPackage"])
        assertEquals("no", igo["getPmob"])
    }

    @Test
    fun theDownloadChoiceIsReadBackByNameAndFallsBackToOv2() {
        assertEquals(PgwDownload.TOMTOM_OV2, PgwDownload.DEFAULT)
        assertEquals(1, PgwDownload.TOMTOM_OV2.idPackage)
        assertEquals(288, PgwDownload.IGO.idPackage)
        assertEquals(PgwDownload.IGO, PgwDownload.fromName("IGO"))
        assertEquals(PgwDownload.TOMTOM_OV2, PgwDownload.fromName("TOMTOM_OV2"))
        // A stored value from another build, a different case or nothing at all: the default.
        assertEquals(PgwDownload.TOMTOM_OV2, PgwDownload.fromName("igo"))
        assertEquals(PgwDownload.TOMTOM_OV2, PgwDownload.fromName("IGO_SINGLE"))
        assertEquals(PgwDownload.TOMTOM_OV2, PgwDownload.fromName(null))
    }

    @Test
    fun theLinkPatternMatchesTheDesignsExample() {
        assertTrue(PgwConfig.ZIP_LINK.matches("/temp/users/pgpsw-speed-cams-tETDF-(24-101).zip"))
        assertTrue(PgwConfig.ZIP_LINK.matches("https://www.pocketgpsworld.com/temp/users/pgpsw-speed-cams-a1-(1-2).zip"))
        assertEquals("Release.txt", PgwConfig.RELEASE_FILE)
        assertTrue(PgwConfig.RSS_URL.startsWith(PgwConfig.BASE))
    }
}

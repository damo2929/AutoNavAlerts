// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.pgw

import io.autonav.alerts.core.poi.DirectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading PocketGPSWorld's pages (DESIGN.md §6.1 "Login", "Find the link"; §8.5 the submission
 * form): when a page says the session is live, the per-user hidden fields of the right form only,
 * the form's speed and direction codes from the §8.5 table, and the temp zip link in its forms.
 * The pages here are made-up fragments, never captured ones.
 */
class PgwPagesTest {
    private fun state(html: String) = PgwPages.loginState(html)

    @Test
    fun loggedInNeedsALogoutMentionAndNoPasswordField() {
        assertEquals(PgwPages.LoginState.LOGGED_IN, state("<p>Welcome back. <a href=\"#\">Log out</a></p>"))
        assertEquals(PgwPages.LoginState.LOGGED_IN, state("<b>LOGOUT</b>"))
        // Both on one page (the login form beside a stray word): not logged in.
        assertEquals(PgwPages.LoginState.LOGGED_OUT, state("<p>logout</p><form><input type=\"password\" name=\"user_password\"></form>"))
        // A link whose only trace of "logout" is in its address doesn't count: only the text does.
        assertEquals(PgwPages.LoginState.LOGGED_OUT, state("<a href=\"modules.php?op=logout\">Bye</a>"))
        // As built, "My Account" alone isn't enough (§6.1 lists it beside "logout").
        assertEquals(PgwPages.LoginState.LOGGED_OUT, state("<p>My Account</p>"))
        assertEquals(PgwPages.LoginState.LOGGED_OUT, state(""))
    }

    @Test
    fun hiddenFieldsComeOnlyFromTheFormWithIdCam() {
        val html = """
            <form name="search"><input type="hidden" name="op" value="search"></form>
            <form name="submit" method="post">
              <input type="hidden" name="POISubmitted" value="1">
              <input type="hidden" name="email" value="someone@example.test">
              <input type="hidden" name="userid" value="12345">
              <input type="hidden" name="" value="nameless">
              <input type="text" name="idCam" value="">
              <input type="text" name="Latitude" value="">
            </form>"""
        val f = PgwPages.submitHiddenFields(html)
        assertEquals(mapOf("POISubmitted" to "1", "email" to "someone@example.test", "userid" to "12345"), f)
        assertEquals(emptyMap<String, String>(), PgwPages.submitHiddenFields("<form><input type=\"hidden\" name=\"a\" value=\"b\"></form>"))
        assertEquals(emptyMap<String, String>(), PgwPages.submitHiddenFields("<p>Please log in</p>"))
    }

    @Test
    fun everySpeedOnTheFormHasItsCodeAndOthersAreUnknown() {
        val table = mapOf(
            20 to "1", 25 to "14", 30 to "2", 35 to "15", 40 to "3", 45 to "16", 50 to "4", 55 to "17", 60 to "5", 65 to "18",
            70 to "6", 80 to "8", 90 to "9", 100 to "10", 110 to "11", 120 to "12", 130 to "13", 140 to "19", 160 to "20",
        )
        for ((mph, code) in table) assertEquals("$mph mph", code, PgwPages.speedCode(mph))
        for (mph in listOf(0, 10, 75, 150, 200, -30)) assertEquals("$mph mph", "0", PgwPages.speedCode(mph))
    }

    @Test
    fun onlyBothWaysIsReversible() {
        assertEquals("2", PgwPages.directionCode(DirectionType.BOTH))
        assertEquals("0", PgwPages.directionCode(DirectionType.ONE_WAY))
        assertEquals("0", PgwPages.directionCode(DirectionType.ANY))
    }

    @Test
    fun theZipLinkIsMadeAbsoluteOnTheHttpsSite() {
        val path = "temp/users/pgpsw-speed-cams-tETDF-(24-101).zip"
        val want = "https://www.pocketgpsworld.com/$path"
        assertEquals(want, PgwPages.findZipLink("<a href=\"https://www.pocketgpsworld.com/$path\">x</a>"))
        assertEquals(want, PgwPages.findZipLink("<a href=\"http://www.pocketgpsworld.com/$path\">x</a>"))
        assertEquals(want, PgwPages.findZipLink("<a href=\"$path\">x</a>"))
        // The first link on the page wins.
        assertEquals(want, PgwPages.findZipLink("<a href=\"/$path\">a</a><a href=\"/temp/users/pgpsw-speed-cams-zzz-(24-99).zip\">b</a>"))
        // A code with characters outside letters and digits, or a missing version, isn't PGW's link.
        assertNull(PgwPages.findZipLink("<a href=\"/temp/users/pgpsw-speed-cams-ab_c-(24-101).zip\">x</a>"))
        assertNull(PgwPages.findZipLink("<a href=\"/temp/users/pgpsw-speed-cams-abc.zip\">x</a>"))
    }
}

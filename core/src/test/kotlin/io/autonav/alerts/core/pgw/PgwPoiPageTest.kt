// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.pgw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The POI page's low-bridge download (DESIGN.md §6.5), on a made-up page in the site's layout (found 2026-11-01):
 * tree checkboxes named by their labels, a category's label ending `{date : count}`, a subcategory's `(date : count)`.
 * The member number and every label here are invented; no PocketGPSWorld data (hard rule 6).
 */
class PgwPoiPageTest {
    private fun page(withBridges: Boolean = true, uid: String = "12345") = """
        <html><body><a href="modules.php?name=Your_Account&amp;op=logout">Logout</a>
        <form name="fDownload1" method="post" action="modules.php?name=POIs&amp;Download">
          <input type="hidden" name="uid" value="$uid">
          <input type="checkbox" name="C_France" value="France">
          <input type="checkbox" name="C_United_Kingdom" value="United Kingdom" checked>
          <select name="System"><option>TomTom (OV2)</option></select>
          <input type="checkbox" name="Holiday Park {2019-01-20 : 181}" value="0">
          <input type="checkbox" name="Caravan &amp; Camping (2019-01-20 : 12) " value="0">
          ${if (withBridges) """
          <input type="checkbox" name="Low Bridges {2026-11-01 : 30}" value="0">
          <input type="checkbox" name="Bridge - 10ft (2025-07-22 : 5)  <font color=red>Premium</font>" value="0">
          <input type="checkbox" name="Bridge - 13ft 6in (2026-02-07 : 20)  <font color=red>Premium</font>" value="0">
          <input type="checkbox" name="Unknown Height (2023-11-28 : 5)  <font color=red>Premium</font>" value="0">
          """ else ""}
          <input type="checkbox" name="Motorcycle Dealership {2023-09-08 : 280}" value="0">
          <input type="checkbox" name="Ducati (2023-09-08 : 9) " value="0">
          <input type="radio" name="FileType" value="Separate" checked><input type="radio" name="FileType" value="Combined">
          <input type="checkbox" name="Link" value="Link">
        </form></body></html>
    """.trimIndent()

    @Test
    fun theLowBridgesBranchIsReadInPageOrder() {
        val sel = PgwPages.lowBridges(page())!!
        assertEquals("12345", sel.uid)
        assertEquals(listOf("C_United_Kingdom" to "United Kingdom"), sel.countries)
        assertEquals("Low Bridges {2026-11-01 : 30}", sel.version)
        assertEquals(
            listOf(
                "Bridge - 10ft (2025-07-22 : 5)  <font color=red>Premium</font>",
                "Bridge - 13ft 6in (2026-02-07 : 20)  <font color=red>Premium</font>",
                "Unknown Height (2023-11-28 : 5)  <font color=red>Premium</font>",
            ),
            sel.labels,
        )
    }

    @Test
    fun theFormTicksTheCategoryAndEveryHeightAsTheSiteDoes() {
        val form = PgwConfig.bridgeDownloadForm(PgwPages.lowBridges(page())!!)
        assertEquals("12345", form.toMap()["uid"])
        assertEquals("TomTom (OV2)", form.toMap()["System"])
        assertEquals("Separate", form.toMap()["FileType"])
        assertEquals("Link", form.toMap()["Link"])
        assertEquals("2", form.toMap()["Low Bridges {2026-11-01 : 30}"])
        assertEquals(3, form.count { (k, v) -> v == "2" && k != "Low Bridges {2026-11-01 : 30}" })
        // Only the branch: the neighbouring categories are never sent.
        assertNull(form.toMap()["Motorcycle Dealership {2023-09-08 : 280}"])
        assertNull(form.toMap()["Caravan & Camping (2019-01-20 : 12) "])
    }

    @Test
    fun noBridgesNoFormOrNoMemberMeansNothingToDownload() {
        assertNull(PgwPages.lowBridges(page(withBridges = false)))
        assertNull(PgwPages.lowBridges(page(uid = "")))
        assertNull(PgwPages.lowBridges("<html><body>Log in</body></html>"))
    }

    @Test
    fun theZipLinkIsFoundOnTheLinkPage() {
        val html = """<p>Your file is ready: <a href="temp/users/12345_POIs.zip">POIs.zip</a></p>"""
        assertEquals("https://www.pocketgpsworld.com/temp/users/12345_POIs.zip", PgwPages.findPoiZipLink(html))
        assertEquals(
            "https://www.pocketgpsworld.com/temp/users/12345_POIs.zip",
            PgwPages.findPoiZipLink("""<a href="https://www.pocketgpsworld.com/temp/users/12345_POIs.zip">x</a>"""),
        )
        assertNull(PgwPages.findPoiZipLink("<p>Error in selection criteria</p>"))
    }
}

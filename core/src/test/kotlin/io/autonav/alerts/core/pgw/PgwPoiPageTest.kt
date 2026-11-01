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
    /**
     * The POI page as the site sends it (checked 2026-11-01): the form with the member's `uid` and the country boxes as
     * plain inputs, and the category tree as a script for the page's `Bs_Tree` (no `<input>` for the categories).
     */
    private fun page(withBridges: Boolean = true, uid: String = "12345") = """
        <html><body><a href="modules.php?name=Your_Account&amp;op=logout">Logout</a>
        <form name="fDownload1" method="post" action="modules.php?name=POIs&amp;Download">
          <input name="uid" type="hidden" id="uid" value="$uid">
          <label><input name='C_France' type='checkbox' value='France'>France</label>
          <label><input name='C_United_Kingdom'  type='checkbox' value='United Kingdom' checked>United Kingdom</label>
          <select name="System"><option>TomTom (OV2)</option></select>
          <div id="treeDiv1"></div>
          <input type="radio" name="FileType" value="Separate" checked><input type="radio" name="FileType" value="Combined">
          <input type="checkbox" name="Link" value="Link">
        </form>
        <script>
         var a = new Array;
         a[0] = new Array;
         a[0]['caption'] = "Holiday Park {2019-01-20 : 181}";
         a[0]['children'] = new Array;
         a[0]['children'][0] = new Array;
         a[0]['children'][0]['caption'] = "Caravan & Camping (2019-01-20 : 12) ";
         ${if (withBridges) """
         a[1] = new Array;
         a[1]['caption'] = "Low Bridges {2026-11-01 : 30}";
         a[1]['children'] = new Array;
         a[1]['children'][0] = new Array;
         a[1]['children'][0]['caption'] = "Bridge - 10ft (2025-07-22 : 5)  <font color=red>Premium</font>";
         a[1]['children'][1] = new Array;
         a[1]['children'][1]['caption'] = "Bridge - 13ft 6in (2026-02-07 : 20)  <font color=red>Premium</font>";
         a[1]['children'][2] = new Array;
         a[1]['children'][2]['caption'] = "Unknown Height (2023-11-28 : 5)  <font color=red>Premium</font>";
         """ else ""}
         a[2] = new Array;
         a[2]['caption'] = "Motorcycle Dealership {2023-09-08 : 280}";
         a[2]['children'] = new Array;
         a[2]['children'][0] = new Array;
         a[2]['children'][0]['caption'] = "Ducati (2023-09-08 : 9) ";
         myTree = new Bs_Tree();
         myTree.initByArray(a);
         myTree.drawInto('treeDiv1');
        </script></body></html>
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
        assertNull(form.toMap()["Ducati (2023-09-08 : 9) "])
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

    @Test
    fun noBareClosingBraceInThePagePatternsForAndroidsRegexEngine() {
        // ICU (Android) rejects an unescaped "}" outside a quantifier; the JVM doesn't, so a JVM test alone missed it
        // and the camera sync failed on the phone with NoClassDefFoundError: PgwPages (2026-11-01).
        val src = java.io.File("src/main/kotlin/io/autonav/alerts/core/pgw/PgwPages.kt").readText()
        val patterns = Regex("Regex\\(\"\"\"(.*?)\"\"\"").findAll(src).map { it.groupValues[1] }.toList()
        assert(patterns.isNotEmpty())
        for (p in patterns) {
            // Drop escaped braces and {n} / {n,m} quantifiers; any "}" left is bare.
            val rest = p.replace("\\}", "").replace("\\{", "").replace(Regex("\\{\\d+(,\\d*)?\\}"), "")
            assertEquals("bare } in $p", -1, rest.indexOf('}'))
        }
    }
}

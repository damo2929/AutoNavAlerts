// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.pgw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Release identity and ordering (DESIGN.md §6.2 "Update detection"): the version read from the
 * feed title and from a download's Release.txt, newer by (major, minor) whatever the feed order,
 * the hash identity of an item whose title doesn't parse, and what the feed's items yield. The
 * feed carries no camera data; these items are made up.
 */
class PgwReleaseTest {
    private fun rel(major: Int, minor: Int) = PgwRelease(major, minor, null, "", null)

    @Test
    fun parseVersionTakesTheFirstNumberPair() {
        assertEquals(24 to 101, PgwRelease.parseVersion("24.101"))
        assertEquals(24 to 101, PgwRelease.parseVersion("Version 24.101 (07 Oct 26)"))
        assertEquals(1 to 2, PgwRelease.parseVersion("v1.2.3"))
        assertNull(PgwRelease.parseVersion("version twenty-four"))
        assertNull(PgwRelease.parseVersion(""))
    }

    @Test
    fun newerIsByMajorThenMinorNumerically() {
        assertTrue(rel(25, 1).isNewerThan("24.999"))
        assertFalse(rel(24, 999).isNewerThan("25.1"))
        assertTrue(rel(24, 100).isNewerThan("24.99"))
        // An installed value that isn't a version (an old hash, or garbage) never blocks an update.
        assertTrue(rel(24, 101).isNewerThan("h:0123456789abcdef"))
        assertTrue(rel(24, 101).isNewerThan(""))
        val sorted = listOf(rel(24, 101), rel(23, 900), rel(24, 99), rel(25, 0)).sorted()
        assertEquals(listOf("23.900", "24.99", "24.101", "25.0"), sorted.map { it.version })
        // The date and summary play no part in the order.
        assertEquals(0, PgwRelease(24, 101, LocalDate.of(2026, 1, 1), "a", null).compareTo(PgwRelease(24, 101, null, "b", "x")))
    }

    @Test
    fun anUnparsedItemIsKeyedByAStableHash() {
        val a = PgwUpdateFeed.parseItem("Database refreshed", "Summary", "https://example.test")
        val b = PgwUpdateFeed.parseItem("Database refreshed", "Summary", null)
        assertEquals(a.hashKey, b.hashKey)
        assertTrue(Regex("^h:[0-9a-f]{16}$").matches(a.version))
        assertEquals(a.hashKey, a.version)
        assertEquals(0, a.major)
        assertNull(a.date)
        // Any change to the title is a new release too.
        assertTrue(PgwUpdateFeed.parseItem("Database refreshed!", "Summary", null).isNewerThan(a.version))
        assertTrue(a.isNewerThan("24.101"))
    }

    @Test
    fun releaseTxtNeedsTheWordVersion() {
        assertEquals(24 to 101, PgwUpdateFeed.versionFromReleaseTxt("version 24.101"))
        assertEquals(24 to 101, PgwUpdateFeed.versionFromReleaseTxt("Licence 1.2\nVersion 24.101 (07 Oct 26)\nVersion 24.100"))
        assertNull(PgwUpdateFeed.versionFromReleaseTxt("24.101 (07 Oct 26)"))
        assertNull(PgwUpdateFeed.versionFromReleaseTxt("Version24.101"))
        assertNull(PgwUpdateFeed.versionFromReleaseTxt(""))
    }

    @Test
    fun titleVariantsAndAnUnreadableDate() {
        val spaced = PgwUpdateFeed.parseItem("  Version 24.101 : 7 Oct 26  ", "  Twenty cameras changed.  ", "https://example.test/c")
        assertEquals("24.101", spaced.version)
        assertEquals(LocalDate.of(2026, 10, 7), spaced.date)
        assertEquals("Twenty cameras changed.", spaced.summary)
        assertEquals("https://example.test/c", spaced.link)
        val badDate = PgwUpdateFeed.parseItem("version 24.102:07 Foo 26", "", null)
        assertEquals("24.102", badDate.version)
        assertNull(badDate.date)
        assertNull(badDate.hashKey)
    }

    @Test
    fun newestPrefersParsedReleasesAndOtherwiseTheFirst() {
        val hashA = PgwUpdateFeed.parseItem("Refresh A", "", null)
        val hashB = PgwUpdateFeed.parseItem("Refresh B", "", null)
        assertNull(PgwUpdateFeed.newest(emptyList()))
        assertEquals(hashA, PgwUpdateFeed.newest(listOf(hashA, hashB)))
        assertEquals("24.101", PgwUpdateFeed.newest(listOf(hashA, rel(24, 101), hashB, rel(24, 99)))!!.version)
    }

    @Test
    fun feedItemsWithBlankLinksOrNoDescription() {
        val feed = """<rss><channel>
            <item><title>version 24.103:21 Oct 26</title><link>  </link></item>
            <item><title>version 24.104:28 Oct 26</title><description>Weekly.</description></item>
            </channel></rss>"""
        val items = PgwUpdateFeed.parse(feed.byteInputStream())
        assertEquals(listOf("24.103", "24.104"), items.map { it.version })
        assertEquals(listOf("", "Weekly."), items.map { it.summary })
        assertEquals(listOf<String?>(null, null), items.map { it.link })
        assertEquals(0, PgwUpdateFeed.parse("<rss><channel/></rss>".byteInputStream()).size)
    }
}

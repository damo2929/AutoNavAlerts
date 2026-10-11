// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.pgw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PgwUpdateFeedTest {
    // Structure mirrors the public RSS feed; wording is invented.
    private val feed = """
        <?xml version="1.0" encoding="ISO-8859-1"?>
        <rss version="2.0"><channel>
          <title>Speed Camera database updates</title>
          <item><title>version 24.99:23 Sep 26</title><link>https://example.test/c</link>
                <description>Older release.</description></item>
          <item><title>version 24.101:07 Oct 26</title><link>https://example.test/c</link>
                <description>This release sees 20 cameras changed.</description></item>
          <item><title>version 24.100:30 Sep 26</title><link>https://example.test/c</link>
                <description>Middle release.</description></item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun parsesItemsAndFindsNewestRegardlessOfOrder() {
        val releases = PgwUpdateFeed.parse(feed.byteInputStream())
        assertEquals(3, releases.size)
        val newest = PgwUpdateFeed.newest(releases)!!
        assertEquals("24.101", newest.version)
        assertEquals(LocalDate.of(2026, 10, 7), newest.date)
        assertEquals("This release sees 20 cameras changed.", newest.summary)
    }

    @Test
    fun versionComparisonIsNumericNotLexical() {
        val r = PgwUpdateFeed.parseItem("version 24.101:07 Oct 26", "", null)
        assertTrue(r.isNewerThan("24.99"))
        assertTrue(r.isNewerThan("24.100"))
        assertFalse(r.isNewerThan("24.101"))
        assertFalse(r.isNewerThan("25.1"))
        assertTrue(r.isNewerThan(null))
    }

    @Test
    fun missingDateIsAccepted() {
        val r = PgwUpdateFeed.parseItem("version 24.101", "x", null)
        assertEquals("24.101", r.version)
        assertNull(r.date)
    }

    @Test
    fun unparseableTitleFallsBackToHash() {
        val a = PgwUpdateFeed.parseItem("Database refreshed", "one", null)
        val b = PgwUpdateFeed.parseItem("Database refreshed", "two", null)
        assertNotNull(a.hashKey)
        assertTrue(a.version != b.version)
        assertTrue(a.isNewerThan(b.version))
        assertFalse(a.isNewerThan(a.version))
    }

    @Test
    fun versionFromReleaseTxtAndZipName() {
        assertEquals(24 to 101, PgwUpdateFeed.versionFromReleaseTxt("Speed Camera Database\r\n\r\nVersion 24.101 (07 Oct 26)\r\n"))
        assertEquals(24 to 101, PgwUpdateFeed.versionFromZipName("/temp/users/pgpsw-speed-cams-aBcDe-(24-101).zip"))
        assertNull(PgwUpdateFeed.versionFromZipName("other.zip"))
    }

    @Test
    fun rejectsDoctypeEntities() {
        val evil = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;</title></item></channel></rss>"""
        val result = runCatching { PgwUpdateFeed.parse(evil.byteInputStream()) }
        assertTrue(result.isFailure || result.getOrThrow().none { it.summary.contains("root") })
    }
}

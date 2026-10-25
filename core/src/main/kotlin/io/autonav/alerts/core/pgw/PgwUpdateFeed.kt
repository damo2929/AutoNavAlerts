// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.pgw

import io.autonav.alerts.core.io.secureDocumentBuilderFactory
import io.autonav.alerts.core.io.sha256Hex
import org.w3c.dom.Element
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * A PocketGPSWorld database release, as announced by
 * https://www.pocketgpsworld.com/speed-camera-rss.php (DESIGN.md §6.2). The feed carries no camera data.
 */
data class PgwRelease(
    /** The version as "major.minor", e.g. 24 and 101 for "24.101"; both 0 when only [hashKey] identifies it. */
    val major: Int,
    val minor: Int,
    /** The release date from the title, or null when it didn't parse. */
    val date: LocalDate?,
    /** The feed's own description of the release, shown in the sync notification (§6.1 step 4). */
    val summary: String,
    val link: String?,
    /** Fallback identity when the title can't be parsed: any change counts as a new release. */
    val hashKey: String? = null,
) : Comparable<PgwRelease> {
    /** The string stored as `installedVersion`. */
    val version: String get() = hashKey ?: "$major.$minor"

    /** By (major, minor): the newest release is the greatest, whatever order the feed lists them in (§6.2). */
    override fun compareTo(other: PgwRelease): Int =
        compareValuesBy(this, other, PgwRelease::major, PgwRelease::minor)

    /** True if this release is newer than an installed version string such as "24.101". */
    fun isNewerThan(installed: String?): Boolean {
        if (installed == null) return true
        if (hashKey != null) return hashKey != installed
        val parsed = parseVersion(installed) ?: return true
        return compareValuesBy(Pair(major, minor), parsed, { it.first }, { it.second }) > 0
    }

    companion object {
        private val VERSION = Regex("""(\d+)\.(\d+)""")

        /** The first "N.M" in a string, as (major, minor). */
        fun parseVersion(s: String): Pair<Int, Int>? =
            VERSION.find(s)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }
}

/**
 * Reader of PocketGPSWorld's release feed (DESIGN.md §6.2): one `<item>` per database release,
 * whose title is "version 24.101:07 Oct 26". Pure XML parsing with external entities off; the
 * app's `PgwFeedCheckWorker` (in `PgwWorkers`) calls [parse] on IO once a day after a conditional
 * GET, and `PgwClient` uses [versionFromReleaseTxt] to cross-check a download. The feed carries no
 * camera data, so nothing here touches PGW's licensed content.
 */
object PgwUpdateFeed {
    private val TITLE = Regex("""version\s+(\d+)\.(\d+)\s*:?\s*(.*)""", RegexOption.IGNORE_CASE)
    private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH)

    /** Version from a zip's Release.txt, e.g. "Version 24.101 (07 Oct 26)" → (24, 101). */
    fun versionFromReleaseTxt(text: String): Pair<Int, Int>? =
        Regex("""Version\s+(\d+)\.(\d+)""", RegexOption.IGNORE_CASE).find(text)
            ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /** Every item of the feed as a [PgwRelease], in feed order; an unparseable title gets a hash identity. */
    fun parse(input: InputStream): List<PgwRelease> {
        // Hardening: no external entities or DTDs from a remote feed.
        val doc = secureDocumentBuilderFactory().newDocumentBuilder().parse(input)
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).map { i ->
            val item = items.item(i) as Element
            val title = item.text("title")
            val description = item.text("description")
            val link = item.text("link").ifBlank { null }
            parseItem(title, description, link)
        }
    }

    /** One item. A title without "version N.M" yields a release keyed by a hash of the item (§6.2), so any change counts. */
    fun parseItem(title: String, description: String, link: String?): PgwRelease {
        val m = TITLE.find(title.trim())
        return if (m != null) {
            PgwRelease(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                date = parseDate(m.groupValues[3]),
                summary = description.trim(),
                link = link,
            )
        } else {
            PgwRelease(0, 0, null, description.trim(), link, hashKey = "h:" + (title + "\n" + description).sha256Hex().take(16))
        }
    }

    /** The highest (major, minor) among the parsed releases, else the first unparsed one, else null. */
    fun newest(releases: List<PgwRelease>): PgwRelease? =
        releases.filter { it.hashKey == null }.maxOrNull() ?: releases.firstOrNull()

    private fun parseDate(s: String): LocalDate? = try {
        LocalDate.parse(s.trim(), DATE_FMT)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun Element.text(tag: String): String =
        getElementsByTagName(tag).item(0)?.textContent ?: ""
}

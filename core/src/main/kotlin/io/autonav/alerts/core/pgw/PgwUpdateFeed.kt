// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.pgw

import org.w3c.dom.Element
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A PocketGPSWorld database release, as announced by
 * https://www.pocketgpsworld.com/speed-camera-rss.php (DESIGN.md §6.2). The feed carries no camera data.
 */
data class PgwRelease(
    val major: Int,
    val minor: Int,
    val date: LocalDate?,
    val summary: String,
    val link: String?,
    /** Fallback identity when the title can't be parsed: any change counts as a new release. */
    val hashKey: String? = null,
) : Comparable<PgwRelease> {
    val version: String get() = hashKey ?: "$major.$minor"

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

        fun parseVersion(s: String): Pair<Int, Int>? =
            VERSION.find(s)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }
}

object PgwUpdateFeed {
    private val TITLE = Regex("""version\s+(\d+)\.(\d+)\s*:?\s*(.*)""", RegexOption.IGNORE_CASE)
    private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH)

    /** Version from a zip's Release.txt, e.g. "Version 24.101 (07 Oct 26)" → (24, 101). */
    fun versionFromReleaseTxt(text: String): Pair<Int, Int>? =
        Regex("""Version\s+(\d+)\.(\d+)""", RegexOption.IGNORE_CASE).find(text)
            ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /** Version from a temp download link, e.g. "pgpsw-speed-cams-tETDF-(24-101).zip" → (24, 101). */
    fun versionFromZipName(name: String): Pair<Int, Int>? =
        Regex("""\((\d+)-(\d+)\)\.zip""").find(name)
            ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    fun parse(input: InputStream): List<PgwRelease> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Hardening: no external entities or DTDs from a remote feed.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(input)
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).map { i ->
            val item = items.item(i) as Element
            val title = item.text("title")
            val description = item.text("description")
            val link = item.text("link").ifBlank { null }
            parseItem(title, description, link)
        }
    }

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
            PgwRelease(0, 0, null, description.trim(), link, hashKey = "h:" + sha256(title + "\n" + description).take(16))
        }
    }

    fun newest(releases: List<PgwRelease>): PgwRelease? =
        releases.filter { it.hashKey == null }.maxOrNull() ?: releases.firstOrNull()

    private fun parseDate(s: String): LocalDate? = try {
        LocalDate.parse(s.trim(), DATE_FMT)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun Element.text(tag: String): String =
        getElementsByTagName(tag).item(0)?.textContent ?: ""

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

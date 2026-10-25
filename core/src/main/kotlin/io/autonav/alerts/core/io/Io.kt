// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.io

import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Small byte-level helpers shared by the importers (DESIGN.md §5), the limit file and shapefile
 * readers (§8.4a), the PGW feed (§6.2) and the contacts cache key (§8.3). Pure functions, no state,
 * no Android: called on whichever worker thread the caller runs (IO or WorkManager). They uphold
 * the "never all in memory" rule of §1.3 by skipping and hashing streams in place, and the
 * hardening of §6.2 by parsing a user's or the site's XML with DTDs and external entities off.
 */

/**
 * Skips exactly [n] bytes, trying a read when `skip` makes no progress, and throws [EOFException]
 * if the stream ends first (the OV2, shapefile and limit-file readers all skip records this way).
 */
fun InputStream.skipFully(n: Long) {
    var left = n
    while (left > 0) {
        val s = skip(left)
        if (s <= 0) { if (read() < 0) throw EOFException(); left-- } else left -= s
    }
}

/**
 * A DOM factory for XML from outside the app (a user's KML or GPX, PGW's feed): no DTDs, no
 * external entities, entity references not expanded, namespaces ignored. Each feature is set with
 * `runCatching`, since Android's parser may not know a name, and that must not stop the parse.
 */
fun secureDocumentBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = false
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    isExpandEntityReferences = false
}

/** SHA-256 of the UTF-8 bytes as 64 lower-case hex digits. */
fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }

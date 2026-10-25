// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of [MapsKeyCreator] (DESIGN.md §13.1 "Error messages", §13.2 step 5): the "Paste key from
 * clipboard" check of a Google API key's shape, and the help link taken from a Google Cloud error's details.
 * The keys here are made up (hard rule 5: there is no built-in key anywhere).
 */
class MapsKeyCreatorTest {
    private val body35 = "0123456789abcdefghijABCDEFGHIJ_-xyz"

    @Test fun aKeyIsAizaAnd35UrlSafeCharacters() {
        assertEquals(35, body35.length)
        assertTrue(MapsKeyCreator.looksLikeKey("AIza$body35"))
        // Pasted with spaces or a newline around it.
        assertTrue(MapsKeyCreator.looksLikeKey("  AIza$body35\n"))
    }

    @Test fun anythingElseIsNotAKey() {
        assertFalse(MapsKeyCreator.looksLikeKey(""))
        assertFalse(MapsKeyCreator.looksLikeKey("AIza${body35.dropLast(1)}"))
        assertFalse(MapsKeyCreator.looksLikeKey("AIza${body35}0"))
        assertFalse(MapsKeyCreator.looksLikeKey("aiza$body35"))
        assertFalse(MapsKeyCreator.looksLikeKey("AIza${body35.dropLast(1)}."))
        assertFalse(MapsKeyCreator.looksLikeKey("AIza${body35.dropLast(1)} "))
        // A key inside other text (an email, a URL) isn't offered: the whole clipboard must be the key.
        assertFalse(MapsKeyCreator.looksLikeKey("key=AIza$body35"))
        assertFalse(MapsKeyCreator.looksLikeKey("AIza$body35 AIza$body35"))
    }

    private fun err(details: String?) = JSONObject("""{"code":403,"message":"denied"${details?.let { ""","details":$it""" } ?: ""}}""")

    @Test fun noDetailsNoLinkNoHelp() {
        assertNull(MapsKeyCreator.helpUrl(null))
        assertNull(MapsKeyCreator.helpUrl(err(null)))
        assertNull(MapsKeyCreator.helpUrl(err("[]")))
        assertNull(MapsKeyCreator.helpUrl(err("""[{"@type":"ErrorInfo","reason":"SERVICE_DISABLED"}]""")))
        assertNull(MapsKeyCreator.helpUrl(err("""[{"links":[]}]""")))
    }

    @Test fun theFirstDetailWithLinksGivesItsFirstUrl() {
        val details = """[{"@type":"ErrorInfo"},{"links":[]},
            {"@type":"Help","links":[{"description":"Enable it","url":"https://console.cloud.google.com/apis/api/x"},{"url":"https://second"}]},
            {"links":[{"url":"https://later"}]}]"""
        assertEquals("https://console.cloud.google.com/apis/api/x", MapsKeyCreator.helpUrl(err(details)))
    }

    @Test fun aBlankFirstUrlIsNoHelp() {
        // The first link decides, even when a later one has a URL.
        assertNull(MapsKeyCreator.helpUrl(err("""[{"links":[{"url":""},{"url":"https://second"}]}]""")))
        assertNull(MapsKeyCreator.helpUrl(err("""[{"links":[{"description":"no url"}]}]""")))
    }
}

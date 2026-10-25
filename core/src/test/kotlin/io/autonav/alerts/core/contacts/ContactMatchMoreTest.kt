// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contacts' addresses (DESIGN.md §8.3 "Matching a name", "Choosing the result"): the shapes of
 * UK postcode, the last one in an address winning, the normalised form the cache key hashes, an
 * ambiguous geocode left to the user, and word starts after punctuation. Made-up people and places.
 */
class ContactMatchMoreTest {
    @Test
    fun everyShapeOfUkPostcode() {
        val shapes = mapOf(
            "M1 1AE" to "M11AE", "B33 8TH" to "B338TH", "CR2 6XH" to "CR26XH",
            "DN55 1PT" to "DN551PT", "W1A 0AX" to "W1A0AX", "EC1A 1BB" to "EC1A1BB", "ls1  1aa" to "LS11AA",
        )
        for ((written, want) in shapes) assertEquals(written, want, ContactMatch.postcode("1 Some Road, Town $written"))
        assertNull(ContactMatch.postcode("Unit S8 0X, Town"))
        assertNull(ContactMatch.postcode("Ref XS80XXY"))
    }

    @Test
    fun theLastPostcodeInTheAddressWins() {
        assertEquals("S80XX", ContactMatch.postcode("c/o LS1 1AA office, 12 High St, Sheffield S8 0XX"))
    }

    @Test
    fun normaliseFoldsCaseWhitespaceAndSpacesBeforeCommas() {
        assertEquals("12 high st, sheffield s8 0xx", ContactMatch.normalise("  12  High\tSt ,\nSheffield   S8 0XX "))
        assertEquals(ContactMatch.key("12 high st, sheffield s8 0xx"), ContactMatch.key("12 HIGH ST , SHEFFIELD  S8 0XX"))
    }

    @Test
    fun anAmbiguousOrUnmatchedGeocodeIsLeftToTheUser() {
        val two = listOf("1 High St, Sheffield S8 0XX", "1 High St East, Sheffield S8 0XX")
        // Two results share the postcode, and there's more than one result: ask.
        assertNull(ContactMatch.choose("1 High St, Sheffield S8 0XX", two) { it })
        // No result has the postcode, but it's the only result: take it.
        assertEquals("Somewhere LS1 1AA", ContactMatch.choose("1 High St, S8 0XX", listOf("Somewhere LS1 1AA")) { it })
        // A result with no address can't match a postcode.
        val withNull = listOf<String?>(null, "Leeds LS1 1AA", "Sheffield S8 0XX")
        assertEquals("Sheffield S8 0XX", ContactMatch.choose("1 High St, S8 0XX", withNull) { it })
    }

    @Test
    fun laterWordsStartAfterSpacesHyphensApostrophesAndDots() {
        assertEquals(1, ContactMatch.rank("Siobhan O'Neil", "neil"))
        assertEquals(1, ContactMatch.rank("Dr. Who", "who"))
        assertEquals(1, ContactMatch.rank("Jean-Luc Picard", "luc"))
        assertEquals(0, ContactMatch.rank("  Alice Brown", " al "))
        assertNull(ContactMatch.rank("Alice Brown", " a "))
        assertEquals(0, ContactMatch.rank("Al", "AL"))
    }
}

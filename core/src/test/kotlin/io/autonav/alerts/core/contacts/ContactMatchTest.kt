// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.contacts

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull

class ContactMatchTest {
    @Test
    fun namesMatchFromTheStartOfAnyWord() {
        assertEquals(0, ContactMatch.rank("Mum", "mu"))
        assertEquals(0, ContactMatch.rank("Dave Smith", "dave s"))
        assertEquals(1, ContactMatch.rank("Dave Smith", "SMI"))
        assertEquals(1, ContactMatch.rank("Mary-Jane O'Neil", "jane"))
        assertNull(ContactMatch.rank("Dave Smith", "mith"))
        assertNull(ContactMatch.rank("Mum", "m"))
    }

    @Test
    fun postcodeIsFoundAndNormalised() {
        assertEquals("S80XX", ContactMatch.postcode("12 High Street, Sheffield s8 0xx, UK"))
        assertEquals("SW1A1AA", ContactMatch.postcode("Buckingham Palace\nLondon SW1A1AA"))
        assertNull(ContactMatch.postcode("1 Rue de Rivoli, Paris"))
    }

    @Test
    fun keyIgnoresCaseAndSpacing() {
        assertEquals(ContactMatch.key("12 High St ,  Sheffield"), ContactMatch.key("12 high st, sheffield"))
        assertNotEquals(ContactMatch.key("12 High St"), ContactMatch.key("14 High St"))
        assertEquals(64, ContactMatch.key("x").length)
    }

    @Test
    fun choosesTheResultWithTheSamePostcodeOrTheOnlyOne() {
        val results = listOf("High St, Leeds LS1 1AA", "High St, Sheffield S8 0XX")
        assertEquals(results[1], ContactMatch.choose("12 High St, Sheffield S8 0XX", results) { it })
        assertNull(ContactMatch.choose("12 High St", results) { it })
        assertEquals("Only one", ContactMatch.choose("12 High St", listOf("Only one")) { it })
        assertNull(ContactMatch.choose("12 High St", emptyList<String>()) { it })
    }
}

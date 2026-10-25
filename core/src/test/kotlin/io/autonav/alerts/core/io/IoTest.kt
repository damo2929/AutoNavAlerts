// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * The shared byte and XML helpers (DESIGN.md §5 importers, §8.4a readers, §6.2 feed hardening,
 * §8.3 contacts cache key): an exact skip that fails at the end of the stream, SHA-256 against
 * published vectors, XML from outside the app parsed with no DTDs or external entities, and the
 * child-text helpers as the importers rely on them.
 */
class IoTest {
    @Test
    fun skipFullySkipsExactlyAndFailsAtTheEnd() {
        val s = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        s.skipFully(0)
        s.skipFully(3)
        assertEquals(4, s.read())
        assertTrue(runCatching { s.skipFully(2) }.exceptionOrNull() is EOFException)
    }

    @Test
    fun skipFullyReadsWhenSkipMakesNoProgress() {
        // A stream whose skip() never moves, as some inflating streams do.
        val stubborn = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < 10) pos++ else -1
            override fun skip(n: Long): Long = 0
        }
        stubborn.skipFully(7)
        assertEquals(7, stubborn.read())
        assertTrue(runCatching { stubborn.skipFully(5) }.exceptionOrNull() is EOFException)
    }

    @Test
    fun sha256MatchesPublishedVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "".sha256Hex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "abc".sha256Hex())
        assertEquals("d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592", "The quick brown fox jumps over the lazy dog".sha256Hex())
        // UTF-8, so an accented letter isn't its Latin-1 byte.
        assertEquals(64, "Café".sha256Hex().length)
        assertFalse("Café".sha256Hex() == String("Café".toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).sha256Hex())
    }

    private fun parse(xml: String) = secureDocumentBuilderFactory().newDocumentBuilder().parse(xml.byteInputStream())

    @Test
    fun theFactoryRefusesDoctypesAndExternalEntities() {
        val f = secureDocumentBuilderFactory()
        assertFalse(f.isNamespaceAware)
        assertFalse(f.isExpandEntityReferences)
        assertTrue(f.getFeature("http://apache.org/xml/features/disallow-doctype-decl"))
        assertFalse(f.getFeature("http://xml.org/sax/features/external-general-entities"))
        assertFalse(f.getFeature("http://xml.org/sax/features/external-parameter-entities"))
        assertTrue(runCatching { parse("""<!DOCTYPE a [<!ENTITY x SYSTEM "file:///etc/hostname">]><a>&x;</a>""") }.isFailure)
        // Even an internal entity (the "billion laughs" shape) is refused with its DOCTYPE.
        assertTrue(runCatching { parse("""<!DOCTYPE a [<!ENTITY l "lol"><!ENTITY l2 "&l;&l;">]><a>&l2;</a>""") }.isFailure)
        assertEquals("a", parse("<a/>").documentElement.tagName)
    }

    @Test
    fun childTextIsRawAndFindsTheFirstDescendant() {
        val root = parse("<r><x>  padded  </x><deep><y>inner</y></deep><x>second</x><empty/></r>").documentElement
        assertEquals("  padded  ", root.childText("x"))
        assertEquals("inner", root.childText("y"))
        assertEquals("", root.childText("empty"))
        assertNull(root.childText("missing"))
        assertNull(root.child("missing"))
        assertEquals(listOf("  padded  ", "second"), root.getElementsByTagName("x").elements().map { it.textContent })
        assertEquals(0, root.getElementsByTagName("none").elements().size)
    }
}

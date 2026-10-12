// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.contacts

import java.security.MessageDigest

/** Name matching, postcodes and the geocode cache key for contacts' addresses (DESIGN.md §8.3). */
object ContactMatch {
    /** Shortest query that searches contacts. */
    const val MIN_QUERY = 2

    private val UK_POSTCODE = Regex("""\b([A-Z]{1,2}[0-9][A-Z0-9]?)\s*([0-9][A-Z]{2})\b""", RegexOption.IGNORE_CASE)

    /**
     * How well [name] matches [query]: 0 if the name starts with it, 1 if a later word does,
     * null if it doesn't match. Case-insensitive.
     */
    fun rank(name: String, query: String): Int? {
        val q = query.trim().lowercase()
        if (q.length < MIN_QUERY) return null
        val n = name.trim().lowercase()
        if (n.startsWith(q)) return 0
        return if (n.split(Regex("""[\s\-'.]+""")).drop(1).any { it.startsWith(q) }) 1 else null
    }

    /** The UK postcode in an address, upper case without spaces ("S8 0XX" → "S80XX"), or null. */
    fun postcode(address: String): String? =
        UK_POSTCODE.findAll(address).lastOrNull()?.let { (it.groupValues[1] + it.groupValues[2]).uppercase() }

    /** Lower case, single spaces, no spaces before commas: two spellings of one address match. */
    fun normalise(address: String): String =
        address.lowercase().replace(Regex("""\s+"""), " ").replace(" ,", ",").trim()

    /** Cache key: SHA-256 of the normalised address, so the address itself isn't stored. */
    fun key(address: String): String =
        MessageDigest.getInstance("SHA-256").digest(normalise(address).toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Which geocode result to use for a contact's address: the one with the same postcode if
     * there's exactly one, else the only result, else null (ask the user).
     */
    fun <T> choose(address: String, results: List<T>, resultAddress: (T) -> String?): T? {
        val pc = postcode(address)
        if (pc != null) {
            val same = results.filter { r -> resultAddress(r)?.let(::postcode) == pc }
            if (same.size == 1) return same[0]
        }
        return results.singleOrNull()
    }
}

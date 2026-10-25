// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.contacts

import io.autonav.alerts.core.io.sha256Hex

/**
 * Name matching, postcodes and the geocode cache key for contacts' addresses (DESIGN.md §8.3).
 * No Android, no threads of its own: the app's `ContactsSource` calls it while the car's Search
 * screen is typed into (every keystroke, local and quick) and when an address is geocoded on IO.
 * Nothing here stores or logs an address: [key] is what the `contact_geocode` table is keyed by.
 */
object ContactMatch {
    /** Shortest query that searches contacts. */
    // 2 characters, one fewer than Places needs (§8.3 "Matching a name").
    const val MIN_QUERY = 2

    // Outward code (area letters, district digit, optional letter or digit) then inward code (digit, two letters).
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
        // "smith" matches "Dave Smith": any later word may start with the query; the first word was tried above.
        return if (n.split(Regex("""[\s\-'.]+""")).drop(1).any { it.startsWith(q) }) 1 else null
    }

    /** The UK postcode in an address, upper case without spaces ("S8 0XX" → "S80XX"), or null. */
    fun postcode(address: String): String? =
        UK_POSTCODE.findAll(address).lastOrNull()?.let { (it.groupValues[1] + it.groupValues[2]).uppercase() }

    /** Lower case, single spaces, no spaces before commas: two spellings of one address match. */
    fun normalise(address: String): String =
        address.lowercase().replace(Regex("""\s+"""), " ").replace(" ,", ",").trim()

    /** Cache key: SHA-256 of the normalised address, so the address itself isn't stored. */
    fun key(address: String): String = normalise(address).sha256Hex()

    // Postcodes are compared without spaces or case (§8.3 "Choosing the result").
    /**
     * Which geocode result to use for a contact's address: the one with the same postcode if
     * there's exactly one, else the only result, else null (ask the user).
     */
    fun <T> choose(address: String, results: List<T>, resultAddress: (T) -> String?): T? =
        postcode(address)?.let { pc -> results.singleOrNull { r -> resultAddress(r)?.let(::postcode) == pc } } ?: results.singleOrNull()
}

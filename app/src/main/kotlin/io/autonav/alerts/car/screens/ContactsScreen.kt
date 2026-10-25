// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.CarContext
import androidx.car.app.model.ItemList
import io.autonav.alerts.R
import io.autonav.alerts.car.CarScreenHost
import io.autonav.alerts.contacts.ContactAddress

/**
 * Contacts with a postal address to drive to (DESIGN.md §8.3): recently used first (from the
 * `contact_geocode` cache), then A–Z, as many rows as the host allows minus one for the last
 * row, "Search contacts…", which opens the typed contacts search for long address books. A row
 * reads "Name" over "Home · 12 High St · 3.2 mi" (the distance once the address has been
 * located). Tapping a row locates the address and opens the route preview, like a contact row
 * in Search; several matches go to Search's "Which address?" list.
 *
 * Pushed by `SearchScreen`'s Contacts row (only when the optional contacts permission is
 * granted, §8.3); popped by the host's Back. A `ListTemplate` ([LoadingListScreen]), so the map
 * stays behind it. All on the main thread: `ContactsSource` does its own reads off it. The template
 * is re-sent only when the rows have loaded (hard rule 3): one `invalidate()` per resume.
 */
class ContactsScreen(carContext: CarContext, private val session: CarScreenHost) : LoadingListScreen<List<Pair<ContactAddress, Int?>>>(carContext, reloadOnResume = true) {
    private val deps get() = session.deps
    override val title: String get() = carContext.getString(R.string.car_contacts)
    override val emptyText: String get() = carContext.getString(R.string.car_no_contacts)

    /** Each contact address with its known distance in metres (null until the address has been located once); reloaded on every resume: a pick moves that contact to the top. */
    override suspend fun load(): List<Pair<ContactAddress, Int?>> {
        val near = deps.location().lastLocation.value
        return deps.contacts().all(limit()).map { it to deps.contacts().knownDistanceM(it, near) }
    }

    /** The host's list limit, less the "Search contacts…" row. */
    private fun limit(): Int = CarUi.listLimit(carContext).coerceAtLeast(2) - 1

    /** The contact rows, then "Search contacts…". */
    override fun ItemList.Builder.addRows(items: List<Pair<ContactAddress, Int?>>) {
        items.forEach { (c, distance) ->
            addItem(CarUi.contactRow(carContext, c.name, c.label, c.address, distance?.let { CarUi.formatDistance(it, session.imperial) }) { pick(c) })
        }
        addItem(CarUi.row(carContext, carContext.getString(R.string.car_search_contacts), R.drawable.ic_action_search) {
            screenManager.push(SearchScreen(carContext, session, contactsOnly = true))
        })
    }

    // Geocoding happens only now, for the one address picked (never the whole address book, §8.3):
    // from the 90-day cache, else one Places Text Search billed to the user's own key.
    private fun pick(c: ContactAddress) = CarUi.pickContact(this, session, c) { options ->
        screenManager.push(SearchScreen(carContext, session, initialQuery = c.name, contactsOnly = true, initialChoice = c to options))
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.R
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.contacts.ContactAddress
import kotlinx.coroutines.launch

/**
 * Contacts with a postal address to drive to (DESIGN.md §8.3): recently used first (from the
 * `contact_geocode` cache), then A–Z, as many rows as the host allows minus one for the last
 * row, "Search contacts…", which opens the typed contacts search for long address books. A row
 * reads "Name" over "Home · 12 High St · 3.2 mi" (the distance once the address has been
 * located). Tapping a row locates the address and opens the route preview, like a contact row
 * in Search; several matches go to Search's "Which address?" list.
 *
 * Pushed by `SearchScreen`'s Contacts row (only when the optional contacts permission is
 * granted, §8.3); popped by the host's Back. A `ListTemplate`, so the map stays behind it. All
 * on the main thread: `ContactsSource` does its own reads off it. The template is re-sent only
 * when the rows have loaded (hard rule 3): one `invalidate()` per resume.
 */
class ContactsScreen(carContext: CarContext, private val session: NavSession) : Screen(carContext), DefaultLifecycleObserver {
    private val deps get() = session.deps
    /** Each contact address with its known distance in metres (null until the address has been located once). */
    private var rows: List<Pair<ContactAddress, Int?>>? = null

    init {
        lifecycle.addObserver(this)
    }

    /** Reloaded on every resume: a pick moves that contact to the top. */
    override fun onResume(owner: LifecycleOwner) {
        lifecycleScope.launch {
            val near = deps.location().lastLocation.value
            val all = deps.contacts().all(limit())
            rows = all.map { it to deps.contacts().knownDistanceM(it, near) }
            invalidate()
        }
    }

    /** The host's list limit, less the "Search contacts…" row. */
    private fun limit(): Int = CarUi.listLimit(carContext).coerceAtLeast(2) - 1

    /** The list, or a loading template before the first load; the host shows its own spinner for that. */
    override fun onGetTemplate(): Template {
        val header = CarUi.header(carContext.getString(R.string.car_contacts))
        val r = rows ?: return CarUi.loading(header)
        val list = ItemList.Builder()
        if (r.isEmpty()) list.setNoItemsMessage(carContext.getString(R.string.car_no_contacts))
        r.forEach { (c, distance) ->
            list.addItem(
                Row.Builder().setTitle(c.name)
                    .addText(listOfNotNull(c.label, c.address, distance?.let { CarUi.formatDistance(it, session.imperial) }).joinToString(" · "))
                    .setImage(CarUi.icon(carContext, R.drawable.ic_action_contact))
                    .setOnClickListener { pick(c) }
                    .build(),
            )
        }
        list.addItem(CarUi.row(carContext, carContext.getString(R.string.car_search_contacts), R.drawable.ic_action_search) {
            screenManager.push(SearchScreen(carContext, session, contactsOnly = true))
        })
        return CarUi.list(header, list.build())
    }

    // Geocoding happens only now, for the one address picked (never the whole address book, §8.3):
    // from the 90-day cache, else one Places Text Search billed to the user's own key.
    private fun pick(c: ContactAddress) = CarUi.pickContact(this, session, c) { options ->
        screenManager.push(SearchScreen(carContext, session, initialQuery = c.name, contactsOnly = true, initialChoice = c to options))
    }
}

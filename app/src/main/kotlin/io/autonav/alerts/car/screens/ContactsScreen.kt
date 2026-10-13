// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.R
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.contacts.ContactAddress
import io.autonav.alerts.contacts.Located
import kotlinx.coroutines.launch

/**
 * Contacts with a postal address to drive to (DESIGN.md §8.3): recently used first (from the
 * `contact_geocode` cache), then A–Z, as many rows as the host allows minus one for the last
 * row, "Search contacts…", which opens the typed contacts search for long address books. A row
 * reads "Name" over "Home · 12 High St · 3.2 mi" (the distance once the address has been
 * located). Tapping a row locates the address and opens the route preview, like a contact row
 * in Search; several matches go to Search's "Which address?" list.
 */
class ContactsScreen(carContext: CarContext, private val session: NavSession) : Screen(carContext), DefaultLifecycleObserver {
    private val deps get() = session.deps
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

    override fun onGetTemplate(): Template {
        val header = Header.Builder().setTitle(carContext.getString(R.string.car_contacts)).setStartHeaderAction(Action.BACK).build()
        val r = rows ?: return ListTemplate.Builder().setHeader(header).setLoading(true).build()
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
        list.addItem(
            Row.Builder().setTitle(carContext.getString(R.string.car_search_contacts))
                .setImage(CarUi.icon(carContext, R.drawable.ic_action_search))
                .setOnClickListener { screenManager.push(SearchScreen(carContext, session, contactsOnly = true)) }
                .build(),
        )
        return ListTemplate.Builder().setHeader(header).setSingleList(list.build()).build()
    }

    private fun pick(c: ContactAddress) {
        lifecycleScope.launch {
            when (val r = deps.contacts().locate(c, deps.location().lastLocation.value)) {
                is Located.Found -> screenManager.push(RoutePreviewScreen(carContext, session, r.destination.copy(address = r.address)))
                is Located.Choose -> screenManager.push(SearchScreen(carContext, session, initialQuery = c.name, contactsOnly = true, initialChoice = c to r.options))
                Located.NotFound -> CarToast.makeText(carContext, carContext.getString(R.string.car_contact_not_found, c.name), CarToast.LENGTH_SHORT).show()
            }
        }
    }
}

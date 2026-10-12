// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.CarToast
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.R
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.contacts.ContactAddress
import io.autonav.alerts.contacts.Located
import io.autonav.alerts.data.PlaceEntity
import io.autonav.alerts.data.PlaceKind
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.places.SearchHit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Places search with recents in the empty state (DESIGN.md §8, §8.2), and contacts' addresses
 * above the Places results (§8.3). [contactsOnly] is the Contacts entry: no Places results.
 */
class SearchScreen(
    carContext: CarContext,
    private val session: NavSession,
    initialQuery: String? = null,
    private val contactsOnly: Boolean = false,
) : Screen(carContext) {
    private val deps get() = session.deps
    private var query = initialQuery.orEmpty()
    private var hits: List<SearchHit> = emptyList()
    private var contacts: List<Pair<ContactAddress, Int?>> = emptyList()
    /** Several places matched a contact's address: pick one (§8.3). */
    private var choosing: Pair<ContactAddress, List<SearchHit>>? = null
    private var recents: List<PlaceEntity> = emptyList()
    private var loading = false
    private var job: Job? = null

    init {
        lifecycleScope.launch { recents = deps.places().recents(6); invalidate() }
        if (query.isNotBlank()) submit(query)
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val choice = choosing
        if (choice != null) {
            val (contact, options) = choice
            options.forEach { h ->
                list.addItem(
                    Row.Builder().setTitle(h.subtitle ?: h.title)
                        .addText("Which address for ${contact.name}?" + (h.distanceM?.let { " · " + CarUi.formatDistance(it, session.imperial) } ?: ""))
                        .setOnClickListener {
                            lifecycleScope.launch {
                                deps.contacts().remember(contact, h)
                                go(Destination(contact.title, h.lat!!, h.lon!!, h.placeId, h.subtitle ?: contact.address))
                            }
                        }
                        .build(),
                )
            }
        } else if (query.isBlank()) {
            recents.forEach { p ->
                list.addItem(
                    Row.Builder().setTitle(p.name)
                        .apply { p.address?.let { addText(it) } }
                        .setImage(CarUi.icon(carContext, when (p.kind) {
                            PlaceKind.HOME -> R.drawable.ic_action_home
                            PlaceKind.WORK -> R.drawable.ic_action_work
                            PlaceKind.FAVOURITE -> R.drawable.ic_action_star
                            PlaceKind.RECENT -> R.drawable.ic_action_recents
                        }))
                        .setOnClickListener { go(Destination(p.name, p.lat, p.lon, p.placeId)) }
                        .build(),
                )
            }
            if (!contactsOnly && deps.contacts().available) {
                list.addItem(
                    Row.Builder().setTitle("Contacts").addText("Drive to a contact's address")
                        .setImage(CarUi.icon(carContext, R.drawable.ic_action_contact))
                        .setOnClickListener { screenManager.push(SearchScreen(carContext, session, contactsOnly = true)) }
                        .build(),
                )
            }
            if (recents.isEmpty() && !deps.contacts().available) list.setNoItemsMessage("Search for a place or address")
            if (contactsOnly) list.setNoItemsMessage("Type or say a contact's name")
        } else {
            contacts.forEach { (c, distance) ->
                list.addItem(
                    Row.Builder().setTitle(c.name)
                        .addText(listOfNotNull(distance?.let { CarUi.formatDistance(it, session.imperial) }, c.label, c.address).joinToString(" · "))
                        .setImage(CarUi.icon(carContext, R.drawable.ic_action_contact))
                        .setOnClickListener { pickContact(c) }
                        .build(),
                )
            }
            hits.forEach { h ->
                list.addItem(
                    Row.Builder().setTitle(h.title)
                        .apply {
                            val sub = listOfNotNull(h.distanceM?.let { CarUi.formatDistance(it, session.imperial) }, h.subtitle).joinToString(" · ")
                            if (sub.isNotBlank()) addText(sub)
                        }
                        .setOnClickListener { pick(h) }
                        .build(),
                )
            }
            if (hits.isEmpty() && contacts.isEmpty() && !loading) list.setNoItemsMessage(if (contactsOnly) "No contacts with an address match" else "No results")
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                query = searchText
                choosing = null
                job?.cancel()
                if (searchText.isBlank()) { hits = emptyList(); contacts = emptyList(); invalidate(); return }
                job = lifecycleScope.launch {
                    // Contacts are local: shown straight away, then Places after the usual pause.
                    contacts = findContacts(searchText)
                    invalidate()
                    if (contactsOnly) return@launch
                    delay(300)
                    hits = deps.places().autocomplete(searchText, deps.location().lastLocation.value)
                    invalidate()
                }
            }

            override fun onSearchSubmitted(searchText: String) = submit(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setInitialSearchText(query)
            .setSearchHint(if (contactsOnly) "Search your contacts" else carContext.getString(R.string.car_search_hint))
            .setShowKeyboardByDefault(contactsOnly || (query.isBlank() && recents.isEmpty()))
            .apply { if (loading) setLoading(true) else setItemList(list.build()) }
            .build()
    }

    private fun submit(text: String) {
        query = text
        job?.cancel()
        loading = true
        invalidate()
        choosing = null
        job = lifecycleScope.launch {
            contacts = findContacts(text)
            hits = if (contactsOnly) emptyList() else deps.places().textSearch(text, deps.location().lastLocation.value)
            loading = false
            invalidate()
        }
    }

    private suspend fun findContacts(text: String): List<Pair<ContactAddress, Int?>> {
        val near = deps.location().lastLocation.value
        // Car lists are short: 3 contacts above the Places results, 6 in the contacts-only search.
        return deps.contacts().search(text, limit = if (contactsOnly) 6 else 3).map { it to deps.contacts().knownDistanceM(it, near) }
    }

    /** A contact's address: located (cached or by Places), or a choice of results (§8.3). */
    private fun pickContact(c: ContactAddress) {
        lifecycleScope.launch {
            when (val r = deps.contacts().locate(c, deps.location().lastLocation.value)) {
                is Located.Found -> go(r.destination.copy(address = r.address))
                is Located.Choose -> { choosing = c to r.options; invalidate() }
                Located.NotFound -> CarToast.makeText(carContext, "Couldn't find ${c.name}'s address", CarToast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pick(h: SearchHit) {
        lifecycleScope.launch {
            val dest = deps.places().resolve(h)
            if (dest == null) {
                CarToast.makeText(carContext, "Couldn't find that place", CarToast.LENGTH_SHORT).show()
            } else {
                go(dest)
            }
        }
    }

    private fun go(dest: Destination) {
        screenManager.push(RoutePreviewScreen(carContext, session, dest))
    }
}

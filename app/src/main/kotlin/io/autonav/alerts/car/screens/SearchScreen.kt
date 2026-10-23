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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.R
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.contacts.ContactAddress
import io.autonav.alerts.contacts.Located
import io.autonav.alerts.data.PlaceEntity
import io.autonav.alerts.data.PlaceKind
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.places.SearchHit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Places search with recents in the empty state (DESIGN.md §8, §8.2), and contacts' addresses
 * above the Places results (§8.3). [contactsOnly] is the typed contacts search: no Places results.
 * [initialChoice] opens straight on "Which address for …?" for a contact whose address matched
 * several places (from `ContactsScreen`).
 *
 * Every text change and submit is logged to `debug-nav` (`Search textChanged/submitted len=`).
 * The host delivers spoken input through the same `onSearchSubmitted` as a typed Enter and
 * doesn't say which it was, so voice isn't a separate line here; Assistant's "navigate to …"
 * arrives as the navigate intent (`Search NavigateIntent`, §9.1).
 */
class SearchScreen(
    carContext: CarContext,
    private val session: NavSession,
    initialQuery: String? = null,
    private val contactsOnly: Boolean = false,
    initialChoice: Pair<ContactAddress, List<SearchHit>>? = null,
) : Screen(carContext), DefaultLifecycleObserver {
    private val deps get() = session.deps
    private var query = initialQuery.orEmpty()
    private var hits: List<SearchHit> = emptyList()
    private var contacts: List<Pair<ContactAddress, Int?>> = emptyList()
    /** Several places matched a contact's address: pick one (§8.3). */
    private var choosing: Pair<ContactAddress, List<SearchHit>>? = initialChoice
    private var recents: List<PlaceEntity> = emptyList()
    /** A submit is running: the list shows "Searching…" until the results arrive. */
    private var searching = false
    /** The last submitted text, for "No results for …". */
    private var submitted: String? = null
    private var job: Job? = null

    init {
        lifecycle.addObserver(this)
        // A contact's "Which address?" list opens as it is: a submit would clear it (§8.3).
        if (query.isNotBlank() && initialChoice == null) submit(query)
    }

    /** Recents reload each time the screen comes back (a Go on the preview adds one, §8). */
    override fun onResume(owner: LifecycleOwner) {
        lifecycleScope.launch { recents = deps.places().recents(6); invalidate() }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val choice = choosing
        if (choice != null) {
            val (contact, options) = choice
            options.forEach { h ->
                list.addItem(
                    Row.Builder().setTitle(h.subtitle ?: h.title)
                        .addText(carContext.getString(R.string.car_which_address, contact.name) + (h.distanceM?.let { " · " + CarUi.formatDistance(it, session.imperial) } ?: ""))
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
                            PlaceKind.FAVOURITE -> R.drawable.ic_action_star
                            PlaceKind.RECENT -> R.drawable.ic_action_recents
                        }))
                        .setOnClickListener { go(Destination(p.name, p.lat, p.lon, p.placeId)) }
                        .build(),
                )
            }
            if (!contactsOnly && deps.contacts().available) {
                // Contacts (DESIGN.md §8.3): the list of contacts with an address, recently used first.
                list.addItem(
                    Row.Builder().setTitle(carContext.getString(R.string.car_contacts)).addText(carContext.getString(R.string.car_contacts_hint))
                        .setImage(CarUi.icon(carContext, R.drawable.ic_action_contact))
                        .setOnClickListener { screenManager.push(ContactsScreen(carContext, session)) }
                        .build(),
                )
            }
            if (recents.isEmpty() && !deps.contacts().available) list.setNoItemsMessage(carContext.getString(R.string.car_search_empty))
            if (contactsOnly) list.setNoItemsMessage(carContext.getString(R.string.car_contacts_type_name))
        } else if (searching) {
            // Never a silent empty list (DESIGN.md §8): the submit is running.
            list.addItem(Row.Builder().setTitle(carContext.getString(R.string.car_searching)).build())
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
            if (hits.isEmpty() && contacts.isEmpty()) {
                list.setNoItemsMessage(
                    if (contactsOnly) carContext.getString(R.string.car_no_contact_results)
                    else carContext.getString(R.string.car_no_results, submitted ?: query),
                )
            }
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                log("textChanged len=${searchText.length}")
                query = searchText
                choosing = null
                searching = false
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

            // Typed Enter and the host's voice input both land here; the host doesn't say which.
            override fun onSearchSubmitted(searchText: String) = submit(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setInitialSearchText(query)
            .setSearchHint(carContext.getString(if (contactsOnly) R.string.car_search_contacts_hint else R.string.car_search_hint))
            .setShowKeyboardByDefault(contactsOnly || (query.isBlank() && recents.isEmpty()))
            .setItemList(list.build())
            .build()
    }

    private fun submit(text: String) {
        log("submitted len=${text.length} contactsOnly=$contactsOnly")
        query = text
        submitted = text
        job?.cancel()
        searching = true
        choosing = null
        invalidate()
        job = lifecycleScope.launch {
            contacts = findContacts(text)
            hits = if (contactsOnly) emptyList() else deps.places().textSearch(text, deps.location().lastLocation.value)
            searching = false
            log("results contacts=${contacts.size} places=${hits.size}")
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
                Located.NotFound -> CarToast.makeText(carContext, carContext.getString(R.string.car_contact_not_found, c.name), CarToast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pick(h: SearchHit) {
        lifecycleScope.launch {
            val dest = deps.places().resolve(h)
            if (dest == null) {
                CarToast.makeText(carContext, carContext.getString(R.string.car_place_not_found), CarToast.LENGTH_SHORT).show()
            } else {
                go(dest)
            }
        }
    }

    private fun go(dest: Destination) {
        screenManager.push(RoutePreviewScreen(carContext, session, dest))
    }

    private fun log(msg: String) = deps.eventLog().record(EventLog.Channel.NAV, "Search", msg)
}

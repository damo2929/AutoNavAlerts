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
import io.autonav.alerts.data.PlaceEntity
import io.autonav.alerts.data.PlaceKind
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.places.SearchHit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Places search with recents in the empty state (DESIGN.md §8, §8.2). */
class SearchScreen(
    carContext: CarContext,
    private val session: NavSession,
    initialQuery: String? = null,
) : Screen(carContext) {
    private val deps get() = session.deps
    private var query = initialQuery.orEmpty()
    private var hits: List<SearchHit> = emptyList()
    private var recents: List<PlaceEntity> = emptyList()
    private var loading = false
    private var job: Job? = null

    init {
        lifecycleScope.launch { recents = deps.places().recents(6); invalidate() }
        if (query.isNotBlank()) submit(query)
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (query.isBlank()) {
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
            if (recents.isEmpty()) list.setNoItemsMessage("Search for a place or address")
        } else {
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
            if (hits.isEmpty() && !loading) list.setNoItemsMessage("No results")
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                query = searchText
                job?.cancel()
                if (searchText.isBlank()) { hits = emptyList(); invalidate(); return }
                job = lifecycleScope.launch {
                    delay(300)
                    hits = deps.places().autocomplete(searchText, deps.location().lastLocation.value)
                    invalidate()
                }
            }

            override fun onSearchSubmitted(searchText: String) = submit(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setInitialSearchText(query)
            .setSearchHint(carContext.getString(R.string.car_search_hint))
            .setShowKeyboardByDefault(query.isBlank() && recents.isEmpty())
            .apply { if (loading) setLoading(true) else setItemList(list.build()) }
            .build()
    }

    private fun submit(text: String) {
        query = text
        job?.cancel()
        loading = true
        invalidate()
        job = lifecycleScope.launch {
            hits = deps.places().textSearch(text, deps.location().lastLocation.value)
            loading = false
            invalidate()
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

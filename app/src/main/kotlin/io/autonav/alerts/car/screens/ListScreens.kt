// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.CarToast
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.R
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.data.PlaceEntity
import io.autonav.alerts.data.PlaceKind
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.nav.Destination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** More: Recents, POI layers, mute, diagnostics (DESIGN.md §8). */
class MoreScreen(carContext: CarContext, private val session: NavSession) : Screen(carContext) {
    private var mutedUntil = 0L

    init {
        lifecycleScope.launch { mutedUntil = session.deps.settings().current().mutedUntilMs; invalidate() }
    }

    override fun onGetTemplate(): Template {
        val muted = mutedUntil > System.currentTimeMillis()
        val stats = session.deps.location().stats.value
        val list = ItemList.Builder()
            .addItem(Row.Builder().setTitle("Recents").setImage(CarUi.icon(carContext, R.drawable.ic_action_recents))
                .setOnClickListener { screenManager.push(RecentsScreen(carContext, session)) }.build())
            .addItem(Row.Builder().setTitle("POI layers").setImage(CarUi.icon(carContext, R.drawable.ic_action_layers))
                .setOnClickListener { screenManager.push(PoiLayersScreen(carContext, session)) }.build())
            .addItem(Row.Builder().setTitle(if (muted) "Unmute alerts" else "Mute alerts for 30 min")
                .setImage(CarUi.icon(carContext, if (muted) R.drawable.ic_action_volume_on else R.drawable.ic_action_volume_off))
                .setOnClickListener {
                    lifecycleScope.launch {
                        val until = if (muted) 0L else System.currentTimeMillis() + 30 * 60_000L
                        session.deps.settings().update { it.copy(mutedUntilMs = until) }
                        mutedUntil = until
                        invalidate()
                    }
                }.build())
            .apply {
                // Only appears if the Navigation SDK ever supports reporting on Android Auto (§16 #21).
                if (session.surface.incidentReportingSupported) addItem(
                    Row.Builder().setTitle("Report to Google Maps")
                        .addText(if (session.surface.incidentReportingAvailableNow) "Crash, slowdown, hazard…" else "Available during navigation")
                        .setImage(CarUi.icon(carContext, R.drawable.ic_action_report))
                        .setOnClickListener {
                            screenManager.pop()
                            if (!session.surface.showIncidentPanel()) {
                                androidx.car.app.CarToast.makeText(carContext, "Reporting isn't available here right now", androidx.car.app.CarToast.LENGTH_SHORT).show()
                            }
                        }.build(),
                )
            }
            .addItem(Row.Builder().setTitle("GPS")
                .addText("Requested ${stats.requestedHz} Hz · actual ${"%.1f".format(stats.actualHz)} Hz · ${"%.0f".format(stats.speedMph)} mph")
                .addText("Location from " + (if (stats.locationFromCar) "car" else "phone") + " · speed from " + (if (stats.speedFromCar) "car" else "phone"))
                .build())
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("More").setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }
}

/** Show/hide each POI set, and switch its alerts on or off. */
class PoiLayersScreen(carContext: CarContext, private val session: NavSession) : Screen(carContext) {
    private var sets: List<PoiSetEntity>? = null

    init {
        lifecycleScope.launch { sets = session.deps.pois().activeSets.first(); invalidate() }
    }

    override fun onGetTemplate(): Template {
        val header = Header.Builder().setTitle("POI layers").setStartHeaderAction(Action.BACK).build()
        val s = sets ?: return ListTemplate.Builder().setHeader(header).setLoading(true).build()
        val list = ItemList.Builder()
        if (s.isEmpty()) list.setNoItemsMessage("No POI sets yet. Import or sync on your phone.")
        val limit = runCatching {
            carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
                .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        s.take(limit).forEach { set ->
            list.addItem(
                Row.Builder().setTitle(set.name)
                    .addText("${set.poiCount} POIs · alerts ${if (set.alertsEnabled) "on" else "off"}")
                    .setToggle(Toggle.Builder { on -> update(set.copy(enabled = on)) }.setChecked(set.enabled).build())
                    .build(),
            )
        }
        return ListTemplate.Builder().setHeader(header).setSingleList(list.build()).build()
    }

    private fun update(set: PoiSetEntity) {
        lifecycleScope.launch {
            session.deps.pois().updateSet(set)
            sets = sets?.map { if (it.id == set.id) set else it }
            invalidate()
        }
    }
}

/** Recents and favourites, with delete on the car screen (DESIGN.md §8.2). */
class RecentsScreen(carContext: CarContext, private val session: NavSession) : Screen(carContext) {
    private var places: List<PlaceEntity>? = null

    init { reload() }

    private fun reload() {
        lifecycleScope.launch { places = session.deps.places().recents(50); invalidate() }
    }

    override fun onGetTemplate(): Template {
        val header = Header.Builder().setTitle("Recents").setStartHeaderAction(Action.BACK).build()
        val p = places ?: return ListTemplate.Builder().setHeader(header).setLoading(true).build()
        val rowActions = carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_6
        val list = ItemList.Builder()
        if (p.isEmpty()) list.setNoItemsMessage("No recent places")
        p.forEach { place ->
            val row = Row.Builder().setTitle(place.name)
                .apply { place.address?.let { addText(it) } }
                .setOnClickListener {
                    if (rowActions) navigate(place) else screenManager.push(PlaceDetailScreen(carContext, session, place) { reload() })
                }
            if (rowActions && place.kind == PlaceKind.RECENT) {
                row.addAction(CarUi.action(carContext, R.drawable.ic_action_delete) { delete(place) })
            }
            list.addItem(row.build())
        }
        val b = ListTemplate.Builder().setHeader(header).setSingleList(list.build())
        if (p.any { it.kind == PlaceKind.RECENT }) {
            b.addAction(CarUi.textAction("Clear all") {
                screenManager.push(ConfirmScreen(carContext, "Clear all recents?") {
                    lifecycleScope.launch { session.deps.places().clearRecents(); reload() }
                })
            })
        }
        return b.build()
    }

    private fun navigate(p: PlaceEntity) {
        screenManager.push(RoutePreviewScreen(carContext, session, Destination(p.name, p.lat, p.lon, p.placeId)))
    }

    private fun delete(p: PlaceEntity) {
        lifecycleScope.launch {
            session.deps.places().delete(p)
            reload()
            CarToast.makeText(carContext, "Removed ${p.name}", CarToast.LENGTH_SHORT).show()
        }
    }
}

/** Fallback for older car hosts without row actions: Navigate / Favourite / Remove. */
class PlaceDetailScreen(
    carContext: CarContext,
    private val session: NavSession,
    private val place: PlaceEntity,
    private val onChanged: () -> Unit,
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val b = MessageTemplate.Builder(place.address ?: place.name)
            .setHeader(Header.Builder().setTitle(place.name).setStartHeaderAction(Action.BACK).build())
            .addAction(CarUi.textAction("Navigate") {
                screenManager.push(RoutePreviewScreen(carContext, session, Destination(place.name, place.lat, place.lon, place.placeId)))
            })
        if (place.kind == PlaceKind.RECENT) {
            b.addAction(CarUi.textAction("Remove") {
                lifecycleScope.launch { session.deps.places().delete(place); onChanged(); screenManager.pop() }
            })
        }
        return b.build()
    }
}

class ConfirmScreen(carContext: CarContext, private val question: String, private val onYes: () -> Unit) : Screen(carContext) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder(question)
        .setHeader(Header.Builder().setTitle("Confirm").setStartHeaderAction(Action.BACK).build())
        .addAction(CarUi.textAction("Yes") { onYes(); screenManager.pop() })
        .addAction(CarUi.textAction("No") { screenManager.pop() })
        .build()
}

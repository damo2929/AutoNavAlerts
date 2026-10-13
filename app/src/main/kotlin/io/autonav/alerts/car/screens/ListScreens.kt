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
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.R
import io.autonav.alerts.alerts.NavTts
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.core.alerts.SpeechScheduler
import io.autonav.alerts.core.alerts.Verdict
import io.autonav.alerts.data.PlaceEntity
import io.autonav.alerts.data.PlaceKind
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.nav.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * More: Recents, POI layers, mute, route options, satellite, Alerts and GPS (DESIGN.md §8). The GPS
 * and Alerts rows are live: a once-a-second ticker checks their text while the screen is visible and
 * re-sends the template **only when a line has changed** (the row titles don't change, so each
 * refresh is a host "refresh", not a new template). Re-sending an identical template made the host
 * animate every row below the first change point once a second, a visible "shake" on a parked car
 * (owner, bench, 2026-10-13).
 */
class MoreScreen(carContext: CarContext, private val session: NavSession) : Screen(carContext) {
    private var mutedUntil = 0L
    private var satellite = false
    private var liveKey: List<String> = emptyList()
    private var ticker: Job? = null

    init {
        lifecycleScope.launch {
            val s = session.deps.settings().current()
            mutedUntil = s.mutedUntilMs
            satellite = s.satelliteMap
            invalidate()
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                ticker?.cancel()
                ticker = lifecycleScope.launch {
                    while (isActive) {
                        delay(1_000)
                        if (liveTexts() != liveKey) invalidate()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                ticker?.cancel()
                ticker = null
            }
        })
    }

    /** The lines that can change while the screen is open: mute state, the Alerts row, the GPS row. */
    private fun liveTexts(): List<String> {
        val muted = mutedUntil > System.currentTimeMillis()
        val stats = session.deps.location().stats.value
        val alerts = session.deps.alerts()
        val aheadCount = alerts.verdicts.value.count { it.value == Verdict.AHEAD_OK }
        val awayCount = alerts.facingAway.value.size
        return listOf(
            if (muted) "Unmute alerts" else "Mute alerts for 30 min",
            "Ahead $aheadCount · facing away $awayCount",
            "Last: ${alerts.trace.lastStage ?: "no stage yet"} · ${alerts.trace.lastSuppress ?: "no suppress yet"}",
            "Requested ${stats.requestedHz} Hz · actual ${"%.1f".format(stats.actualHz)} Hz · " +
                "heading ${stats.headingDeg?.let { "%.0f°".format(it) } ?: "–"} (${stats.headingSrc.ifEmpty { "none" }}" +
                "${stats.headingAccDeg?.let { " ±%.0f°".format(it) } ?: ""})",
            "Engine ${"%.0f".format(stats.speedMph)} mph (${stats.speedSrc.ifEmpty { "none" }}) · " +
                "dial ${stats.dialSpeedMph?.let { "%.0f mph (car)".format(it) } ?: "${"%.0f".format(stats.speedMph)} mph (${if (stats.speedFromCar) "car" else "phone"})"} · " +
                "location ${if (stats.locationFromCar) "car" else "phone"}" +
                (stats.accuracyM?.let { " ±%.0f m".format(it) } ?: ""),
        )
    }

    override fun onGetTemplate(): Template {
        val muted = mutedUntil > System.currentTimeMillis()
        val live = liveTexts().also { liveKey = it }
        val list = ItemList.Builder()
            .addItem(Row.Builder().setTitle("Recents").setImage(CarUi.icon(carContext, R.drawable.ic_action_recents))
                .setOnClickListener { screenManager.push(RecentsScreen(carContext, session)) }.build())
            .addItem(Row.Builder().setTitle("POI layers").setImage(CarUi.icon(carContext, R.drawable.ic_action_layers))
                .setOnClickListener { screenManager.push(PoiLayersScreen(carContext, session)) }.build())
            .addItem(Row.Builder().setTitle(live[0])
                .setImage(CarUi.icon(carContext, if (muted) R.drawable.ic_action_volume_on else R.drawable.ic_action_volume_off))
                .setOnClickListener {
                    lifecycleScope.launch {
                        val until = if (muted) 0L else System.currentTimeMillis() + 30 * 60_000L
                        session.deps.settings().update { it.copy(mutedUntilMs = until) }
                        mutedUntil = until
                        invalidate()
                    }
                }.build())
            .addItem(Row.Builder().setTitle("Route options").addText("Avoid tolls, energy-efficient EV routes")
                .setImage(CarUi.icon(carContext, R.drawable.ic_action_navigate))
                .setOnClickListener { screenManager.push(RouteOptionsScreen(carContext, session)) }.build())
            .addItem(Row.Builder().setTitle("Satellite map")
                .setImage(CarUi.icon(carContext, R.drawable.ic_action_layers))
                .setToggle(Toggle.Builder { on ->
                    lifecycleScope.launch { session.deps.settings().update { it.copy(satelliteMap = on) } }
                }.setChecked(satellite).build())
                .build())
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
            .addItem(Row.Builder().setTitle("Alerts")
                .addText(live[1])
                .addText(live[2])
                .setImage(CarUi.icon(carContext, R.drawable.ic_action_volume_on))
                .setOnClickListener { screenManager.push(AlertsRecordsScreen(carContext, session.deps.tts())) }
                .build())
            .addItem(Row.Builder().setTitle("GPS")
                .addText(live[3])
                .addText(live[4])
                .build())
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("More").setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }
}

/** Avoid tolls and energy-efficient EV routes (DESIGN.md §8.3a); used from the next route. */
class RouteOptionsScreen(carContext: CarContext, private val session: NavSession) : Screen(carContext) {
    private var settings: io.autonav.alerts.settings.AppSettings? = null

    init {
        lifecycleScope.launch { settings = session.deps.settings().current(); invalidate() }
    }

    override fun onGetTemplate(): Template {
        val header = Header.Builder().setTitle("Route options").setStartHeaderAction(Action.BACK).build()
        val s = settings ?: return ListTemplate.Builder().setHeader(header).setLoading(true).build()
        val list = ItemList.Builder()
            .addItem(Row.Builder().setTitle("Avoid tolls").addText("Where there's a way round")
                .setToggle(Toggle.Builder { on -> update { it.copy(avoidTolls = on) } }.setChecked(s.avoidTolls).build()).build())
            .addItem(Row.Builder().setTitle("Energy-efficient routes (EV)").addText("Least energy for an electric car")
                .setToggle(Toggle.Builder { on -> update { it.copy(evEfficientRoutes = on) } }.setChecked(s.evEfficientRoutes).build()).build())
        return ListTemplate.Builder().setHeader(header).setSingleList(list.build()).build()
    }

    private fun update(change: (io.autonav.alerts.settings.AppSettings) -> io.autonav.alerts.settings.AppSettings) {
        lifecycleScope.launch {
            session.deps.settings().update(change)
            settings = session.deps.settings().current()
            invalidate()
        }
    }
}

/**
 * Show/hide each POI set, and switch its alerts on or off. Each row carries the set's own map icon
 * (its default icon, the one its markers use unless a category overrides it), so the list reads like
 * the map (owner, 2026-10-13).
 */
class PoiLayersScreen(carContext: CarContext, private val session: NavSession) : Screen(carContext) {
    private var sets: List<PoiSetEntity>? = null
    private var icons: Map<String, CarIcon> = emptyMap()

    init {
        lifecycleScope.launch {
            val s = session.deps.pois().activeSets.first()
            icons = withContext(Dispatchers.IO) {
                val px = (ICON_DP * carContext.resources.displayMetrics.density).toInt().coerceAtLeast(48)
                s.map { it.defaultIconId }.distinct().associateWith { CarUi.icon(session.deps.icons().bitmap(it, px)) }
            }
            sets = s
            invalidate()
        }
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
                    .apply { icons[set.defaultIconId]?.let { setImage(it, Row.IMAGE_TYPE_SMALL) } }
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

    private companion object { const val ICON_DP = 48 }
}

/**
 * The speech scheduler's decisions, newest first (DESIGN.md §12.3): what was spoken, replaced,
 * dropped as stale, interrupted, timed out, failed or muted, and how long each waited. A snapshot
 * when opened; Refresh takes a new one.
 */
class AlertsRecordsScreen(carContext: CarContext, private val tts: NavTts) : Screen(carContext) {
    private var records: List<SpeechScheduler.Record> = tts.records.value

    override fun onGetTemplate(): Template {
        val header = Header.Builder().setTitle("Alerts").setStartHeaderAction(Action.BACK).build()
        val limit = runCatching {
            carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
                .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        val list = ItemList.Builder()
        if (records.isEmpty()) list.setNoItemsMessage("No alerts spoken yet")
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
        records.asReversed().take(limit).forEach { r ->
            val u = r.utterance
            list.addItem(
                Row.Builder().setTitle(if (u.kind == io.autonav.alerts.core.alerts.AlertKind.LIMIT_CHANGE) "Speed limit change ${r.outcome}" else "${u.kind} poi ${u.poiId} ${r.outcome}")
                    .addText(
                        fmt.format(java.util.Date(r.atMs)) + " · waited " + (r.waitedMs?.let { "$it ms" } ?: "–") +
                            (r.byKind?.let { " · by $it" } ?: "") + (r.detail?.let { " · $it" } ?: ""),
                    )
                    .build(),
            )
        }
        return ListTemplate.Builder().setHeader(header).setSingleList(list.build())
            .addAction(CarUi.textAction("Refresh") { records = tts.records.value; invalidate() })
            .build()
    }
}

/** Recents and favourites, with delete on the car screen (DESIGN.md §8.2); reloaded whenever it comes back on top. */
class RecentsScreen(carContext: CarContext, private val session: NavSession) : Screen(carContext) {
    private var places: List<PlaceEntity>? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = reload()
        })
    }

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

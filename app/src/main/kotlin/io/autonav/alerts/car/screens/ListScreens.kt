// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.R
import io.autonav.alerts.alerts.NavTts
import io.autonav.alerts.car.CarScreenHost
import io.autonav.alerts.car.screens.CarUi.toDestination
import io.autonav.alerts.core.alerts.SpeechScheduler
import io.autonav.alerts.core.alerts.Verdict
import io.autonav.alerts.data.PlaceEntity
import io.autonav.alerts.data.PlaceKind
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
 *
 * Pushed from Home's strip; a `ListTemplate`, so the map stays behind. Main thread throughout
 * (`lifecycleScope`). Hard rule 3 (no shake) is the whole point of the ticker's compare-first
 * design; the mute row here is the one Mute on the car screen (the banner has no buttons, §12.1,
 * hard rule 2).
 */
class MoreScreen(carContext: CarContext, private val session: CarScreenHost) : Screen(carContext) {
    private var mutedUntil = 0L
    private var satellite = false
    /** The live lines as last sent, compared by the ticker before any re-send. */
    private var liveKey: List<String> = emptyList()
    private var ticker: Job? = null

    init {
        // Follows the settings, so a change made elsewhere (the map's pill, the phone) shows here,
        // and only a change re-sends the template (no shake, DESIGN.md §8).
        session.deps.settings().settings.onEach { s ->
            if (s.mutedUntilMs == mutedUntil && s.satelliteMap == satellite) return@onEach
            mutedUntil = s.mutedUntilMs
            satellite = s.satelliteMap
            invalidate()
        }.launchIn(lifecycleScope)
        // The ticker runs only while the screen is showing (STARTED), and compares before it re-sends.
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

    /** The More list; the live lines sent are remembered in [liveKey] for the ticker's comparison. */
    override fun onGetTemplate(): Template {
        val muted = mutedUntil > System.currentTimeMillis()
        val live = liveTexts().also { liveKey = it }
        val list = ItemList.Builder()
            .addItem(CarUi.row(carContext, "Recents", R.drawable.ic_action_recents) { screenManager.push(RecentsScreen(carContext, session)) })
            .addItem(CarUi.row(carContext, "POI layers", R.drawable.ic_action_layers) { screenManager.push(PoiLayersScreen(carContext, session)) })
            .addItem(CarUi.row(carContext, live[0], if (muted) R.drawable.ic_action_volume_on else R.drawable.ic_action_volume_off) {
                lifecycleScope.launch {
                    // Mute silences speech only for 30 min; banners and cards still show (§12.3).
                    val until = if (muted) 0L else System.currentTimeMillis() + 30 * 60_000L
                    session.deps.settings().update { it.copy(mutedUntilMs = until) }
                    mutedUntil = until
                    invalidate()
                }
            })
            .addItem(CarUi.row(carContext, "Route options", R.drawable.ic_action_navigate, "Avoid tolls, energy-efficient EV routes") {
                screenManager.push(RouteOptionsScreen(carContext, session))
            })
            .addItem(CarUi.row("Satellite map", image = CarUi.icon(carContext, R.drawable.ic_action_layers), toggle = Toggle.Builder { on ->
                // Kept here at once: the next template must not show the old state (it snapped back).
                satellite = on
                lifecycleScope.launch { session.deps.settings().update { it.copy(satelliteMap = on) } }
            }.setChecked(satellite).build()))
            .addItem(CarUi.row(carContext, "Alerts", R.drawable.ic_action_volume_on, live[1], live[2]) {
                screenManager.push(AlertsRecordsScreen(carContext, session.deps.tts()))
            })
            .addItem(CarUi.row("GPS", live[3], live[4]))
        return CarUi.list(CarUi.header("More"), list.build())
    }
}

/**
 * A list screen whose rows come from one suspending [load] (DESIGN.md §8): the host's own spinner under
 * [title] until [items] are in, then the rows and any [actions], with [emptyText] when a loaded list has
 * none. Re-sent once per load (hard rule 3). [reload] runs on every resume when [reloadOnResume] (Recents
 * and Contacts, whose order a Go or a pick changes); the other screens call it when they want it. Main
 * thread (`lifecycleScope`); the repositories do their own I/O.
 */
abstract class LoadingListScreen<T : Any>(carContext: CarContext, reloadOnResume: Boolean = false) : Screen(carContext) {
    /** What the rows are built from; null until the first load has finished. */
    protected var items: T? = null
    protected abstract val title: String
    /** The host's message for an empty list; a screen whose [items] isn't a list never needs one. */
    protected open val emptyText: String? = null

    init {
        if (reloadOnResume) lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = reload()
        })
    }

    protected abstract suspend fun load(): T
    protected abstract fun ItemList.Builder.addRows(items: T)
    protected open fun actions(items: T): List<Action> = emptyList()

    protected fun reload() {
        lifecycleScope.launch { items = load(); invalidate() }
    }

    /** The rows and actions, or a loading template before the first load. */
    override fun onGetTemplate(): Template {
        val header = CarUi.header(title)
        val t = items ?: return CarUi.loading(header)
        val list = ItemList.Builder()
        if (t is Collection<*> && t.isEmpty()) emptyText?.let(list::setNoItemsMessage)
        list.addRows(t)
        return CarUi.list(header, list.build(), *actions(t).toTypedArray())
    }
}

/**
 * Avoid tolls and energy-efficient EV routes (DESIGN.md §8.3a); used from the next route. Pushed
 * from More; a `ListTemplate` with a toggle per option. The same two settings as the phone's
 * Settings → Routing, so both always agree. Main thread; the settings update suspends on its own
 * dispatcher. Re-sent only after a load or a change (hard rule 3).
 */
class RouteOptionsScreen(carContext: CarContext, private val session: CarScreenHost) : LoadingListScreen<AppSettings>(carContext) {
    override val title = "Route options"

    init {
        reload()
    }

    override suspend fun load(): AppSettings = session.deps.settings().current()

    /** The two toggles. */
    override fun ItemList.Builder.addRows(items: AppSettings) {
        addItem(CarUi.row("Avoid tolls", "Where there's a way round", toggle = Toggle.Builder { on -> update { it.copy(avoidTolls = on) } }.setChecked(items.avoidTolls).build()))
        addItem(CarUi.row("Energy-efficient routes (EV)", "Least energy for an electric car",
            toggle = Toggle.Builder { on -> update { it.copy(evEfficientRoutes = on) } }.setChecked(items.evEfficientRoutes).build()))
    }

    // Written to the store first, then read back, so the toggle shows what was actually saved.
    private fun update(change: (AppSettings) -> AppSettings) {
        lifecycleScope.launch {
            session.deps.settings().update(change)
            items = session.deps.settings().current()
            invalidate()
        }
    }
}

/**
 * Show/hide each POI set, and switch its alerts on or off. Each row carries the set's own map icon
 * (its default icon, the one its markers use unless a category overrides it), so the list reads like
 * the map (owner, 2026-10-13).
 *
 * The POI layers screen of DESIGN.md §8 (a `ListTemplate` pushed from More; the map stays
 * behind). The icon bitmaps are rendered on `Dispatchers.IO` (§2), the template on the main
 * thread. A toggle writes the set through `PoiRepository`, which the marker layer and the alert
 * engine both read, so the map follows at the next fix. Re-sent only after the load or a toggle
 * (hard rule 3).
 */
class PoiLayersScreen(carContext: CarContext, private val session: CarScreenHost) : LoadingListScreen<List<PoiSetEntity>>(carContext) {
    override val title = "POI layers"
    override val emptyText = "No POI sets yet. Import or sync on your phone."
    /** One car icon per distinct default icon id, so sets sharing an icon share the bitmap. */
    private var icons: Map<String, CarIcon> = emptyMap()

    init {
        reload()
    }

    override suspend fun load(): List<PoiSetEntity> {
        val s = session.deps.pois().activeSets.first()
        icons = withContext(Dispatchers.IO) {
            // 48 dp, never under 48 px, so the host's small-image slot doesn't upscale a tiny bitmap.
            val px = (ICON_DP * carContext.resources.displayMetrics.density).toInt().coerceAtLeast(48)
            s.map { it.defaultIconId }.distinct().associateWith { CarUi.icon(session.deps.icons().bitmap(it, px)) }
        }
        return s
    }

    /** One row per set up to the host's list limit, with its icon, counts and the on/off toggle. */
    override fun ItemList.Builder.addRows(items: List<PoiSetEntity>) {
        items.take(CarUi.listLimit(carContext)).forEach { set ->
            addItem(CarUi.row(set.name, "${set.poiCount} POIs · alerts ${if (set.alertsEnabled) "on" else "off"}", image = icons[set.defaultIconId],
                toggle = Toggle.Builder { on -> update(set.copy(enabled = on)) }.setChecked(set.enabled).build()))
        }
    }

    // The local copy is patched rather than re-read, so the toggle doesn't snap back before Room has caught up.
    private fun update(set: PoiSetEntity) {
        lifecycleScope.launch {
            session.deps.pois().updateSet(set)
            items = items?.map { if (it.id == set.id) set else it }
            invalidate()
        }
    }

    private companion object { const val ICON_DP = 48 }
}

/**
 * The speech scheduler's decisions, newest first (DESIGN.md §12.3): what was spoken, replaced,
 * dropped as stale, interrupted, timed out, failed or muted, and how long each waited. A snapshot
 * when opened; Refresh takes a new one.
 *
 * The car side of the 50-entry speech record ring (the phone's Diagnostics shows the same one).
 * Pushed from More's Alerts row; a `ListTemplate`. A snapshot rather than a collector, so the
 * list never changes under the driver's finger and nothing re-sends on its own (hard rule 3).
 * Main thread only.
 */
class AlertsRecordsScreen(carContext: CarContext, private val tts: NavTts) : LoadingListScreen<List<SpeechScheduler.Record>>(carContext) {
    override val title = "Alerts"
    override val emptyText = "No alerts spoken yet"

    init {
        // Taken as the screen is made, so the first template is the list, never a spinner.
        items = tts.records.value
    }

    override suspend fun load(): List<SpeechScheduler.Record> = tts.records.value

    /** The newest records up to the host's list limit: kind, POI, outcome, time, wait and detail. */
    override fun ItemList.Builder.addRows(items: List<SpeechScheduler.Record>) {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
        // Limit changes and directions are their own kinds, never shown as a camera stage (§8.4a, §12.3).
        items.asReversed().take(CarUi.listLimit(carContext)).forEach { r ->
            val u = r.utterance
            val title = when (u.kind) {
                io.autonav.alerts.core.alerts.AlertKind.LIMIT_CHANGE -> "Speed limit change ${r.outcome}"
                io.autonav.alerts.core.alerts.AlertKind.GUIDANCE -> "Directions ${r.outcome}"
                else -> "${u.kind} poi ${u.poiId} ${r.outcome}"
            }
            addItem(CarUi.row(title,
                fmt.format(java.util.Date(tts.wallMs(r.atMs))) + " · waited " + (r.waitedMs?.let { "$it ms" } ?: "–") +
                    (r.byKind?.let { " · by $it" } ?: "") + (r.detail?.let { " · $it" } ?: "")))
        }
    }

    override fun actions(items: List<SpeechScheduler.Record>): List<Action> = listOf(CarUi.textAction("Refresh") { reload() })
}

/**
 * Recents and favourites, with delete on the car screen (DESIGN.md §8.2); reloaded whenever it comes back on top.
 *
 * Pushed from Home's strip (free drive only) and from More; a `ListTemplate`. From car API 6 a
 * row carries its own delete action and a tap navigates; below that a tap opens
 * [PlaceDetailScreen], which has Navigate and Remove. Favourites can't be removed here (§8.2:
 * Unfavourite or the phone app). Main thread; the place repository does its Room work on its
 * own dispatcher (§2). Re-sent once per load (hard rule 3).
 */
class RecentsScreen(carContext: CarContext, private val session: CarScreenHost) : LoadingListScreen<List<PlaceEntity>>(carContext, reloadOnResume = true) {
    override val title = "Recents"
    override val emptyText = "No recent places"

    // On resume, not create: a Go on the preview adds a recent, and the host's limit shows the newest first.
    override suspend fun load(): List<PlaceEntity> = session.deps.places().recents(50)

    /** The rows: a tap navigates (car API 6, with a delete action on each recent) or opens the place detail. */
    override fun ItemList.Builder.addRows(items: List<PlaceEntity>) {
        // Row.addAction needs car API 6 (§8.2 "Deleting from the car screen").
        val rowActions = carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_6
        items.forEach { place ->
            val delete = if (rowActions && place.kind == PlaceKind.RECENT) CarUi.action(carContext, R.drawable.ic_action_delete) { delete(place) } else null
            addItem(CarUi.row(place.name, place.address, action = delete) {
                if (rowActions) navigate(place) else screenManager.push(PlaceDetailScreen(carContext, session, place) { reload() })
            })
        }
    }

    /** Clear all, when any recent exists. */
    override fun actions(items: List<PlaceEntity>): List<Action> = listOfNotNull(
        CarUi.textAction("Clear all") {
            screenManager.push(ConfirmScreen(carContext, "Clear all recents?") {
                lifecycleScope.launch { session.deps.places().clearRecents(); reload() }
            })
        }.takeIf { items.any { it.kind == PlaceKind.RECENT } },
    )

    private fun navigate(p: PlaceEntity) {
        screenManager.push(RoutePreviewScreen(carContext, session, p.toDestination()))
    }

    private fun delete(p: PlaceEntity) {
        lifecycleScope.launch {
            session.deps.places().delete(p)
            reload()
            CarToast.makeText(carContext, "Removed ${p.name}", CarToast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Fallback for older car hosts without row actions: Navigate / Favourite / Remove.
 *
 * The place-detail screen of DESIGN.md §8.2 for car API < 6, pushed from [RecentsScreen]. A
 * `MessageTemplate` with the address as its text. [onChanged] tells the recents list to reload
 * after a removal. Main thread; one template, never re-sent.
 */
class PlaceDetailScreen(
    carContext: CarContext,
    private val session: CarScreenHost,
    private val place: PlaceEntity,
    private val onChanged: () -> Unit,
) : Screen(carContext) {
    /** Navigate always; Remove only for a recent (favourites are removed on the phone, §8.2). */
    override fun onGetTemplate(): Template {
        val b = MessageTemplate.Builder(place.address ?: place.name)
            .setHeader(CarUi.header(place.name))
            .addAction(CarUi.textAction("Navigate") {
                screenManager.push(RoutePreviewScreen(carContext, session, place.toDestination()))
            })
        if (place.kind == PlaceKind.RECENT) {
            b.addAction(CarUi.textAction("Remove") {
                lifecycleScope.launch { session.deps.places().delete(place); onChanged(); screenManager.pop() }
            })
        }
        return b.build()
    }
}

/**
 * A Yes / No question on a `MessageTemplate` (DESIGN.md §8.2 "Clear all recents", "with a
 * confirmation step"). Pushed by the caller; pops itself on either answer and runs [onYes] on
 * Yes, on the main thread. A couple of taps, as the design asks; nothing to type.
 */
class ConfirmScreen(carContext: CarContext, private val question: String, private val onYes: () -> Unit) : Screen(carContext) {
    /** The question with Yes and No; the header's Back is a No as well. */
    override fun onGetTemplate(): Template = MessageTemplate.Builder(question)
        .setHeader(CarUi.header("Confirm"))
        .addAction(CarUi.textAction("Yes") { onYes(); screenManager.pop() })
        .addAction(CarUi.textAction("No") { screenManager.pop() })
        .build()
}

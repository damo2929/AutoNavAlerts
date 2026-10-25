// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarText
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.App
import io.autonav.alerts.BuildConfig
import io.autonav.alerts.R
import io.autonav.alerts.car.CarScreenHost
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.nav.RouteOption
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Route preview with ETA and distance, then Go (DESIGN.md §8). Uses RoutePreviewNavigationTemplate,
 * deprecated in Car App 1.7 in favour of MapWithContentTemplate, which needs car API level 7; the
 * older template keeps the preview working on older head units.
 *
 * Back (the header's `Action.BACK`, the error screen's Back, or the session ending with the
 * preview up) reaches `onDestroy`, which abandons the preview unless Go was pressed: the
 * destination and its route are cleared, or while guiding the guided route is put back (§8). While the screen is on screen `MapSurface.previewShowing`
 * is set, so the follow watchdog leaves the route overview alone (§8, §16 row 22).
 *
 * Pushed by Search, Recents, Contacts, the place detail and a navigate intent (§9.1), always with
 * a resolved [destination]; it pops to the root on Go. With the EV option on (§8.3a) the rows are
 * the fastest and the energy-efficient route with their traffic, the Go button names the selected
 * one, and selecting a row sets that route in the SDK so the map draws it. Main thread
 * (`lifecycleScope`); `GuidanceController` does the routing and the Routes API call off it.
 *
 * `previewShowing` also lifts the zoom lock while the overview is up (§8 "One view with and
 * without a route"). Hard rule 2: the one Go is the host's navigate action; there is no Stop here
 * either. The template is re-sent on load, on a selection and on error only (hard rule 3).
 */
@Suppress("DEPRECATION")
class RoutePreviewScreen(
    carContext: CarContext,
    private val session: CarScreenHost,
    private val destination: Destination,
) : Screen(carContext), DefaultLifecycleObserver {
    private val deps get() = session.deps
    /** The SDK's route (time, distance, traffic delay), or null when none was found. */
    private var route: RouteOption? = null
    /** Why the destination couldn't be set, shown in place of the preview. */
    private var error: String? = null
    private var loading = true
    /** Go was pressed: the preview must not be abandoned in `onDestroy`. */
    private var started = false

    init {
        lifecycle.addObserver(this)
        // The route is asked for at once, while the host shows the loading preview; Back during this
        // still reaches onDestroy, which clears a route that finishes afterwards (§8, code review 2026-10-23).
        lifecycleScope.launch {
            error = deps.guidance().setDestination(destination)
            route = deps.guidance().currentRoute()
            loading = false
            invalidate()
        }
    }

    /** On screen: the watchdog and the zoom lock stand down for the SDK's route overview (§8). */
    override fun onStart(owner: LifecycleOwner) {
        session.surface.previewShowing = true
        showing = this
    }

    /** Hidden (another screen over it, or leaving): the watchdog and the lock come back. */
    override fun onStop(owner: LifecycleOwner) {
        session.surface.previewShowing = false
        if (showing === this) showing = null
    }

    /** Leaving for good: without Go, the preview is abandoned (the route goes, or the guided one is put back). */
    override fun onDestroy(owner: LifecycleOwner) {
        // Hidden for good: the same stand-down as onStop.
        onStop(owner)
        // Back, or the session ending, without Go: the preview's route goes; while guiding, the
        // guided route is put back (DESIGN.md §8, owner 2026-10-13).
        if (!started) deps.guidance().abandonPreview()
    }

    /**
     * The error message, the loading preview, "Couldn't find a route there", or the preview with
     * one row (the SDK's route) or the fastest / energy-efficient pair (§8.3a) and Go.
     */
    override fun onGetTemplate(): Template {
        error?.let { return message(it) }
        val b = RoutePreviewNavigationTemplate.Builder().setHeader(CarUi.header(destination.title))
        if (loading) return b.setLoading(true).build()
        val r = route ?: return message("Couldn't find a route there")
        val items = ItemList.Builder()
        val choices = deps.guidance().choices.value
        if (choices.isEmpty()) {
            // One SDK route. The note says why there's no energy-efficient one when that was asked for
            // ("Fastest route (energy-efficient routing isn't available: …)", §8.3a "never a dead end").
            val note = deps.guidance().routeNote.value?.let { " · $it" }.orEmpty()
            items.addItem(routeRow(r.seconds, r.meters, trafficText(r.delaySeverity) + note))
            items.setOnSelectedListener { }
            items.setSelectedIndex(0)
        } else {
            // Fastest and energy-efficient (DESIGN.md §8.3a): the driver picks; the map shows the selected one.
            // Android Auto allows no button on a route row, so the one Go names the selected route
            // and the rows say which is selected (DESIGN.md §8.3a).
            val sel = deps.guidance().selected.value.coerceIn(0, choices.size - 1)
            choices.forEachIndexed { i, c ->
                // A green tick on the selected route, an empty circle on the other: clear before Go.
                val icon = if (choices.size == 1) null else routeIcon(selected = i == sel)
                // The card's width is the host's; keep the text short so it wraps less: the route's
                // name goes on the title line beside the time, the traffic on the line below.
                val name = if (c.efficient && choices.size > 1) "Energy-efficient" else c.label
                items.addItem(routeRow(c.route.seconds, c.route.meters, routeTraffic(c.route), icon, title = " · $name", second = routeQueue(c.route)))
            }
            items.setSelectedIndex(sel)
            items.setOnSelectedListener { i ->
                lifecycleScope.launch {
                    if (!deps.guidance().select(i)) {
                        androidx.car.app.CarToast.makeText(carContext, "Couldn't switch to that route", androidx.car.app.CarToast.LENGTH_SHORT).show()
                    }
                    invalidate()
                }
            }
        }
        b.setItemList(items.build())
        b.setNavigateAction(
            Action.Builder().setTitle(goTitle()).setOnClickListener { start(simulate = false) }.build(),
        )
        // Debug builds only: the bench's simulated drive along the route (AGENTS.md §4). Never in a release.
        if (BuildConfig.DEBUG) {
            b.setActionStrip(ActionStrip.Builder().addAction(CarUi.textAction("Simulate") { start(simulate = true) }).build())
        }
        return b.build()
    }

    /** [text] under the destination's header with a Back action: the error, or no route found. */
    private fun message(text: String): Template =
        MessageTemplate.Builder(text).setHeader(CarUi.header(destination.title)).addAction(CarUi.textAction("Back") { screenManager.pop() }).build()

    /** The host requires a duration or distance span on every route row (it throws otherwise). */
    private fun routeRow(
        seconds: Int, meters: Int, extra: String, icon: androidx.car.app.model.CarIcon? = null, title: String = "", second: String? = null,
    ): Row {
        // The spans replace the first character with the host's own formatted duration / distance,
        // so the time and distance render in the car's style and units.
        val title = SpannableString("  $title").apply { setSpan(DurationSpan.create(seconds.toLong()), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
        val text = SpannableString("  $extra").apply {
            setSpan(DistanceSpan.create(io.autonav.alerts.car.TripMapper(carContext, session.imperial).distance(meters)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        return Row.Builder().setTitle(CarText.create(title)).addText(CarText.create(text))
            .apply { second?.let { addText(it) }; icon?.let(::setImage) }.build()
    }

    /** "Go", or with two routes "Go · Fastest" / "Go · Energy-efficient", so it's clear which one Go uses. */
    private fun goTitle(): String {
        val go = carContext.getString(R.string.car_go)
        val choices = deps.guidance().choices.value
        val c = choices.getOrNull(deps.guidance().selected.value)?.takeIf { choices.size >= 2 } ?: return go
        return "$go · " + if (c.efficient) "Energy-efficient" else "Fastest"
    }

    /**
     * The tick / circle drawn here as a bitmap, coloured: a resource icon is loaded by the host from
     * its own copy of our resources, which after an update can still be the old APK's (it showed
     * other icons on the bench).
     */
    private fun routeIcon(selected: Boolean): androidx.car.app.model.CarIcon {
        val d = androidx.core.content.ContextCompat.getDrawable(carContext, if (selected) R.drawable.ic_route_selected else R.drawable.ic_route_option)!!.mutate()
        androidx.core.graphics.drawable.DrawableCompat.setTint(d, if (selected) 0xFF34A853.toInt() else 0xFFBDC1C6.toInt())
        val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96)
        d.draw(android.graphics.Canvas(bmp))
        return CarUi.icon(bmp)
    }

    /** First line after the distance: " · heavy traffic +12 min", so the routes can be compared (DESIGN.md §8.3a). */
    private fun routeTraffic(r: io.autonav.alerts.nav.EcoRoutes.Route): String = trafficLine(r.traffic, r.delayMinutes)

    /**
     * Second line: how far to the first queue Google projects for when you get there, and about
     * when ("Queue in 12 mi · ~20 min"), or "No queues expected" (DESIGN.md §8.3a).
     */
    private fun routeQueue(r: io.autonav.alerts.nav.EcoRoutes.Route): String? {
        val m = r.firstJamMeters
        if (m == null) return if (r.traffic != io.autonav.alerts.nav.EcoRoutes.Traffic.UNKNOWN) "No queues expected" else null
        val mins = maxOf(r.minutesToFirstJam ?: 0, 1)
        return "Queue in ${CarUi.formatDistance(m, session.imperial)} · ~$mins min"
    }

    // The single SDK route keeps the SDK's own traffic level (§8.3a); only heavy and medium are worth a word.
    private fun trafficText(severity: Int): String = when (severity) {
        com.google.android.libraries.navigation.TimeAndDistance.DELAY_HEAVY -> " · heavy traffic"
        com.google.android.libraries.navigation.TimeAndDistance.DELAY_MEDIUM -> " · some traffic"
        else -> ""
    }

    /** The bench's `--ez go true`: Go on the showing preview (TESTING.md §3). */
    fun go() = start(simulate = false)

    /**
     * Go. The destination is remembered in Recents first, from the app scope and not cancellable
     * (a `lifecycleScope` insert was cancelled by `popToRoot()` and Recents stayed empty after a
     * real drive, DESIGN.md §8), and awaited before guidance starts and the screen pops. Guidance
     * starts inside the same non-cancellable block, so a Back pressed meanwhile can't leave the
     * route set with nothing guiding it.
     */
    private fun start(simulate: Boolean) {
        if (started) return
        started = true
        lifecycleScope.launch {
            withContext(NonCancellable) {
                App.appScope.async { runCatching { deps.places().remember(destination, destination.address) } }.await()
                deps.eventLog().record(EventLog.Channel.NAV, "Guidance", "Places remember \"${destination.title}\"")
                deps.guidance().startGuidance(simulate)
            }
            screenManager.popToRoot()
        }
    }

    companion object {
        /** The preview on screen, if any, for the debug bench's `--ez go true`. */
        @Volatile var showing: RoutePreviewScreen? = null
            private set

        /** " · light traffic", " · heavy traffic +12 min" (the delay only when there is one), nothing when the traffic is unknown (§8.3a). */
        internal fun trafficLine(traffic: io.autonav.alerts.nav.EcoRoutes.Traffic, delayMinutes: Int): String {
            if (traffic == io.autonav.alerts.nav.EcoRoutes.Traffic.UNKNOWN) return ""
            return " · ${traffic.name.lowercase()} traffic" + if (delayMinutes > 0) " +$delayMinutes min" else ""
        }
    }
}

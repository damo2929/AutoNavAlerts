// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
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
import io.autonav.alerts.car.NavSession
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
 * preview up) reaches `onDestroy`, which abandons the preview unless guidance has started: the
 * destination and its route are cleared (§8). While the screen is on screen `MapSurface.previewShowing`
 * is set, so the follow watchdog leaves the route overview alone (§8, §16 row 22).
 */
@Suppress("DEPRECATION")
class RoutePreviewScreen(
    carContext: CarContext,
    private val session: NavSession,
    private val destination: Destination,
) : Screen(carContext), DefaultLifecycleObserver {
    private val deps get() = session.deps
    private var route: RouteOption? = null
    private var error: String? = null
    private var loading = true
    private var started = false

    init {
        lifecycle.addObserver(this)
        lifecycleScope.launch {
            error = deps.guidance().setDestination(destination)
            route = deps.guidance().currentRoute()
            loading = false
            invalidate()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        session.surface.previewShowing = true
        showing = this
    }

    override fun onStop(owner: LifecycleOwner) {
        session.surface.previewShowing = false
        if (showing === this) showing = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        session.surface.previewShowing = false
        if (showing === this) showing = null
        // Back, or the session ending: abandonPreview is a no-op once guidance runs (the Go path).
        if (!started && !deps.guidance().guiding.value) deps.guidance().abandonPreview()
    }

    override fun onGetTemplate(): Template {
        error?.let { msg ->
            return MessageTemplate.Builder(msg)
                .setHeader(Header.Builder().setTitle(destination.title).setStartHeaderAction(Action.BACK).build())
                .addAction(CarUi.textAction("Back") { screenManager.pop() })
                .build()
        }
        val header = Header.Builder().setTitle(destination.title).setStartHeaderAction(Action.BACK).build()
        val b = RoutePreviewNavigationTemplate.Builder().setHeader(header)
        if (loading) return b.setLoading(true).build()
        val r = route ?: return MessageTemplate.Builder("Couldn't find a route there")
            .setHeader(header)
            .addAction(CarUi.textAction("Back") { screenManager.pop() })
            .build()
        val items = ItemList.Builder()
        val choices = deps.guidance().choices.value
        if (choices.isEmpty()) {
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
                val icon = if (choices.size == 1) null
                    else if (i == sel) routeIcon(R.drawable.ic_route_selected, 0xFF34A853.toInt())
                    else routeIcon(R.drawable.ic_route_option, 0xFFBDC1C6.toInt())
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
        if (BuildConfig.DEBUG) {
            b.setActionStrip(ActionStrip.Builder().addAction(CarUi.textAction("Simulate") { start(simulate = true) }).build())
        }
        return b.build()
    }

    /** The host requires a duration or distance span on every route row (it throws otherwise). */
    private fun routeRow(
        seconds: Int, meters: Int, extra: String, icon: androidx.car.app.model.CarIcon? = null, title: String = "", second: String? = null,
    ): Row {
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
        if (choices.size < 2) return go
        val c = choices.getOrNull(deps.guidance().selected.value) ?: return go
        return "$go · " + if (c.efficient) "Energy-efficient" else "Fastest"
    }

    /**
     * The tick / circle drawn here as a bitmap, coloured: a resource icon is loaded by the host from
     * its own copy of our resources, which after an update can still be the old APK's (it showed
     * other icons on the bench).
     */
    private fun routeIcon(res: Int, color: Int): androidx.car.app.model.CarIcon {
        val d = androidx.core.content.ContextCompat.getDrawable(carContext, res)!!.mutate()
        androidx.core.graphics.drawable.DrawableCompat.setTint(d, color)
        val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96)
        d.draw(android.graphics.Canvas(bmp))
        return androidx.car.app.model.CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)).build()
    }

    /** First line after the distance: " · heavy traffic +12 min", so the routes can be compared (DESIGN.md §8.3a). */
    private fun routeTraffic(r: io.autonav.alerts.nav.EcoRoutes.Route): String {
        val delay = if (r.delayMinutes > 0) " +${r.delayMinutes} min" else ""
        val level = when (r.traffic) {
            io.autonav.alerts.nav.EcoRoutes.Traffic.LIGHT -> " · light traffic$delay"
            io.autonav.alerts.nav.EcoRoutes.Traffic.SOME -> " · some traffic$delay"
            io.autonav.alerts.nav.EcoRoutes.Traffic.HEAVY -> " · heavy traffic$delay"
            io.autonav.alerts.nav.EcoRoutes.Traffic.UNKNOWN -> ""
        }
        return level
    }

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
     * real drive, DESIGN.md §8), and awaited before guidance starts and the screen pops.
     */
    private fun start(simulate: Boolean) {
        if (started) return
        started = true
        lifecycleScope.launch {
            withContext(NonCancellable) {
                App.appScope.async { runCatching { deps.places().remember(destination, destination.address) } }.await()
            }
            deps.eventLog().record(EventLog.Channel.NAV, "Guidance", "Places remember \"${destination.title}\"")
            deps.guidance().startGuidance(simulate)
            screenManager.popToRoot()
        }
    }

    companion object {
        /** The preview on screen, if any, for the debug bench's `--ez go true`. */
        @Volatile var showing: RoutePreviewScreen? = null
            private set
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.navigation.SpeedAlertOptions
import com.google.android.libraries.navigation.SpeedAlertSeverity
import io.autonav.alerts.App
import io.autonav.alerts.alerts.ActiveAlert
import io.autonav.alerts.alerts.AlertCoordinator
import io.autonav.alerts.alerts.CarAlertSink
import io.autonav.alerts.car.screens.HomeScreen
import io.autonav.alerts.car.screens.RoutePreviewScreen
import io.autonav.alerts.car.screens.SearchScreen
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.alerts.Units
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.map.MapSurface
import io.autonav.alerts.map.PoiMarkerLayer
import io.autonav.alerts.map.SpeedPanelView
import io.autonav.alerts.nav.Destination
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One car session: map surface, alerts, markers, speed panel and navigation intents (DESIGN.md §8–9). */
class NavSession : Session(), DefaultLifecycleObserver {
    lateinit var deps: CarDeps
        private set
    lateinit var surface: MapSurface
        private set
    private lateinit var markers: PoiMarkerLayer

    @Volatile private var speedingPct: Float = 0f
    @Volatile private var speedingSeverity = SpeedPanelView.Severity.NONE
    @Volatile private var derivedLimit: Pair<Int, Long>? = null
    @Volatile var imperial = true
        private set
    @Volatile private var speedPanelOn = true

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        deps = CarDeps.from(carContext)
        surface = MapSurface(carContext)
        markers = PoiMarkerLayer(deps.icons(), (36 * carContext.resources.displayMetrics.density).toInt())
        surface.onMapReady = { m -> markers.map = m; m.setOnCameraIdleListener { redrawMarkers() } }
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface)
        carContext.getCarService(NavigationManager::class.java).setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = deps.guidance().stopGuidance()
            override fun onAutoDriveEnabled() {
                Log.i(TAG, "Auto drive enabled (DHU): starting simulation if a route is set")
                deps.guidance().startGuidance(simulate = true)
            }
        })
        deps.carData().start(carContext)
        deps.driving().carSessionOpen = true
        deps.tts().warmUp()
        deps.alerts().start(App.appScope)
        deps.dispatcher().carSink = CarAlertSink(::showCarAlert)
        startCollectors()

        val home = HomeScreen(carContext, this)
        handleNavigateIntent(intent)?.let { pending ->
            carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(home)
            return pending
        }
        return home
    }

    override fun onNewIntent(intent: Intent) {
        handleNavigateIntent(intent)?.let { carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(it) }
    }

    /**
     * Best-known speed limit right now, for a report (DESIGN.md §8.5): Google's (only known while
     * speeding), else the camera being alerted, else none.
     */
    fun currentLimit(): Pair<Int?, io.autonav.alerts.data.LimitSource> {
        val now = System.currentTimeMillis()
        derivedLimit?.takeIf { now - it.second < 60_000 }?.let { return it.first to io.autonav.alerts.data.LimitSource.GOOGLE }
        deps.alerts().active.value?.takeIf { now - it.timeMs < 20_000 }?.speedLimit?.let { return it to io.autonav.alerts.data.LimitSource.CAMERA }
        return null to io.autonav.alerts.data.LimitSource.NONE
    }

    /** geo: / google.navigation: / ACTION_NAVIGATE → preview or search (DESIGN.md §9.1). */
    private fun handleNavigateIntent(intent: Intent): Screen? {
        if (intent.action != CarContext.ACTION_NAVIGATE && intent.action != Intent.ACTION_VIEW) return null
        val data: Uri = intent.data ?: return null
        val (latLon, query) = parseNavUri(data)
        return when {
            latLon != null -> RoutePreviewScreen(carContext, this, Destination(query ?: "Destination", latLon.first, latLon.second))
            !query.isNullOrBlank() -> SearchScreen(carContext, this, initialQuery = query)
            else -> null
        }
    }

    private fun startCollectors() {
        lifecycleScope.launch {
            deps.guidance().arrived.collect { if (it) deps.reports().remindIfDrafts() }
        }
        lifecycleScope.launch {
            deps.settings().settings.collect { imperial = it.units == Units.IMPERIAL; speedPanelOn = it.speedPanelOn }
        }
        lifecycleScope.launch {
            val nav = deps.navigator().ensureNavigator() ?: return@launch
            nav.setSpeedAlertOptions(SpeedAlertOptions(5f, 10f, 5.0))
            nav.setSpeedingListener { pct, severity ->
                speedingPct = pct
                speedingSeverity = when (severity) {
                    SpeedAlertSeverity.MAJOR -> SpeedPanelView.Severity.MAJOR
                    SpeedAlertSeverity.MINOR -> SpeedPanelView.Severity.MINOR
                    else -> SpeedPanelView.Severity.NONE
                }
            }
        }
        // Every fix: redraw markers (DESIGN.md §8.1) and update the speed panel (§8.4).
        lifecycleScope.launch {
            combine(deps.alerts().lastFix, deps.alerts().nearby, deps.alerts().active) { f, n, a -> Triple(f, n, a) }
                .collect { (fix, nearby, active) ->
                    fix?.let { deps.driving().onSpeed(it.speedMps) }
                    markers.redraw(nearby, fix, active)
                    updateSpeedPanel(fix?.speedMps, active)
                }
        }
    }

    private fun redrawMarkers() {
        val a = deps.alerts()
        markers.redraw(a.nearby.value, a.lastFix.value, a.active.value)
    }

    private fun updateSpeedPanel(speedMps: Double?, active: ActiveAlert?) {
        val panel = surface.speedPanel ?: return
        if (!speedPanelOn) { panel.post { panel.visibility = android.view.View.GONE }; return }
        if (speedMps == null) return
        if (panel.visibility != android.view.View.VISIBLE) panel.post { panel.visibility = android.view.View.VISIBLE }
        // The car's display speed matches its dashboard; otherwise the engine's speed (car raw or GPS).
        val shownMps = deps.carData().freshSpeed()?.displayMps?.toDouble()
            ?.takeIf { !deps.guidance().simulating.value } ?: speedMps
        val speed = if (imperial) shownMps / Geo.MPS_PER_MPH else shownMps * 3.6
        val shown = speed.roundToInt()
        val now = System.currentTimeMillis()
        // While speeding, the SDK reports % over Google's limit, so the limit can be worked out.
        if (speedingSeverity != SpeedPanelView.Severity.NONE && speedingPct > 0f) {
            val lim = (speed / (1 + speedingPct / 100.0) / 5.0).roundToInt() * 5
            derivedLimit = lim to now
        }
        val google = derivedLimit?.takeIf { now - it.second < 60_000 }?.first
        val camera = active?.takeIf { now - it.timeMs < 20_000 && it.kind != AlertKind.ZONE_EXIT }?.speedLimit
        val limit = google ?: camera
        val unit = if (imperial) "mph" else "km/h"
        panel.post { panel.update(shown, limit, google == null && camera != null, speedingSeverity, unit) }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Car-screen alert (car API ≥ 5), with Dismiss and Mute actions (DESIGN.md §12.1). Called from the
     * alert thread; Car App Library calls are posted to the main thread.
     */
    private fun showCarAlert(alert: ActiveAlert): Boolean {
        if (lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED) return false
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_5) return false
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(deps.icons().bitmap(alert.iconId, 128))).build()
        val subtitle = buildString {
            if (alert.distanceM > 0) append(AlertCoordinator.distanceLabel(alert.distanceM, imperial))
            alert.speedLimit?.let { if (isNotEmpty()) append(" · "); append(it).append(if (imperial) " mph" else " km/h") }
        }
        val id = (alert.poiId % 100_000).toInt()
        main.post {
            val am = carContext.getCarService(AppManager::class.java)
            val builder = Alert.Builder(id, CarText.create(alert.display), if (alert.kind == AlertKind.PASSING) 5_000 else 8_000)
                .setIcon(icon)
                .addAction(Action.Builder().setTitle("Dismiss").setOnClickListener { am.dismissAlert(id) }.build())
                .addAction(Action.Builder().setTitle("Mute 30 min").setOnClickListener {
                    deps.alerts().muteSet(alert.setId)
                    am.dismissAlert(id)
                }.build())
                .setCallback(object : AlertCallback {
                    override fun onCancel(reason: Int) = Unit
                    override fun onDismiss() = Unit
                })
            if (subtitle.isNotEmpty()) builder.setSubtitle(CarText.create(subtitle))
            runCatching {
                am.dismissAlert(id)
                am.showAlert(builder.build())
            }.onFailure { Log.w(TAG, "showAlert failed", it) }
        }
        return true
    }

    override fun onDestroy(owner: LifecycleOwner) {
        deps.carData().stop()
        deps.driving().carSessionOpen = false
        // End of drive: remind the user to check buffered reports on the phone (§8.5).
        App.appScope.launch { deps.reports().remindIfDrafts() }
        deps.dispatcher().carSink = null
        deps.alerts().stop()
        markers.clear()
        surface.release()
    }

    companion object {
        private const val TAG = "NavSession"

        /** Parses geo:lat,lon?q=... and google.navigation:q=... into coordinates and/or a query. */
        fun parseNavUri(uri: Uri): Pair<Pair<Double, Double>?, String?> {
            val s = uri.toString()
            val q = Regex("""[?&]q=([^&]+)""").find(s)?.groupValues?.get(1)?.let { Uri.decode(it).replace('+', ' ') }
            val coord = Regex("""(-?\d+\.?\d*),\s*(-?\d+\.?\d*)""")
            val fromPath = if (s.startsWith("geo:")) coord.find(s.removePrefix("geo:").substringBefore('?')) else null
            val fromQuery = q?.let { coord.matchEntire(it.substringBefore('(').trim()) }
            val m = (fromPath ?: fromQuery)?.let { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
                ?.takeIf { !(it.first == 0.0 && it.second == 0.0) }
            val label = q?.let { Regex("""\((.+)\)""").find(it)?.groupValues?.get(1) ?: if (fromQuery != null) null else it }
            return m to label
        }
    }
}

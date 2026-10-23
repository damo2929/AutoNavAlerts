// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
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
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.SpeedAlertOptions
import com.google.android.libraries.navigation.SpeedAlertSeverity
import io.autonav.alerts.App
import io.autonav.alerts.R
import io.autonav.alerts.alerts.ActiveAlert
import io.autonav.alerts.alerts.AlertCoordinator
import io.autonav.alerts.alerts.CarAlertSink
import io.autonav.alerts.car.screens.HomeScreen
import io.autonav.alerts.car.screens.RoutePreviewScreen
import io.autonav.alerts.car.screens.SearchScreen
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.alerts.Units
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.map.MapSurface
import io.autonav.alerts.map.PoiMarkerLayer
import io.autonav.alerts.map.SpeedPanelView
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.nav.LimitAnnouncer
import io.autonav.alerts.nav.StopReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One car session: map surface, alerts, markers, speed panel and navigation intents (DESIGN.md §8–9). */
class NavSession : Session(), DefaultLifecycleObserver {
    lateinit var deps: CarDeps
        private set
    lateinit var surface: MapSurface
        private set
    private lateinit var markers: PoiMarkerLayer
    private lateinit var limitMarker: io.autonav.alerts.map.LimitChangeMarker
    private lateinit var eventLog: EventLog
    private var tripPublisher: TripPublisher? = null
    /** The SDK's road-snapped position, for the follow watchdog (DESIGN.md §8); registered once the navigator exists. */
    private val sdkListener = RoadSnappedLocationProvider.LocationListener { loc -> surface.onSdkLocation(loc) }

    @Volatile private var speedingPct: Float = 0f
    @Volatile private var speedingSeverity = SpeedPanelView.Severity.NONE
    /** When the SDK last reported speeding; an older % isn't used to work out the limit (§8.4). */
    @Volatile private var speedingAtMs = 0L
    @Volatile private var derivedLimit: Pair<Int, Long>? = null
    @Volatile var imperial = true
        private set
    @Volatile private var speedPanelOn = true
    @Volatile private var osmLimitsOn = true
    @Volatile private var announceLimits = true
    @Volatile private var voiceOn = true
    @Volatile private var mutedUntilMs = 0L

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        active = this
        deps = CarDeps.from(carContext)
        eventLog = deps.eventLog()
        eventLog.record(EventLog.Channel.NAV, "Session", "create api=${carContext.carAppApiLevel} host=${runCatching { carContext.hostInfo?.packageName }.getOrNull() ?: "?"}")
        deps.driveRecorder().start("car")
        surface = MapSurface(carContext) { deps.navigator().applyApiKey() }
        surface.guidingProvider = { deps.guidance().guiding.value }
        markers = PoiMarkerLayer(deps.icons(), (36 * carContext.resources.displayMetrics.density).toInt())
        limitMarker = io.autonav.alerts.map.LimitChangeMarker(carContext.resources.displayMetrics.density)
        surface.onMapReady = { m -> markers.map = m; limitMarker.map = m; m.setOnCameraIdleListener { redrawMarkers() } }
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface)
        carContext.getCarService(NavigationManager::class.java).setNavigationManagerCallback(object : NavigationManagerCallback {
            // The X on the host's travel-estimate card (the one cancel on the car screen, DESIGN.md §8),
            // the system UI, or another navigation app taking over (§9.3): logged, then guidance stops.
            override fun onStopNavigation() {
                eventLog.record(EventLog.Channel.NAV, "Session", "hostStopNavigation guiding=${deps.guidance().guiding.value}")
                deps.guidance().stopGuidance(StopReason.HOST)
            }
            override fun onAutoDriveEnabled() {
                // Only with a route set: startGuidance logs and does nothing otherwise (DESIGN.md §9.3).
                Log.i(TAG, "Auto drive enabled (DHU): starting simulation if a route is set")
                deps.guidance().startGuidance(simulate = true)
            }
        })
        deps.carData().start(carContext)
        deps.driving().carSessionOpen = true
        deps.tts().warmUp()
        deps.alerts().start(App.appScope)
        deps.dispatcher().carSink = CarAlertSink(::showCarAlert)
        tripPublisher = TripPublisher(carContext, deps, { imperial }, eventLog).also { it.start(lifecycleScope) }
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

    /** Day or night changed in the car: our map buttons follow it (DESIGN.md §8). */
    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        if (::surface.isInitialized) surface.setDark(carContext.isDarkMode)
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
        eventLog.record(EventLog.Channel.NAV, "Search", "NavigateIntent action=${intent.action} coords=${latLon != null} queryLen=${query?.length ?: 0}")
        return when {
            latLon != null -> RoutePreviewScreen(carContext, this, Destination(query ?: "Destination", latLon.first, latLon.second))
            !query.isNullOrBlank() -> SearchScreen(carContext, this, initialQuery = query)
            else -> null
        }
    }

    private fun startCollectors() {
        // Arrival has ended guidance (DESIGN.md §8 "Arrival"): say so, and remind about reports (§8.5).
        lifecycleScope.launch {
            deps.guidance().arrivals.collect { title ->
                val text = if (title.isBlank()) carContext.getString(R.string.car_arrived) else carContext.getString(R.string.car_arrived_at, title)
                CarToast.makeText(carContext, text, CarToast.LENGTH_LONG).show()
                App.appScope.launch { deps.reports().remindIfDrafts() }
            }
        }
        lifecycleScope.launch {
            deps.settings().settings.collect {
                imperial = it.units == Units.IMPERIAL
                speedPanelOn = it.speedPanelOn
                osmLimitsOn = it.osmLimitsOn
                announceLimits = it.announceLimitChanges
                voiceOn = it.voiceOn
                mutedUntilMs = it.mutedUntilMs
                if (surface.satellite != it.satelliteMap) surface.satellite = it.satelliteMap
            }
        }
        surface.onSatelliteToggle = { on ->
            lifecycleScope.launch { deps.settings().update { it.copy(satelliteMap = on) } }
        }
        // The listeners follow the current navigator: a new key makes a new one (DESIGN.md §9.3).
        lifecycleScope.launch { deps.navigator().ensureNavigator() }
        lifecycleScope.launch {
            deps.navigator().navigator.collect { nav -> attachNavigator(nav) }
        }
        // The SDK builds its ETA card when guidance starts, so the controls-off calls are repeated then
        // (DESIGN.md §8 step 6; on the bench a call made before the card existed had no effect).
        lifecycleScope.launch {
            deps.guidance().guiding.collect { g ->
                surface.refreshSdkControls("guiding=$g")
                delay(2_000)
                surface.refreshSdkControls("guiding=$g +2s")
            }
        }
        // Every fix: redraw markers (DESIGN.md §8.1) and update the speed panel (§8.4).
        lifecycleScope.launch {
            combine(deps.alerts().lastFix, deps.alerts().nearby, deps.alerts().active, deps.alerts().facingAway, deps.alerts().verdicts) { f, n, a, away, verdicts ->
                Triple(f, n, a) to (away to verdicts)
            }
                .collect { (t, av) ->
                    val (fix, nearby, active) = t
                    val (away, verdicts) = av
                    fix?.let { deps.driving().onSpeed(it.speedMps) }
                    markers.redraw(nearby, fix, active, away, verdicts)
                    updateSpeedPanel(fix?.speedMps, active)
                }
        }
        // Limit changes ahead (DESIGN.md §8.4a): once a second, never per fix.
        lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                runCatching { tickLimitAhead() }.onFailure { Log.w(TAG, "limitAhead", it) }
            }
        }
    }

    private var attachedNav: Navigator? = null
    private var sdkProvider: RoadSnappedLocationProvider? = null

    /** Speeding listener and the SDK position (for the follow watchdog) on [nav]; off the old one. */
    private fun attachNavigator(nav: Navigator?) {
        if (nav === attachedNav) return
        detachNavigator()
        nav ?: return
        attachedNav = nav
        nav.setSpeedAlertOptions(SpeedAlertOptions(5f, 10f, 5.0))
        nav.setSpeedingListener { pct, severity ->
            speedingPct = pct
            speedingAtMs = System.currentTimeMillis()
            speedingSeverity = when (severity) {
                SpeedAlertSeverity.MAJOR -> SpeedPanelView.Severity.MAJOR
                SpeedAlertSeverity.MINOR -> SpeedPanelView.Severity.MINOR
                else -> SpeedPanelView.Severity.NONE
            }
        }
        // The follow watchdog watches the SDK's own position, never our fix (DESIGN.md §8).
        deps.navigator().roadSnappedProvider()?.let { p ->
            p.addLocationListener(sdkListener)
            sdkProvider = p
            eventLog.record(EventLog.Channel.NAV, "Session", "sdkLocationListener added")
        }
    }

    private fun detachNavigator() {
        attachedNav?.let { runCatching { it.setSpeedingListener(null) } }
        sdkProvider?.let { runCatching { it.removeLocationListener(sdkListener) } }
        attachedNav = null
        sdkProvider = null
        speedingPct = 0f
        speedingSeverity = SpeedPanelView.Severity.NONE
    }

    private fun redrawMarkers() {
        val a = deps.alerts()
        markers.redraw(a.nearby.value, a.lastFix.value, a.active.value, a.facingAway.value, a.verdicts.value)
    }

    // --- Limit changes ahead (DESIGN.md §8.4a) --------------------------------------------------

    private val limitAnnouncer = LimitAnnouncer()

    /**
     * The next change of limit on our road, from the OSM data on the phone and the route ahead:
     * the grey limit marker on the map where it starts, one `limitAhead` line per change, and one spoken announcement
     * within max(300 m, speed × 12 s) when `announceLimitChanges` is on (voice on, not muted).
     */
    private fun tickLimitAhead() {
        val fix = deps.alerts().lastFix.value
        val heading = fix?.heading
        if (fix == null || heading == null || !osmLimitsOn) { setUpcoming(null); return }
        val route = deps.guidance().routePointsAhead()
        val change = deps.speedLimits().nextChange(fix.lat, fix.lon, heading, route.takeIf { it.size >= 2 })
        // Already under it (the panel shows that limit): nothing ahead to say. Both in display units (§8.4).
        val upcoming = change?.let { io.autonav.alerts.core.osm.OsmSpeedLimits.display(it.newMph.toDouble(), imperial) }
        if (change == null || upcoming == lastLimit?.limit) { setUpcoming(null); return }
        setUpcoming(change, upcoming)
        eventLog.onChange("alerts.limitAhead", change.newMph to change.viaRoute, EventLog.Channel.ALERT, "Alerts") {
            "limitAhead new=${change.newMph} d=${change.distanceM.toInt()} via=${if (change.viaRoute) "route" else "road"}"
        }
        if (!limitAnnouncer.shouldAnnounce(change.newMph, change.distanceM, fix.lat, fix.lon, fix.speedMps, heading, change.lat, change.lon)) return
        val speak = announceLimits && voiceOn && mutedUntilMs <= System.currentTimeMillis()
        eventLog.record(EventLog.Channel.ALERT, "Alerts", "limitAhead announce new=${change.newMph} d=${change.distanceM.toInt()} spd=${fix.speedMps.roundToInt()} speak=$speak")
        // Its own kind: never reported, listed or traced as a camera stage (owner, 2026-10-13).
        if (speak) deps.tts().speak(LimitAnnouncer.text(upcoming!!), 10, -1, false, kind = io.autonav.alerts.core.alerts.AlertKind.LIMIT_CHANGE)
    }

    /** The map marker for the change ahead ([limit] in display units), or none; redrawn only on a change (§8.4a). */
    private fun setUpcoming(change: io.autonav.alerts.core.osm.LimitLookahead.Change?, limit: Int? = null) {
        if (change == null || limit == null) limitMarker.clear() else limitMarker.show(limit, change.lat, change.lon)
        if (limit == null) eventLog.onChange("alerts.limitAhead", null, EventLog.Channel.ALERT, "Alerts") { "limitAhead none" }
    }

    private data class HeldLimit(val limit: Int, val source: SpeedPanelView.Source, val timeMs: Long, val lat: Double, val lon: Double)
    private var lastLimit: HeldLimit? = null
    private var pendingOsm: HeldLimit? = null
    private var shownLimit: Int? = null

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
        // While speeding, the SDK reports % over Google's limit, so the limit can be worked out: from
        // the engine's speed (GPS or the car's raw), not the dashboard figure, which reads high (§8.4).
        // Snapped to a limit that exists, and only from an update in the last 2 s (bench, 2026-10-23: "45").
        if (speedingSeverity != SpeedPanelView.Severity.NONE && speedingPct > 0f && now - speedingAtMs <= 2_000) {
            val measured = if (imperial) speedMps / Geo.MPS_PER_MPH else speedMps * 3.6
            val lim = io.autonav.alerts.core.osm.LimitSnap.snap(measured / (1 + speedingPct / 100.0), imperial)
            derivedLimit = lim to now
        }
        val google = derivedLimit?.takeIf { now - it.second < 60_000 }?.first
        val camera = active?.takeIf { now - it.timeMs < 20_000 && it.kind != AlertKind.ZONE_EXIT }?.limitShown(imperial)
        // OpenStreetMap last (DESIGN.md §8.4a): from the downloaded tiles, never a live lookup.
        val osm = if (google == null && camera == null && osmLimitsOn) {
            deps.alerts().lastFix.value?.let { f -> deps.speedLimits().limitAt(f.lat, f.lon, f.heading) }
                ?.let { io.autonav.alerts.core.osm.OsmSpeedLimits.display(it, imperial) }
        } else null
        var (limit, source) = when {
            google != null -> google to SpeedPanelView.Source.GOOGLE
            camera != null -> camera to SpeedPanelView.Source.CAMERA
            else -> osm to SpeedPanelView.Source.OSM
        }
        // Steady OSM changes (DESIGN.md §8.4): at junctions a side road within 20 m can match for a
        // fix or two. A different OSM limit replaces the shown one only after 2 s or 40 m.
        val fixNow = deps.alerts().lastFix.value
        val shownNow = lastLimit
        if (source == SpeedPanelView.Source.OSM && limit != null && shownNow != null &&
            shownNow.source == SpeedPanelView.Source.OSM && limit != shownNow.limit && fixNow != null
        ) {
            val p = pendingOsm
            if (p == null || p.limit != limit) pendingOsm = HeldLimit(limit, source, now, fixNow.lat, fixNow.lon)
            val q = pendingOsm!!
            val held = now - q.timeMs >= 2_000 || Geo.distanceM(q.lat, q.lon, fixNow.lat, fixNow.lon) >= 40
            if (!held) limit = shownNow.limit else pendingOsm = null
        } else pendingOsm = null
        // Hold the last limit through short gaps (junctions, road pieces meeting): up to 30 s or
        // 300 m (DESIGN.md §8.4), so the sign doesn't blink to "–".
        val fix = deps.alerts().lastFix.value
        if (limit != null) {
            if (fix != null) lastLimit = HeldLimit(limit, source, now, fix.lat, fix.lon)
        } else lastLimit?.let { held ->
            val moved = fix?.let { Geo.distanceM(held.lat, held.lon, it.lat, it.lon) } ?: 0.0
            if (now - held.timeMs < 30_000 && moved < 300) { limit = held.limit; source = held.source } else lastLimit = null
        }
        val unit = if (imperial) "mph" else "km/h"
        // `Panel limit n src=` only when it changes (DESIGN.md §15.5), and `limitChanged a→b` to the
        // alert log when the shown limit flips, so the lookahead can be checked against what happened.
        eventLog.onChange("panel.limit", (limit ?: -1) to source, EventLog.Channel.NAV, "Panel") {
            "limit ${limit ?: "–"} src=${if (limit == null) "none" else source.name} at ${fix?.let { "%.5f,%.5f".format(it.lat, it.lon) } ?: "-"} $shown $unit"
        }
        val before = shownLimit
        if (limit != before) {
            shownLimit = limit
            if (before != null && limit != null) eventLog.record(EventLog.Channel.ALERT, "Alerts", "limitChanged $before→$limit src=${source.name}")
        }
        panel.post { panel.update(shown, limit, source, speedingSeverity, unit) }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Car-screen alert (DESIGN.md §12.1): the banner at the bottom of our map, or, before the map
     * exists, the host's alert card (car API ≥ 5) with Dismiss and Mute actions. Called from the
     * alert thread; view and Car App Library calls are posted to the main thread.
     */
    private fun showCarAlert(alert: ActiveAlert): Boolean {
        if (lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED) return false
        val bitmap = deps.icons().bitmap(alert.iconId, 128)
        val subtitle = buildString {
            if (alert.distanceM > 0) append(AlertCoordinator.distanceLabel(alert.distanceM, imperial))
            alert.limitShown(imperial)?.let { if (isNotEmpty()) append(" · "); append(it).append(if (imperial) " mph" else " km/h") }
        }
        val duration = if (alert.kind == AlertKind.PASSING) 5_000L else 8_000L
        surface.alertBanner?.let { banner ->
            banner.post { banner.show(alert.poiId, bitmap, alert.display, subtitle, duration) }
            return true
        }
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_5) return false
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        val id = (alert.poiId % 100_000).toInt()
        main.post {
            val am = carContext.getCarService(AppManager::class.java)
            val builder = Alert.Builder(id, CarText.create(alert.display), duration)
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

    /**
     * The car session ends. Guidance is NOT stopped here (DESIGN.md §9.3: it and the foreground
     * service outlive the session); the logs are flushed so a kill right after loses nothing.
     */
    override fun onDestroy(owner: LifecycleOwner) {
        eventLog.record(EventLog.Channel.NAV, "Session", "destroy guiding=${deps.guidance().guiding.value}")
        detachNavigator()
        tripPublisher?.stop()
        tripPublisher = null
        deps.carData().stop()
        deps.driving().carSessionOpen = false
        // End of drive: remind the user to check buffered reports on the phone (§8.5).
        App.appScope.launch { deps.reports().remindIfDrafts() }
        deps.dispatcher().carSink = null
        deps.alerts().stop()
        markers.clear()
        limitMarker.clear()
        surface.close()
        deps.driveRecorder().stop()
        eventLog.flush("session")
        if (active === this) active = null
    }

    companion object {
        /** The open car session, for the debug-build bench hook (TESTING.md §3); null when none. */
        @Volatile var active: NavSession? = null
            private set

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

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
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.versioning.CarAppApiLevels
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
import io.autonav.alerts.car.screens.CarUi
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
import io.autonav.alerts.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One car session (DESIGN.md §2, §8, §9): everything that lives as long as the car screen is
 * connected and isn't a screen of its own. It owns the [MapSurface] (the Navigation SDK map on
 * the host's surface), the POI marker layer, the limit-change marker, the speed panel's data,
 * the session-level [TripPublisher], the car-side alert sink (banner or host alert) and the
 * handling of navigation intents (§9.1). It also wires the session-level listeners to whichever
 * `Navigator` is current: the speeding listener for the speed panel's limit and colour, and the
 * SDK's road-snapped position for the follow watchdog (§8, §9.3).
 *
 * Lifecycle: created by [NavCarAppService.onCreateSession] for each connection; the host calls
 * `onCreateScreen`, then `onDestroy` when the car screen closes. Guidance and the foreground
 * service outlive it (§9.3): a session ending does not stop a route.
 *
 * Threads (§2): the Car App Library calls (`onCreateScreen`, `onNewIntent`, the callbacks) and
 * everything on `lifecycleScope` run on the main thread. The marker-state collector also
 * runs there; it posts view updates to the views' own handlers. [showCarAlert] is the one entry
 * from the alert thread (`AlertDispatcher`) and posts to the main thread itself. The screens see
 * it as their [CarScreenHost] (deps, units, surface, report limit).
 *
 * Hard rules it upholds: one stop on the car screen, Google's X on the host's travel-estimate
 * card, which reaches us through `onStopNavigation` (rule 2); the speed panel and the markers
 * are updated with new values only, never on a timer (rule 3); the one-tap report captures here
 * and review and send happen on the phone, with the end-of-drive reminder from `onDestroy`
 * (rule 8).
 */
class NavSession : Session(), DefaultLifecycleObserver, CarScreenHost {
    /** The app's singletons; valid from `onCreateScreen` (screens read it as `session.deps`). */
    override lateinit var deps: CarDeps
        private set
    /** The map on the car's surface, with the overlays the screens and alerts talk to (§8). */
    override lateinit var surface: MapSurface
        private set
    private lateinit var markers: PoiMarkerLayer
    private lateinit var limitMarker: io.autonav.alerts.map.LimitChangeMarker
    private lateinit var eventLog: EventLog
    private var tripPublisher: TripPublisher? = null
    /** The SDK's road-snapped position, for the follow watchdog (DESIGN.md §8); registered once the navigator exists. */
    private val sdkListener = RoadSnappedLocationProvider.LocationListener { loc -> surface.onSdkLocation(loc) }

    // The SDK's speeding listener writes these from its own thread and the alert collector reads them,
    // hence @Volatile on each; they are plain values, so no lock is needed.
    @Volatile private var speedingPct: Float = 0f
    @Volatile private var speedingSeverity = SpeedPanelView.Severity.NONE
    /** When the SDK last reported speeding; an older % isn't used to work out the limit (§8.4). */
    @Volatile private var speedingAtMs = 0L
    /** Google's limit worked out from the last speeding update, and when; kept for 60 s (§8.4). */
    @Volatile private var derivedLimit: Pair<Int, Long>? = null
    /**
     * The settings the session reads on every fix (units, speed panel, OSM limits, limit announcements, voice,
     * mute), copied from the store's flow so the hot paths never touch DataStore; the defaults until it answers.
     */
    @Volatile private var settings = AppSettings()
    /** Display units from the settings: miles, yards and mph when true (§13). Read by every screen. */
    override val imperial: Boolean get() = settings.units == Units.IMPERIAL
    /** The speed and a limit in the display units (§8.4). */
    private val unit: String get() = if (imperial) "mph" else "km/h"

    init {
        lifecycle.addObserver(this)
    }

    /**
     * The session starts: builds the map surface and its overlays, registers the host callbacks,
     * opens the car-side services (car data, TTS warm-up, the alert coordinator, the trip feed) and
     * returns the first screen. A navigate [intent] (§9.1) puts the preview or search over Home so
     * Back still lands on the map.
     */
    override fun onCreateScreen(intent: Intent): Screen {
        active = this
        deps = CarDeps.from(carContext)
        eventLog = deps.eventLog()
        session("create api=${carContext.carAppApiLevel} host=${runCatching { carContext.hostInfo?.packageName }.getOrNull() ?: "?"}")
        deps.driveRecorder().start("car")
        // The map is only created once the user's own Maps key is applied: no built-in key (hard rule 5, §1.4).
        surface = MapSurface(carContext) { deps.navigator().applyApiKey() }
        surface.guidingProvider = { deps.guidance().guiding.value }
        // 36 dp marker icons; the layer needs the whole surface size for its bounds (§8.1).
        markers = PoiMarkerLayer(deps.icons(), (36 * carContext.resources.displayMetrics.density).toInt()) { surface.surfaceSize }
        limitMarker = io.autonav.alerts.map.LimitChangeMarker(carContext.resources.displayMetrics.density)
        // Camera idle (the user panned or zoomed) is the one redraw not driven by a fix (§8.1).
        surface.onMapReady = { m -> markers.map = m; limitMarker.map = m; m.setOnCameraIdleListener { redrawMarkers() } }
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface)
        carContext.getCarService(NavigationManager::class.java).setNavigationManagerCallback(object : NavigationManagerCallback {
            // The X on the host's travel-estimate card (the one cancel on the car screen, DESIGN.md §8),
            // the system UI, or another navigation app taking over (§9.3): logged, then guidance stops.
            override fun onStopNavigation() {
                session("hostStopNavigation guiding=${deps.guidance().guiding.value}")
                deps.guidance().stopGuidance(StopReason.HOST)
            }
            // The DHU's "auto drive" test hook (§9.3): simulate driving along the set route.
            override fun onAutoDriveEnabled() {
                // Only with a route set: startGuidance logs and does nothing otherwise (DESIGN.md §9.3).
                Log.i(TAG, "Auto drive enabled (DHU): starting simulation if a route is set")
                deps.guidance().startGuidance(simulate = true)
            }
        })
        deps.carData().start(carContext)
        // Report sending is refused while a car session is open (§8.5 "Never while driving").
        deps.driving().carSessionOpen = true
        deps.tts().warmUp()
        // The alert engine runs on the app scope, so it outlives the session with the foreground service (§9.3).
        deps.alerts().start(App.appScope)
        deps.dispatcher().carSink = CarAlertSink(::showCarAlert)
        tripPublisher = TripPublisher(carContext, deps) { imperial }.also { it.start(lifecycleScope) }
        startCollectors()

        val home = HomeScreen(carContext, this)
        handleNavigateIntent(intent)?.let { pending ->
            push(home)
            return pending
        }
        return home
    }

    /** A navigate intent while the session is up (Assistant, a share, a link): its screen goes on top (§9.1). */
    override fun onNewIntent(intent: Intent) {
        handleNavigateIntent(intent)?.let(::push)
    }

    private fun push(screen: Screen) = carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(screen)

    /** A `Session …` line in debug-nav (§15.5): create, destroy, the host's stop, the SDK listener. */
    private fun session(msg: String) = eventLog.record(EventLog.Channel.NAV, "Session", msg)

    /** Day or night changed in the car: our map buttons follow it (DESIGN.md §8). */
    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        if (::surface.isInitialized) surface.setDark(carContext.isDarkMode)
    }

    /**
     * Best-known speed limit right now, for a report (DESIGN.md §8.5): Google's (only known while
     * speeding), else the camera being alerted, else none.
     */
    override fun currentLimit(): Pair<Int?, io.autonav.alerts.data.LimitSource> {
        val now = System.currentTimeMillis()
        // Same windows as the speed panel (§8.4): Google's limit for 60 s, a camera's for 20 s. The
        // camera's limit is in its data's own unit; the report keeps the source so review can show it.
        googleLimit(now)?.let { return it to io.autonav.alerts.data.LimitSource.GOOGLE }
        freshAlert(deps.alerts().active.value, now)?.speedLimit?.let { return it to io.autonav.alerts.data.LimitSource.CAMERA }
        return null to io.autonav.alerts.data.LimitSource.NONE
    }

    /** Google's limit from the last speeding update, while it is under 60 s old at [now] (§8.4). */
    private fun googleLimit(now: Long): Int? = derivedLimit?.takeIf { now - it.second < 60_000 }?.first

    /** [active] while it is under 20 s old at [now]: the camera whose limit the panel and a report may use (§8.4, §8.5). */
    private fun freshAlert(active: ActiveAlert?, now: Long): ActiveAlert? = active?.takeIf { now - it.timeMs < 20_000 }

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

    // The session's collectors, all on lifecycleScope (main thread), cancelled with the session.
    private fun startCollectors() {
        // Arrival has ended guidance (DESIGN.md §8 "Arrival"): say so, and remind about reports (§8.5).
        deps.guidance().arrivals.onEach { title ->
            val text = if (title.isBlank()) carContext.getString(R.string.car_arrived) else carContext.getString(R.string.car_arrived_at, title)
            CarToast.makeText(carContext, text, CarToast.LENGTH_LONG).show()
            App.appScope.launch { deps.reports().remindIfDrafts() }
        }.launchIn(lifecycleScope)
        // Settings the session reads on every fix are copied into fields here, so the hot paths never
        // touch DataStore. The map follows the one satellite setting (§8 "Satellite view"), and only
        // when it differs: setting it re-applies the view.
        deps.settings().settings.onEach {
            settings = it
            if (surface.satellite != it.satelliteMap) surface.satellite = it.satelliteMap
        }.launchIn(lifecycleScope)
        // The map's pill writes the setting, like More's toggle and the phone's, so all three agree (§8).
        surface.onSatelliteToggle = { on ->
            lifecycleScope.launch { deps.settings().update { it.copy(satelliteMap = on) } }
        }
        // The listeners follow the current navigator: a new key makes a new one (DESIGN.md §9.3).
        lifecycleScope.launch { deps.navigator().ensureNavigator() }
        deps.navigator().navigator.onEach(::attachNavigator).launchIn(lifecycleScope)
        // The SDK builds its ETA card when guidance starts, so the controls-off calls are repeated then
        // (DESIGN.md §8 step 6; on the bench a call made before the card existed had no effect).
        deps.guidance().guiding.onEach { g ->
            surface.refreshSdkControls("guiding=$g")
            delay(2_000)
            surface.refreshSdkControls("guiding=$g +2s")
        }.launchIn(lifecycleScope)
        // Every fix: redraw markers (DESIGN.md §8.1) and update the speed panel (§8.4).
        // The same fix the engine used feeds the panel, so the dial and the engine can't diverge (§8.4).
        deps.alerts().markers.onEach { m ->
            // Speed feeds the "moved faster than 5 mph in the last 2 minutes" block on sending (§8.5).
            m.lastFix?.let { deps.driving().onSpeed(it.speedMps) }
            markers.redraw(m)
            updateSpeedPanel(m.lastFix?.speedMps, m.active)
        }.launchIn(lifecycleScope)
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
        // Minor at 5 % over, major at 10 % over (the panel's amber and red, §8.4), major after 5 s.
        nav.setSpeedAlertOptions(SpeedAlertOptions(5f, 10f, 5.0))
        // Google exposes its limit only while you're over it, as a percentage (§8.4): kept with its time.
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
            session("sdkLocationListener added")
        }
    }

    // Taken off the old navigator so a replaced one (after a key change) doesn't keep feeding the panel.
    private fun detachNavigator() {
        attachedNav?.let { runCatching { it.setSpeedingListener(null) } }
        sdkProvider?.let { runCatching { it.removeLocationListener(sdkListener) } }
        attachedNav = null
        sdkProvider = null
        speedingPct = 0f
        speedingSeverity = SpeedPanelView.Severity.NONE
    }

    // Camera idle: the same state as the last fix, drawn for the new viewport (§8.1).
    private fun redrawMarkers() = markers.redraw(deps.alerts().markers.value)

    // --- Limit changes ahead (DESIGN.md §8.4a) --------------------------------------------------

    /** Once per change: keyed by the new limit and where it starts, so a change is never announced twice (§8.4a). */
    private val limitAnnouncer = LimitAnnouncer()

    /**
     * The next change of limit on our road, from the OSM data on the phone and the route ahead:
     * the grey limit marker on the map where it starts, one `limitAhead` line per change, and one spoken announcement
     * within max(300 m, speed × 12 s) when `announceLimitChanges` is on (voice on, not muted).
     */
    private fun tickLimitAhead() {
        val fix = deps.alerts().lastFix.value
        val heading = fix?.heading
        // No heading (parked, crawling): the walk along the road can't start, so nothing ahead (§8.4a).
        if (fix == null || heading == null || !settings.osmLimitsOn) { setUpcoming(null); return }
        val route = deps.guidance().routePointsAhead()
        // With a route the lookahead walks the route polyline; without one it follows the road by connectivity (§8.4a).
        val change = deps.speedLimits().nextChange(fix.lat, fix.lon, heading, route.takeIf { it.size >= 2 })
        // Already under it (the panel shows that limit): nothing ahead to say. Both in display units (§8.4).
        val upcoming = change?.let { io.autonav.alerts.core.osm.OsmSpeedLimits.display(it.newMph.toDouble(), imperial) }
        if (change == null || upcoming == lastLimit?.limit) { setUpcoming(null); return }
        setUpcoming(change, upcoming)
        eventLog.onChange("alerts.limitAhead", change.newMph to change.viaRoute, EventLog.Channel.ALERT, "Alerts") {
            "limitAhead new=${change.newMph} d=${change.distanceM.toInt()} via=${if (change.viaRoute) "route" else "road"}"
        }
        if (!limitAnnouncer.shouldAnnounce(change.newMph, change.distanceM, fix.lat, fix.lon, fix.speedMps, heading, change.lat, change.lon)) return
        val speak = settings.announceLimitChanges && settings.voiceOn && settings.mutedUntilMs <= System.currentTimeMillis()
        eventLog.record(EventLog.Channel.ALERT, "Alerts", "limitAhead announce new=${change.newMph} d=${change.distanceM.toInt()} spd=${fix.speedMps.roundToInt()} speak=$speak")
        // Its own kind: never reported, listed or traced as a camera stage (owner, 2026-10-13).
        if (speak) deps.tts().speak(LimitAnnouncer.text(upcoming!!), 10, -1, false, kind = io.autonav.alerts.core.alerts.AlertKind.LIMIT_CHANGE)
    }

    /** The map marker for the change ahead ([limit] in display units), or none; redrawn only on a change (§8.4a). */
    private fun setUpcoming(change: io.autonav.alerts.core.osm.LimitLookahead.Change?, limit: Int? = null) {
        if (change == null || limit == null) limitMarker.clear() else limitMarker.show(limit, change.lat, change.lon)
        if (limit == null) eventLog.onChange("alerts.limitAhead", null, EventLog.Channel.ALERT, "Alerts") { "limitAhead none" }
    }

    /** A limit shown on the panel, with when and where it was last confirmed (for the hold rules, §8.4). */
    private data class HeldLimit(val limit: Int, val source: SpeedPanelView.Source, val timeMs: Long, val lat: Double, val lon: Double)
    /** The limit last shown, held through gaps for up to 30 s or 300 m (§8.4). */
    private var lastLimit: HeldLimit? = null
    /** A different OSM limit waiting out the 2 s / 40 m steadiness rule before it replaces the shown one (§8.4). */
    private var pendingOsm: HeldLimit? = null
    /** What the panel last showed, for the `limitChanged a→b` log line. */
    private var shownLimit: Int? = null

    /**
     * The speed panel for one fix (DESIGN.md §8.4): the display speed and the limit from the first
     * source that has one (Google, camera or zone, OpenStreetMap, else none), with the hold and
     * steadiness rules below. Runs on the collector (main thread); the view is updated through its
     * own `post`, and the view itself redraws only when a shown value changed (hard rule 3).
     */
    private fun updateSpeedPanel(speedMps: Double?, active: ActiveAlert?) {
        val panel = surface.speedPanel ?: return
        if (!settings.speedPanelOn) { panel.post { panel.visibility = android.view.View.GONE }; return }
        if (speedMps == null) return
        if (panel.visibility != android.view.View.VISIBLE) panel.post { panel.visibility = android.view.View.VISIBLE }
        // The car's display speed matches its dashboard; otherwise the engine's speed (car raw or GPS).
        // Not while simulating on the bench: the car's real (parked) speed would hide the simulated one.
        val shownMps = deps.carData().freshSpeed()?.displayMps?.toDouble()
            ?.takeIf { !deps.guidance().simulating.value } ?: speedMps
        val shown = display(shownMps).roundToInt()
        val now = System.currentTimeMillis()
        // While speeding, the SDK reports % over Google's limit, so the limit can be worked out: from
        // the engine's speed (GPS or the car's raw), not the dashboard figure, which reads high (§8.4).
        // Snapped to a limit that exists, and only from an update in the last 2 s (bench, 2026-10-23: "45").
        if (speedingSeverity != SpeedPanelView.Severity.NONE && speedingPct > 0f && now - speedingAtMs <= 2_000) {
            val lim = io.autonav.alerts.core.osm.LimitSnap.snap(display(speedMps) / (1 + speedingPct / 100.0), imperial)
            derivedLimit = lim to now
        }
        // Source order (§8.4): Google (kept 60 s), then the camera being alerted (20 s; a zone exit has
        // no limit of its own to show), then OpenStreetMap. A camera's limit is converted to the display units.
        val google = googleLimit(now)
        val camera = freshAlert(active, now)?.takeIf { it.kind != AlertKind.ZONE_EXIT }?.limitShown(imperial)
        // OpenStreetMap last (DESIGN.md §8.4a): from the downloaded tiles, never a live lookup.
        val osm = if (google == null && camera == null && settings.osmLimitsOn) {
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
            val q = pendingOsm?.takeIf { it.limit == limit } ?: HeldLimit(limit, source, now, fixNow.lat, fixNow.lon)
            val held = now - q.timeMs >= 2_000 || Geo.distanceM(q.lat, q.lon, fixNow.lat, fixNow.lon) >= 40
            pendingOsm = if (held) null else q
            if (!held) limit = shownNow.limit
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

    /** [mps] in the display units: mph, or km/h (§8.4). */
    private fun display(mps: Double): Double = if (imperial) mps / Geo.MPS_PER_MPH else mps * 3.6

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Car-screen alert (DESIGN.md §12.1): the banner at the bottom of our map, or, before the map
     * exists, the host's alert card (car API ≥ 5) with Dismiss and Mute actions. Called from the
     * alert thread; view and Car App Library calls are posted to the main thread.
     *
     * Returns false when the session isn't in the foreground (the dispatcher then uses the
     * notification, §12) or the host can't show an alert, so the event is never lost.
     * The banner is the only alert surface on the map (hard rule 2, one element per job); the host
     * alert is used only while the map surface doesn't exist, never alongside it.
     */
    private fun showCarAlert(alert: ActiveAlert): Boolean {
        if (lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED) return false
        // The set's icon at 128 px, the same bitmap the marker layer uses (§8.1, §12.1).
        val bitmap = deps.icons().bitmap(alert.iconId, 128)
        val subtitle = buildString {
            if (alert.distanceM > 0) append(AlertCoordinator.distanceLabel(alert.distanceM, imperial))
            alert.limitShown(imperial)?.let { if (isNotEmpty()) append(" · "); append(it).append(' ').append(unit) }
        }
        // 8 s, or 5 s for the passing alert (§12.1); a new stage for the same POI restarts the time.
        val duration = if (alert.kind == AlertKind.PASSING) 5_000L else 8_000L
        surface.alertBanner?.let { banner ->
            banner.post { banner.show(alert.poiId, bitmap, alert.display, subtitle, duration) }
            return true
        }
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_5) return false
        // Host alert ids are Int; the same id for the same POI, so a later stage updates the card in place.
        val id = (alert.poiId % 100_000).toInt()
        main.post {
            val am = carContext.getCarService(AppManager::class.java)
            val card = CarUi.alert(id, alert.display, duration, CarUi.icon(bitmap), subtitle, "Dismiss" to { am.dismissAlert(id) }, "Mute 30 min" to {
                deps.alerts().muteSet(alert.setId)
                am.dismissAlert(id)
            })
            runCatching {
                am.dismissAlert(id)
                am.showAlert(card)
            }.onFailure { Log.w(TAG, "showAlert failed", it) }
        }
        return true
    }

    /**
     * The car session ends. Guidance is NOT stopped here (DESIGN.md §9.3: it and the foreground
     * service outlive the session); the logs are flushed so a kill right after loses nothing.
     */
    override fun onDestroy(owner: LifecycleOwner) {
        session("destroy guiding=${deps.guidance().guiding.value}")
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

        /**
         * Parses geo:lat,lon?q=... and google.navigation:q=... into coordinates and/or a query
         * (DESIGN.md §9.1). Returns (lat, lon) when the URI carries real coordinates (in the path
         * or as the whole `q`), and a label: the text in brackets after coordinates, else the `q`
         * text when it isn't itself a coordinate. `geo:0,0?q=address` gives no coordinates and the
         * address as the query, which goes to Search.
         */
        fun parseNavUri(uri: Uri): Pair<Pair<Double, Double>?, String?> {
            val s = uri.toString()
            val q = Regex("""[?&]q=([^&]+)""").find(s)?.groupValues?.get(1)?.let { Uri.decode(it).replace('+', ' ') }
            val coord = Regex("""(-?\d+\.?\d*),\s*(-?\d+\.?\d*)""")
            val fromPath = if (s.startsWith("geo:")) coord.find(s.removePrefix("geo:").substringBefore('?')) else null
            val fromQuery = q?.let { coord.matchEntire(it.substringBefore('(').trim()) }
            // 0,0 is the convention for "no position, use the query", never a destination.
            val m = (fromPath ?: fromQuery)?.let { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
                ?.takeIf { !(it.first == 0.0 && it.second == 0.0) }
            val label = q?.let { Regex("""\((.+)\)""").find(it)?.groupValues?.get(1) ?: if (fromQuery != null) null else it }
            return m to label
        }
    }
}

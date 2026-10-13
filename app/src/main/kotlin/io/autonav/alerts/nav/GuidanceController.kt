// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.AudioGuidanceSettings
import com.google.android.libraries.navigation.CustomRoutesOptions
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.Waypoint
import io.autonav.alerts.App
import io.autonav.alerts.BuildConfig
import io.autonav.alerts.core.alerts.RouteMatcher
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.geo.LatLngLike
import io.autonav.alerts.core.geo.LatLngPoint
import io.autonav.alerts.core.nav.EcoReaskPolicy
import io.autonav.alerts.core.nav.GuidanceState
import io.autonav.alerts.core.nav.RoutePolyline
import io.autonav.alerts.diag.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Where to go. [address] is kept with the recent (e.g. a contact's address, DESIGN.md §8.3). */
data class Destination(val title: String, val lat: Double, val lon: Double, val placeId: String? = null, val address: String? = null)

/** A route the driver can choose in the preview (DESIGN.md §8.3a). */
data class RouteChoice(val label: String, val route: EcoRoutes.Route, val efficient: Boolean)

/** Route alternative shown in the preview (DESIGN.md §8). */
data class RouteOption(val index: Int, val seconds: Int, val meters: Int, val delaySeverity: Int)

/**
 * Who ended guidance (DESIGN.md §9.3): every stop is logged with its caller so a route that
 * vanishes on a drive can be traced. `SESSION_DESTROY` is defined but not used: the car session
 * ending keeps guidance running (§9.3); the owner decides after the next drive's logs.
 */
enum class StopReason { ACTION_STRIP, HOST, ARRIVED_DONE, KEY_CHANGED, SESSION_DESTROY, BENCH }

/**
 * Destination, preview, guidance and route geometry on top of the [Navigator] (DESIGN.md §8,
 * §8.3a, §9.3). The route state lives in one core [GuidanceState], mirrored to the StateFlows the
 * screens collect; [stopGuidance] is the only way guidance ends and resets all of it.
 */
@Singleton
class GuidanceController @Inject constructor(
    private val app: Application,
    private val holder: NavigatorHolder,
    private val location: AdaptiveLocationSource,
    private val settings: io.autonav.alerts.settings.SettingsStore,
    private val eco: EcoRoutes,
    private val eventLog: EventLog,
) {
    private val _routeNote = MutableStateFlow<String?>(null)
    /** What kind of route is set, for the preview: e.g. "Energy-efficient route (EV)" (DESIGN.md §8.3a). */
    val routeNote: StateFlow<String?> = _routeNote

    @Volatile private var waypoint: Waypoint? = null

    /** The one source of truth for the route state (core, pure); the flows below mirror it. */
    @Volatile private var state = GuidanceState()
    private val ecoPolicy = EcoReaskPolicy()
    /** A rerouting event has been seen and no route-changed event has followed it yet (§8.3a). */
    @Volatile private var ecoReaskPending = false

    private val _simulating = MutableStateFlow(false)
    /** True while the Navigation SDK simulator drives the route (debug bench testing). */
    val simulating: StateFlow<Boolean> = _simulating

    private val simListener = RoadSnappedLocationProvider.LocationListener { loc -> location.inject(loc) }

    private val _destination = MutableStateFlow<Destination?>(null)
    val destination: StateFlow<Destination?> = _destination

    private val _guiding = MutableStateFlow(false)
    val guiding: StateFlow<Boolean> = _guiding

    private val _arrived = MutableStateFlow(false)
    val arrived: StateFlow<Boolean> = _arrived

    @Volatile private var routePoints: List<LatLng> = emptyList()
    /** The same route as a core polyline, for the alert engine and the limit lookahead. */
    @Volatile private var polyline: RoutePolyline? = null
    /** The navigator the listeners were added to (a new key makes a new navigator). */
    private var listenersFor: Navigator? = null
    /** The engine's latest heading (track heading, DESIGN.md §10.6a), for the re-ask origin. */
    @Volatile private var lastHeading: Double? = null

    init {
        // A new key makes a new navigator: stop on the old one while it's still alive (NavigatorHolder
        // runs this before cleanup()).
        holder.onKeyChanged { stopGuidance(StopReason.KEY_CHANGED) }
        App.appScope.launch { location.fixes.collect { lastHeading = it.heading } }
    }

    /**
     * Route geometry for the alert engine's parallel-road check (DESIGN.md §11 step 4): the
     * cross-track distance to the route ahead of the car, never to segments behind. Called from
     * the alert thread once per fix; `locate` first, so [RoutePolyline.carIndex] follows the car.
     */
    val routeMatcher = RouteMatcher { lat, lon ->
        val p = polyline ?: return@RouteMatcher null
        if (!_guiding.value) return@RouteMatcher null
        // With the heading: a route that doubles back along this road (the SDK's U-turn route from a
        // parked car) must not pin the car to the leg going the other way (bench, 2026-10-13).
        p.locate(lat, lon, lastHeading)
        // Off the SDK's route (it hasn't rerouted yet, or routed a parallel road): the route says
        // nothing about the road the car is on, so the engine falls back to its free-drive corridor
        // (DESIGN.md §11 step 4; bench 2026-10-13: cameras on the driven A61 were 1.2 km from the route).
        val carOff = p.carDistanceM(lat, lon)
        val offRoute = carOff > CAR_OFF_ROUTE_M
        eventLog.onChange("guidance.carOffRoute", offRoute, EventLog.Channel.NAV, "Guidance") {
            if (offRoute) "carOffRoute d=${carOff.toInt()} m: free-drive matching until the SDK reroutes" else "carOnRoute d=${carOff.toInt()} m"
        }
        if (offRoute) return@RouteMatcher null
        p.distanceToRouteM(lat, lon).coerceAtMost(10_000.0)
    }

    /**
     * The route from the car onward (first point at the car, then the SDK route from the car's
     * segment), for the limit lookahead (DESIGN.md §8.4a). Empty with no route, no fix, fewer than
     * two points, or when the car is more than 100 m off the route (then it says nothing about the
     * road the car is on). Doesn't move the engine's [RoutePolyline.carIndex].
     */
    fun routePointsAhead(): List<LatLngLike> {
        val p = polyline ?: return emptyList()
        val pts = routePoints
        if (pts.size < 2) return emptyList()
        val here = location.lastLocation.value ?: return emptyList()
        val i = p.nearestSegment(here.latitude, here.longitude, p.carIndex).coerceIn(0, pts.size - 2)
        val a = pts[i]; val b = pts[i + 1]
        if (Geo.distanceToSegmentM(here.latitude, here.longitude, a.latitude, a.longitude, b.latitude, b.longitude) > 100.0) return emptyList()
        val out = ArrayList<LatLngLike>(AHEAD_POINTS + 1)
        out += LatLngPoint(here.latitude, here.longitude)
        var m = 0.0
        var prevLat = here.latitude; var prevLon = here.longitude
        for (j in i + 1 until pts.size) {
            val q = pts[j]
            out += LatLngPoint(q.latitude, q.longitude)
            m += Geo.distanceM(prevLat, prevLon, q.latitude, q.longitude)
            prevLat = q.latitude; prevLon = q.longitude
            if (m >= AHEAD_M || out.size > AHEAD_POINTS) break
        }
        return if (out.size >= 2) out else emptyList()
    }

    /** Sets the destination and computes routes. Returns null on success, or an error message. */
    suspend fun setDestination(dest: Destination): String? {
        val nav = holder.ensureNavigator() ?: return "Navigation isn't available (check the API key and terms on the phone)"
        addListeners(nav)
        val s = settings.current()
        log("setDestination \"${dest.title}\" ${"%.5f".format(dest.lat)} ${"%.5f".format(dest.lon)} placeId=${dest.placeId ?: "-"} ev=${s.evEfficientRoutes} tolls=${s.avoidTolls}")
        val waypoint = runCatching {
            Waypoint.builder().setTitle(dest.title).apply {
                if (dest.placeId != null) runCatching { setPlaceIdString(dest.placeId) }.onFailure { setLatLng(dest.lat, dest.lon) }
                else setLatLng(dest.lat, dest.lon)
            }.build()
        }.getOrElse { return "Bad destination: ${it.message}" }
        this.waypoint = waypoint
        val status = route(nav, waypoint, dest)
        return if (status == Navigator.RouteStatus.OK) {
            _destination.value = dest
            refreshRoutePoints(nav)
            state = state.previewing(
                choices = maxOf(1, _choices.value.size), selectedIndex = _selected.value,
                selectedEfficient = _choices.value.getOrNull(_selected.value)?.efficient == true, routeNote = _routeNote.value,
            )
            _arrived.value = false
            null
        } else {
            "No route: ${status.name.lowercase().replace('_', ' ')}"
        }
    }

    /** Debug bench only: two route rows even when Google's fastest is also the most efficient. */
    @Volatile var debugTwoChoices = false

    private val _choices = MutableStateFlow<List<RouteChoice>>(emptyList())
    /** Routes the driver can pick in the preview (fastest / energy-efficient); empty = the SDK's one route. */
    val choices: StateFlow<List<RouteChoice>> = _choices
    private val _selected = MutableStateFlow(0)
    val selected: StateFlow<Int> = _selected

    /**
     * Routes to [waypoint] (DESIGN.md §8.3a). With energy-efficient routes on and the Routes API
     * answering, both the fastest and the energy-efficient route become [choices] and the
     * energy-efficient one is set; otherwise the SDK's own route, with a [routeNote] saying why.
     */
    private suspend fun route(nav: Navigator, waypoint: Waypoint, dest: Destination): Navigator.RouteStatus {
        _routeNote.value = null
        _choices.value = emptyList()
        val s = settings.current()
        if (s.evEfficientRoutes) {
            val here = location.lastLocation.value ?: lastKnown()
            val eco = if (here == null) EcoRoutes.Result.Unavailable("no GPS position yet")
                else eco.route(here.latitude, here.longitude, dest, s.avoidTolls, lastHeading)
            when (eco) {
                is EcoRoutes.Result.Routes -> {
                    // Debug only (bench): show two rows even when Google returns one route.
                    val same = (eco.same || eco.fastest == null) && !(BuildConfig.DEBUG && debugTwoChoices)
                    val list = if (same) listOf(RouteChoice("Fastest and most efficient", eco.efficient, efficient = true))
                        else listOf(RouteChoice("Fastest", eco.fastest ?: eco.efficient, efficient = false), RouteChoice("Energy-efficient (EV)", eco.efficient, efficient = true))
                    val pick = list.indexOfFirst { it.efficient }
                    val status = setToken(nav, waypoint, list[pick].route.token, "preview")
                    if (status == Navigator.RouteStatus.OK) {
                        _choices.value = list
                        _selected.value = pick
                        Log.i(TAG, "Route choices: ${list.joinToString { "${it.label} ${it.route.seconds}s ${it.route.meters}m" }}")
                        return status
                    }
                    Log.w(TAG, "The SDK refused the energy-efficient route: $status")
                    _routeNote.value = "Fastest route (the energy-efficient route couldn't be used)"
                }
                is EcoRoutes.Result.Unavailable ->
                    _routeNote.value = "Fastest route (energy-efficient routing isn't available: ${eco.reason})"
            }
        }
        return plainRoute(nav, waypoint, "preview")
    }

    /** The SDK's own route with the tolls option; every status is logged (DESIGN.md §15.5). */
    private suspend fun plainRoute(nav: Navigator, waypoint: Waypoint, why: String): Navigator.RouteStatus {
        val t0 = SystemClock.elapsedRealtime()
        val tolls = settings.current().avoidTolls
        val status = suspendCancellableCoroutine { cont ->
            nav.setDestinations(listOf(waypoint), RoutingOptions().avoidTolls(tolls)) { desc -> if (cont.isActive) cont.resume(desc.routeStatus) }
        }
        logRouteStatus(nav, status, t0, why)
        return status
    }

    private fun logRouteStatus(nav: Navigator, status: Navigator.RouteStatus, t0: Long, why: String) {
        val points = if (status == Navigator.RouteStatus.OK) runCatching { nav.routeSegments.sumOf { it.latLngs.size } }.getOrDefault(-1) else 0
        log("routeStatus=${status.name} ms=${SystemClock.elapsedRealtime() - t0} points=$points ($why)")
    }

    /** The phone's last known position, for right after a restart before any new fix. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnown(): android.location.Location? = runCatching {
        val lm = app.getSystemService(android.location.LocationManager::class.java)
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()

    private suspend fun setToken(nav: Navigator, waypoint: Waypoint, token: String, why: String): Navigator.RouteStatus {
        val t0 = SystemClock.elapsedRealtime()
        val options = CustomRoutesOptions.Builder().setRouteToken(token).setTravelMode(CustomRoutesOptions.TravelMode.DRIVING).build()
        val status = suspendCancellableCoroutine { cont ->
            nav.setDestinations(listOf(waypoint), options) { desc -> if (cont.isActive) cont.resume(desc.routeStatus) }
        }
        logRouteStatus(nav, status, t0, "token $why")
        if (status == Navigator.RouteStatus.OK) state = state.tokenApplied(token)
        return status
    }

    /** The driver picked a route in the preview: show it on the map; Go then guides along it. */
    suspend fun select(index: Int): Boolean {
        val nav = holder.navigator.value ?: return false
        val wp = waypoint ?: return false
        val choice = _choices.value.getOrNull(index) ?: return false
        if (index == _selected.value) return true
        val ok = setToken(nav, wp, choice.route.token, "select") == Navigator.RouteStatus.OK
        if (ok) {
            _selected.value = index
            state = state.copy(selectedIndex = index, selectedEfficient = choice.efficient)
            refreshRoutePoints(nav)
        } else {
            recoverRoute(nav, "select")
        }
        return ok
    }

    fun currentRoute(): RouteOption? {
        val nav = holder.navigator.value ?: return null
        val td = nav.currentTimeAndDistance ?: return null
        return RouteOption(0, td.seconds, td.meters, td.delaySeverity)
    }

    fun startGuidance(simulate: Boolean = false) {
        val nav = holder.navigator.value ?: return
        log("start simulate=$simulate")
        nav.setAudioGuidanceSettings(
            AudioGuidanceSettings.builder()
                .setGuidanceMode(AudioGuidanceSettings.GuidanceMode.VOICE_ALERTS_AND_GUIDANCE)
                .setBluetoothAudioEnabled(true)
                .build(),
        )
        nav.registerServiceForNavUpdates(
            app.packageName, NavUpdatesService::class.java.name,
            NavigationUpdatesOptions.Builder()
                .setNumNextStepsToPreview(2)
                .setGeneratedStepImagesType(NavigationUpdatesOptions.GeneratedStepImagesType.BITMAP)
                .setDisplayMetrics(app.resources.displayMetrics)
                .build(),
        )
        nav.startGuidance()
        state = state.started(SystemClock.elapsedRealtime())
        ecoReaskPending = false
        _guiding.value = true
        _arrived.value = false
        if (simulate && BuildConfig.DEBUG) {
            // The simulator moves the SDK's location only, so feed it to the alert engine too.
            location.setSimulated(true)
            holder.roadSnappedProvider()?.addLocationListener(simListener)
            _simulating.value = true
            nav.simulator.simulateLocationsAlongExistingRoute(SimulationOptions().speedMultiplier(simulationSpeed))
        }
    }

    /** Simulator speed multiplier (1 = real time). */
    @Volatile var simulationSpeed = 1f

    /**
     * The only way guidance ends (DESIGN.md §9.3): logged with its caller, and nothing survives
     * for the next route: destination, arrived, the choices and the selected one, the route note,
     * the waypoint, the EV re-ask state and the route points.
     */
    fun stopGuidance(reason: StopReason) {
        log("stop caller=$reason guiding=${state.guiding} dest=${_destination.value?.title ?: "-"}")
        val nav = holder.navigator.value
        if (nav != null) {
            if (_simulating.value) {
                holder.roadSnappedProvider()?.removeLocationListener(simListener)
                location.setSimulated(false)
            }
            runCatching { nav.simulator.unsetUserLocation() }
            runCatching { nav.stopGuidance() }.onFailure { Log.w(TAG, "stopGuidance", it) }
            runCatching { nav.clearDestinations() }.onFailure { Log.w(TAG, "clearDestinations", it) }
            runCatching { nav.unregisterServiceForNavUpdates() }
        }
        _simulating.value = false
        state = state.stopped()
        clearRoute()
    }

    /**
     * Back on the route preview (DESIGN.md §8): the destination and its route are cleared, unless
     * guidance is already running, when the route stays and this only notes the preview is gone.
     */
    fun abandonPreview() {
        if (state.guiding) {
            state = state.previewAbandoned()
            log("preview abandoned (guiding, route kept)")
            return
        }
        log("preview abandoned dest=${_destination.value?.title ?: "-"}")
        holder.navigator.value?.let { nav -> runCatching { nav.clearDestinations() } }
        state = state.previewAbandoned()
        clearRoute()
    }

    /** Everything about the route, after a stop or an abandoned preview; mirrors [state]. */
    private fun clearRoute() {
        waypoint = null
        routePoints = emptyList()
        polyline = null
        ecoReaskPending = false
        _guiding.value = state.guiding
        _arrived.value = state.arrived
        _destination.value = null
        _choices.value = emptyList()
        _selected.value = 0
        _routeNote.value = null
    }

    private fun refreshRoutePoints(nav: Navigator) {
        val pts = runCatching { nav.routeSegments.flatMap { it.latLngs } }.getOrDefault(emptyList())
        routePoints = pts
        polyline = if (pts.size >= 2) RoutePolyline(pts.map { LatLngPoint(it.latitude, it.longitude) }) else null
    }

    /**
     * The route is gone or refused (anything but OK): set the plain SDK route to the same
     * waypoint again, with the tolls option, so the driver is never left without one (§8.3a).
     */
    private suspend fun recoverRoute(nav: Navigator, why: String) {
        val wp = waypoint ?: run { log("recoverRoute skipped: no waypoint ($why)"); return }
        log("recoverRoute after=$why")
        val status = plainRoute(nav, wp, "recover")
        if (status == Navigator.RouteStatus.OK) refreshRoutePoints(nav)
    }

    private fun addListeners(nav: Navigator) {
        if (listenersFor === nav) return
        listenersFor = nav
        nav.addRouteChangedListener {
            state = state.routeChanged()
            refreshRoutePoints(nav)
            log("routeChanged points=${routePoints.size} rerouteCompleted=${state.sdkRerouteCompleted}")
            if (ecoReaskPending) {
                ecoReaskPending = false
                maybeEcoReask(nav)
            }
        }
        // Off the energy-efficient route the SDK re-routes by itself (fastest). The Routes API is
        // asked for an energy-efficient route from here only after that reroute has completed
        // (the route-changed event), by EcoReaskPolicy (DESIGN.md §8.3a): never racing the SDK.
        nav.addReroutingListener {
            state = state.rerouting()
            ecoReaskPending = state.guiding
            log("rerouting")
        }
        nav.addArrivalListener { event ->
            if (event.isFinalDestination) {
                state = state.arrived()
                _arrived.value = true
                log("arrived \"${event.waypoint?.title}\"")
            }
        }
    }

    /** Ask the Routes API again, if the policy says so; apply the token only when it's new. */
    private fun maybeEcoReask(nav: Navigator) {
        val wp = waypoint ?: return
        val dest = _destination.value ?: return
        val now = SystemClock.elapsedRealtime()
        val s = state
        when (val d = ecoPolicy.decide(now, s.guidanceStartedMs, s.lastEcoReaskMs, s.guiding, s.selectedEfficient, s.sdkRerouteCompleted)) {
            is EcoReaskPolicy.Decision.Skip -> { log("ecoReask skip reason=${d.reason}"); return }
            EcoReaskPolicy.Decision.Ask -> Unit
        }
        state = state.ecoAsked(now)
        App.appScope.launch(Dispatchers.Main) {
            val here = location.lastLocation.value ?: lastKnown() ?: run { log("ecoReask status=noPosition"); return@launch }
            val heading = lastHeading
            val t0 = SystemClock.elapsedRealtime()
            val eco = eco.route(here.latitude, here.longitude, dest, settings.current().avoidTolls, heading)
            when (eco) {
                is EcoRoutes.Result.Unavailable -> log("ecoReask status=unavailable ms=${SystemClock.elapsedRealtime() - t0} reason=${eco.reason} hdg=${heading?.toInt() ?: "-"}")
                is EcoRoutes.Result.Routes -> {
                    log("ecoReask status=OK ms=${SystemClock.elapsedRealtime() - t0} same=${eco.same} hdg=${heading?.toInt() ?: "-"}")
                    val apply = ecoPolicy.decide(
                        SystemClock.elapsedRealtime(), state.guidanceStartedMs, null, state.guiding, state.selectedEfficient,
                        state.sdkRerouteCompleted, newToken = eco.efficient.token, lastAppliedToken = state.lastAppliedToken,
                    )
                    if (apply is EcoReaskPolicy.Decision.Skip) { log("ecoReask skip reason=${apply.reason}"); return@launch }
                    if (!state.guiding) return@launch
                    val status = setToken(nav, wp, eco.efficient.token, "ecoReask")
                    if (status == Navigator.RouteStatus.OK) refreshRoutePoints(nav) else recoverRoute(nav, "ecoReask $status")
                }
            }
        }
    }

    private fun log(msg: String) = eventLog.record(EventLog.Channel.NAV, "Guidance", msg)

    companion object {
        /** The car further than this from its own route segment is off the SDK's route (§11 step 4). */
        private const val CAR_OFF_ROUTE_M = 50.0
        private const val TAG = "Guidance"
        /** How much route [routePointsAhead] returns: enough for the 600 m limit lookahead. */
        private const val AHEAD_M = 1_500.0
        private const val AHEAD_POINTS = 200
    }
}

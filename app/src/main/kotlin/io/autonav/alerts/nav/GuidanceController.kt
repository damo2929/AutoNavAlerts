// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.app.Application
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
import io.autonav.alerts.BuildConfig
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.alerts.RouteMatcher
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

/** Destination, preview, guidance and route geometry on top of the [Navigator]. */
@Singleton
class GuidanceController @Inject constructor(
    private val app: Application,
    private val holder: NavigatorHolder,
    private val location: AdaptiveLocationSource,
    private val settings: io.autonav.alerts.settings.SettingsStore,
    private val eco: EcoRoutes,
) {
    private val _routeNote = MutableStateFlow<String?>(null)
    /** What kind of route is set, for the preview: e.g. "Energy-efficient route (EV)" (DESIGN.md §8.3a). */
    val routeNote: StateFlow<String?> = _routeNote

    @Volatile private var waypoint: Waypoint? = null
    @Volatile private var lastEcoReroute = 0L

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
    private var listenersAdded = false

    /** Route geometry for the alert engine's parallel-road check (DESIGN.md §11 step 4). */
    val routeMatcher = RouteMatcher { lat, lon ->
        val pts = routePoints
        if (pts.size < 2 || !_guiding.value) return@RouteMatcher null
        var best = Double.MAX_VALUE
        // Coarse prefilter: only segments whose start is within 5 km.
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            if (kotlin.math.abs(a.latitude - lat) > 0.05 || kotlin.math.abs(a.longitude - lon) > 0.08) continue
            val b = pts[i + 1]
            val d = Geo.distanceToSegmentM(lat, lon, a.latitude, a.longitude, b.latitude, b.longitude)
            if (d < best) best = d
        }
        if (best == Double.MAX_VALUE) 10_000.0 else best
    }

    /** Sets the destination and computes routes. Returns null on success, or an error message. */
    suspend fun setDestination(dest: Destination): String? {
        val nav = holder.ensureNavigator() ?: return "Navigation isn't available (check the API key and terms on the phone)"
        addListeners(nav)
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
            _arrived.value = false
            refreshRoutePoints(nav)
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
                else eco.route(here.latitude, here.longitude, dest, s.avoidTolls)
            when (eco) {
                is EcoRoutes.Result.Routes -> {
                    // Debug only (bench): show two rows even when Google returns one route.
                    val same = (eco.same || eco.fastest == null) && !(BuildConfig.DEBUG && debugTwoChoices)
                    val list = if (same) listOf(RouteChoice("Fastest and most efficient", eco.efficient, efficient = true))
                        else listOf(RouteChoice("Fastest", eco.fastest ?: eco.efficient, efficient = false), RouteChoice("Energy-efficient (EV)", eco.efficient, efficient = true))
                    val pick = list.indexOfFirst { it.efficient }
                    val status = setToken(nav, waypoint, list[pick].route.token)
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
        return suspendCancellableCoroutine { cont ->
            nav.setDestinations(listOf(waypoint), RoutingOptions().avoidTolls(s.avoidTolls)) { desc -> if (cont.isActive) cont.resume(desc.routeStatus) }
        }
    }

    /** The phone's last known position, for right after a restart before any new fix. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnown(): android.location.Location? = runCatching {
        val lm = app.getSystemService(android.location.LocationManager::class.java)
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()

    private suspend fun setToken(nav: Navigator, waypoint: Waypoint, token: String): Navigator.RouteStatus {
        val options = CustomRoutesOptions.Builder().setRouteToken(token).setTravelMode(CustomRoutesOptions.TravelMode.DRIVING).build()
        return suspendCancellableCoroutine { cont ->
            nav.setDestinations(listOf(waypoint), options) { desc -> if (cont.isActive) cont.resume(desc.routeStatus) }
        }
    }

    /** The driver picked a route in the preview: show it on the map; Go then guides along it. */
    suspend fun select(index: Int): Boolean {
        val nav = holder.navigator.value ?: return false
        val wp = waypoint ?: return false
        val choice = _choices.value.getOrNull(index) ?: return false
        if (index == _selected.value) return true
        val ok = setToken(nav, wp, choice.route.token) == Navigator.RouteStatus.OK
        if (ok) { _selected.value = index; refreshRoutePoints(nav) }
        return ok
    }

    fun currentRoute(): RouteOption? {
        val nav = holder.navigator.value ?: return null
        val td = nav.currentTimeAndDistance ?: return null
        return RouteOption(0, td.seconds, td.meters, td.delaySeverity)
    }

    fun startGuidance(simulate: Boolean = false) {
        val nav = holder.navigator.value ?: return
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
        _guiding.value = true
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

    fun stopGuidance() {
        val nav = holder.navigator.value ?: return
        if (_simulating.value) {
            holder.roadSnappedProvider()?.removeLocationListener(simListener)
            location.setSimulated(false)
            _simulating.value = false
        }
        runCatching { nav.simulator.unsetUserLocation() }
        nav.stopGuidance()
        nav.clearDestinations()
        nav.unregisterServiceForNavUpdates()
        _guiding.value = false
        _destination.value = null
        routePoints = emptyList()
    }

    private fun refreshRoutePoints(nav: Navigator) {
        routePoints = runCatching { nav.routeSegments.flatMap { it.latLngs } }.getOrDefault(emptyList())
    }

    private fun addListeners(nav: Navigator) {
        if (listenersAdded) return
        listenersAdded = true
        nav.addRouteChangedListener { refreshRoutePoints(nav) }
        // Off the energy-efficient route, the SDK re-routes by itself (fastest); ask Google for an
        // energy-efficient one from here instead, at most once a minute (DESIGN.md §8.3a).
        nav.addReroutingListener {
            val wp = waypoint ?: return@addReroutingListener
            val dest = _destination.value ?: return@addReroutingListener
            val now = android.os.SystemClock.elapsedRealtime()
            if (!_guiding.value || now - lastEcoReroute < 60_000) return@addReroutingListener
            lastEcoReroute = now
            // Only when the driver chose the energy-efficient route; the fastest is the SDK's own job.
            if (_choices.value.getOrNull(_selected.value)?.efficient != true) return@addReroutingListener
            io.autonav.alerts.App.appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val here = location.lastLocation.value ?: lastKnown() ?: return@launch
                val eco = eco.route(here.latitude, here.longitude, dest, settings.current().avoidTolls)
                if (eco is EcoRoutes.Result.Routes) setToken(nav, wp, eco.efficient.token)
            }
        }
        nav.addArrivalListener { event ->
            if (event.isFinalDestination) {
                _arrived.value = true
                Log.i(TAG, "Arrived at ${event.waypoint?.title}")
            }
        }
    }

    companion object { private const val TAG = "Guidance" }
}

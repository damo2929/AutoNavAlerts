// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.app.Application
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.AudioGuidanceSettings
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
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class Destination(val title: String, val lat: Double, val lon: Double, val placeId: String? = null)

/** Route alternative shown in the preview (DESIGN.md §8). */
data class RouteOption(val index: Int, val seconds: Int, val meters: Int, val delaySeverity: Int)

/** Destination, preview, guidance and route geometry on top of the [Navigator]. */
@Singleton
class GuidanceController @Inject constructor(
    private val app: Application,
    private val holder: NavigatorHolder,
    private val location: AdaptiveLocationSource,
) {
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
        val status = suspendCancellableCoroutine { cont ->
            nav.setDestinations(listOf(waypoint), RoutingOptions()) { desc -> if (cont.isActive) cont.resume(desc.routeStatus) }
        }
        return if (status == Navigator.RouteStatus.OK) {
            _destination.value = dest
            _arrived.value = false
            refreshRoutePoints(nav)
            null
        } else {
            "No route: ${status.name.lowercase().replace('_', ' ')}"
        }
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
        nav.addArrivalListener { event ->
            if (event.isFinalDestination) {
                _arrived.value = true
                Log.i(TAG, "Arrived at ${event.waypoint?.title}")
            }
        }
    }

    companion object { private const val TAG = "Guidance" }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.CarToast
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import io.autonav.alerts.R
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.car.TripMapper
import io.autonav.alerts.nav.NavUnavailable
import io.autonav.alerts.nav.NavUpdatesService
import io.autonav.alerts.reports.toSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The map screen: free drive, or guidance with turn cards (DESIGN.md §8). Action strip:
 * Search · Report · Recents · More, with Stop replacing Recents while navigating.
 */
class HomeScreen(carContext: CarContext, private val session: NavSession) : Screen(carContext), DefaultLifecycleObserver {
    private val deps get() = session.deps
    private var navInfo: NavInfo? = null
    private var guiding = false
    private var arrived = false
    private var unavailable: NavUnavailable? = null
    private var lastInvalidate = 0L
    private var pendingInvalidate: Job? = null
    private var tripActive = false

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        lifecycleScope.launch {
            combine(
                NavUpdatesService.navInfo, deps.guidance().guiding, deps.guidance().arrived, deps.navigator().unavailable,
            ) { info, g, a, u -> listOf(info, g, a, u) }.collect { (info, g, a, u) ->
                navInfo = info as NavInfo?
                guiding = g as Boolean
                arrived = a as Boolean
                unavailable = u as NavUnavailable?
                publishTrip()
                invalidateThrottled()
            }
        }
        lifecycleScope.launch { deps.navigator().ensureNavigator(); invalidateThrottled() }
    }

    private fun publishTrip() {
        val nm = carContext.getCarService(NavigationManager::class.java)
        val info = navInfo
        if (guiding && !tripActive) { runCatching { nm.navigationStarted() }; tripActive = true }
        if (!guiding && tripActive) { runCatching { nm.navigationEnded() }; tripActive = false }
        if (guiding && info != null && info.navState == NavState.ENROUTE) {
            TripMapper(carContext, session.imperial).trip(info, deps.guidance().destination.value?.title)
                ?.let { runCatching { nm.updateTrip(it) } }
        }
    }

    private fun invalidateThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastInvalidate >= 1_000) {
            lastInvalidate = now
            invalidate()
        } else if (pendingInvalidate?.isActive != true) {
            pendingInvalidate = lifecycleScope.launch {
                delay(1_000 - (now - lastInvalidate))
                lastInvalidate = System.currentTimeMillis()
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val b = NavigationTemplate.Builder()
            .setActionStrip(actionStrip())
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .addAction(CarUi.action(carContext, R.drawable.ic_action_zoom_in) { session.surface.zoom(1f) })
                    .addAction(CarUi.action(carContext, R.drawable.ic_action_zoom_out) { session.surface.zoom(-1f) })
                    .addAction(CarUi.action(carContext, R.drawable.ic_action_recenter) { session.surface.recenter() })
                    .build(),
            )
            .setPanModeListener { }

        val info = navInfo
        val mapper = TripMapper(carContext, session.imperial)
        when {
            unavailable != null -> b.setNavigationInfo(
                MessageInfo.Builder(carContext.getString(R.string.car_setup_title))
                    .setText(setupText(unavailable!!))
                    .setImage(CarUi.icon(carContext, R.drawable.ic_action_report))
                    .build(),
            )
            arrived -> b.setNavigationInfo(
                MessageInfo.Builder(carContext.getString(R.string.car_arrived))
                    .setText(deps.guidance().destination.value?.title ?: "")
                    .setImage(CarUi.icon(carContext, R.drawable.ic_action_place))
                    .build(),
            )
            guiding && info != null && info.navState == NavState.ENROUTE -> {
                mapper.routingInfo(info)?.let { b.setNavigationInfo(it) }
                mapper.travelEstimate(info)?.let { b.setDestinationTravelEstimate(it) }
            }
            guiding && info?.navState == NavState.REROUTING -> b.setNavigationInfo(
                androidx.car.app.navigation.model.RoutingInfo.Builder().setLoading(true).build(),
            )
        }
        return b.build()
    }

    private fun setupText(u: NavUnavailable) = when (u) {
        NavUnavailable.NO_API_KEY -> "Add your Google Maps API key in the phone app's Settings."
        NavUnavailable.TERMS_NOT_ACCEPTED -> "Open the phone app once and accept the navigation terms."
        NavUnavailable.LOCATION_PERMISSION -> "Allow location access in the phone app."
        NavUnavailable.NOT_AUTHORIZED -> "The Maps API key isn't authorised for the Navigation SDK."
        NavUnavailable.NETWORK -> "No network connection."
        NavUnavailable.UNKNOWN -> "Navigation isn't available right now."
    }

    private fun actionStrip(): ActionStrip {
        val strip = ActionStrip.Builder()
        if (guiding || arrived) {
            strip.addAction(CarUi.textAction(carContext.getString(R.string.car_stop)) {
                deps.guidance().stopGuidance()
                invalidate()
            })
        } else {
            strip.addAction(CarUi.action(carContext, R.drawable.ic_action_search) {
                screenManager.push(SearchScreen(carContext, session))
            })
        }
        strip.addAction(CarUi.action(carContext, R.drawable.ic_action_report) { openReport() })
        if (!guiding && !arrived) {
            strip.addAction(CarUi.action(carContext, R.drawable.ic_action_recents) {
                screenManager.push(RecentsScreen(carContext, session))
            })
        }
        strip.addAction(CarUi.action(carContext, R.drawable.ic_action_more) {
            screenManager.push(MoreScreen(carContext, session))
        })
        return strip.build()
    }

    private fun openReport() {
        // Saved at the moment of the tap, so the report marks where the camera was seen (§8.5).
        val snap = deps.alerts().lastFix.value?.toSnapshot()
        if (snap == null) {
            CarToast.makeText(carContext, "Waiting for a GPS fix", CarToast.LENGTH_SHORT).show()
            return
        }
        screenManager.push(ReportTypeScreen(carContext, session, snap))
    }
}

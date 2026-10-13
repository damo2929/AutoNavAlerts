// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.model.Trip
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.nav.NavUpdatesService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Feeds the host's `NavigationManager` from the session, not a screen (DESIGN.md §9.3, §12.4):
 * `navigationStarted()` when guidance starts, `updateTrip(Trip)` on every turn-by-turn update
 * whichever of our screens is on top, a loading trip while rerouting (the cluster shows a
 * spinner, not a stale turn), and `navigationEnded()` when guidance stops. The current and next
 * step go in the trip ([TripMapper.trip]); each update is sampled into `debug-nav` every 5 s.
 */
class TripPublisher(
    private val carContext: CarContext,
    private val deps: CarDeps,
    private val imperial: () -> Boolean,
    private val eventLog: EventLog,
) {
    private var active = false
    private var job: Job? = null

    private val manager: NavigationManager get() = carContext.getCarService(NavigationManager::class.java)

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            combine(NavUpdatesService.navInfo, deps.guidance().guiding) { info, guiding -> info to guiding }
                .collect { (info, guiding) -> publish(info, guiding) }
        }
    }

    /** Stops collecting. The host's trip ends with the session; guidance itself carries on (§9.3). */
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun publish(info: NavInfo?, guiding: Boolean) {
        if (guiding && !active) {
            runCatching { manager.navigationStarted() }.onFailure { Log.w(TAG, "navigationStarted", it) }
            active = true
            log("navigationStarted")
        }
        if (!guiding && active) {
            runCatching { manager.navigationEnded() }.onFailure { Log.w(TAG, "navigationEnded", it) }
            active = false
            log("navigationEnded")
        }
        if (!guiding || info == null) return
        val trip = when (info.navState) {
            NavState.ENROUTE -> TripMapper(carContext, imperial()).trip(info, deps.guidance().destination.value?.title)
            NavState.REROUTING -> Trip.Builder().setLoading(true).build()
            else -> null
        } ?: return
        runCatching { manager.updateTrip(trip) }.onFailure { Log.w(TAG, "updateTrip", it) }
        eventLog.sample("trip.update", 5_000, EventLog.Channel.NAV, "Trip") {
            if (info.navState == NavState.REROUTING) "update loading (rerouting)"
            else "update step=\"${info.currentStep?.fullInstructionText ?: info.currentStep?.simpleRoadName ?: "-"}\" " +
                "d=${info.distanceToCurrentStepMeters ?: -1} next=\"${info.remainingSteps?.firstOrNull()?.simpleRoadName ?: "-"}\" " +
                "toDest=${info.distanceToFinalDestinationMeters ?: -1}"
        }
    }

    private fun log(msg: String) = eventLog.record(EventLog.Channel.NAV, "Trip", msg)

    private companion object { const val TAG = "TripPublisher" }
}

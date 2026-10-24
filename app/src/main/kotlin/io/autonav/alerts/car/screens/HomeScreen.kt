// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.versioning.CarAppApiLevels
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.CarToast
import androidx.car.app.model.Template
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

/** Google Maps' turn-card teal, sampled from it on the DHU (DESIGN.md §8); the same by day and night. */
private val TURN_CARD = CarColor.createCustom(0xFF07786D.toInt(), 0xFF07786D.toInt())

/**
 * The map screen: free drive, or guidance with turn cards (DESIGN.md §8). Action strip:
 * Search · Report · Recents · More (Recents leaves it while a route is set). Nothing of ours stops
 * a route: Google's X on the host's travel-estimate card does, and arrival ends guidance by itself
 * (§9.3). The host's trip feed is the session's `TripPublisher`, not this screen's (§9.3), so it
 * never stalls while another screen is up.
 *
 * It is the root screen for the whole session: `NavSession.onCreateScreen` creates it and every
 * other screen is pushed over it, so `popToRoot()` always lands on the map. A
 * `NavigationTemplate`, which keeps the map (our surface) behind it and gives the host the
 * `RoutingInfo` and `TravelEstimate` it draws as its turn card and travel-estimate card. All on
 * the main thread (the collector runs on `lifecycleScope`).
 *
 * Hard rules it upholds: one element per job (rule 2): no stop action, no message card for the
 * next camera (the banner on the map is the only alert surface, §12.1), zoom and recenter only on
 * the host's strip when the surface can't take taps; and no shake (rule 3): the template is
 * re-sent only when what the host's cards display has changed ([TripMapper.displayKey]), never
 * on every `NavInfo`.
 */
class HomeScreen(carContext: CarContext, private val session: NavSession) : Screen(carContext), DefaultLifecycleObserver {
    private val deps get() = session.deps
    /** The SDK's latest turn-by-turn update, or null with no route. */
    private var navInfo: NavInfo? = null
    private var guiding = false
    /** The last travel estimate, sent again while rerouting so the host's card and its X stay (§8). */
    private var lastEstimate: androidx.car.app.navigation.model.TravelEstimate? = null
    /** Why navigation can't run (no key, terms, permission…), shown as the set-up message card. */
    private var unavailable: NavUnavailable? = null
    private var lastInvalidate = 0L
    /** What the last sent template showed ([TripMapper.displayKey] with guiding and unavailable). */
    private var shownKey: String? = null
    private var pendingInvalidate: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    /** Collects the turn-by-turn feed, the guiding flag and the SDK's availability for the life of the screen. */
    override fun onCreate(owner: LifecycleOwner) {
        lifecycleScope.launch {
            combine(
                NavUpdatesService.navInfo, deps.guidance().guiding, deps.navigator().unavailable,
            ) { info, g, u -> Triple(info, g, u) }.collect { (info, g, u) ->
                navInfo = info
                guiding = g
                unavailable = u
                // A stopped route must not leave its last estimate for the next one (§9.3 stopGuidance resets).
                if (!g) lastEstimate = null
                // Only when what the cards show changes (DESIGN.md §8, no shake): re-sending the same
                // template every second rebuilt the host's travel-estimate card and lost presses on its X.
                val key = "$g|$u|" + (info?.let { TripMapper(carContext, session.imperial).displayKey(it) } ?: "-")
                if (key != shownKey) {
                    shownKey = key
                    invalidateThrottled()
                }
            }
        }
        lifecycleScope.launch { deps.navigator().ensureNavigator(); invalidateThrottled() }
    }

    // At most one re-send a second, and only for a change the collector has already found: a burst
    // of changed updates is folded into one re-send with the newest content, never dropped.
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

    /**
     * The `NavigationTemplate` for the current state: the set-up message when navigation can't
     * run, the turn card and travel estimate while en route, a loading turn card with the last
     * estimate while rerouting (so the host's card and its X never blink out, §8), the last
     * estimate alone before a restored route's first update, and nothing in free drive.
     */
    override fun onGetTemplate(): Template {
        val b = NavigationTemplate.Builder()
            // The turn card in Google Maps' teal (DESIGN.md §8, "Looking like Google Maps").
            .setBackgroundColor(TURN_CARD)
            .setActionStrip(actionStrip())
            .setMapActionStrip(
                // From car API 5 the map gets taps (onClick), so zoom and recenter are our own buttons
                // down the right under the satellite toggle (DESIGN.md §8); Pan stays for rotary cars.
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .apply {
                        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_5) {
                            addAction(CarUi.action(carContext, R.drawable.ic_action_zoom_in) { session.surface.zoom(1f) })
                            addAction(CarUi.action(carContext, R.drawable.ic_action_zoom_out) { session.surface.zoom(-1f) })
                            addAction(CarUi.action(carContext, R.drawable.ic_action_recenter) { session.surface.recenter() })
                        }
                    }
                    .build(),
            )
            // Pan mode is handled on the surface (onScroll turns follow off, §8 "Gestures"); the host
            // only needs a listener to offer Pan at all.
            .setPanModeListener { }

        val info = navInfo
        val mapper = TripMapper(carContext, session.imperial)
        // Told before the template goes out: the host's report that follows is read against it (DESIGN.md §8).
        session.surface.homeCard = unavailable != null || (guiding && (info != null || lastEstimate != null))
        when {
            unavailable != null -> b.setNavigationInfo(
                MessageInfo.Builder(carContext.getString(R.string.car_setup_title))
                    .setText(setupText(unavailable!!))
                    .setImage(CarUi.icon(carContext, R.drawable.ic_action_report))
                    .build(),
            )
            guiding && info != null && info.navState == NavState.ENROUTE -> {
                mapper.routingInfo(info)?.let { b.setNavigationInfo(it) }
                (mapper.travelEstimate(info) ?: lastEstimate)?.let { lastEstimate = it; b.setDestinationTravelEstimate(it) }
            }
            guiding && info?.navState == NavState.REROUTING -> {
                b.setNavigationInfo(androidx.car.app.navigation.model.RoutingInfo.Builder().setLoading(true).build())
                lastEstimate?.let { b.setDestinationTravelEstimate(it) }
            }
            // Before the first update of a route: keep the card if there was one (a restored route).
            guiding -> lastEstimate?.let { b.setDestinationTravelEstimate(it) }
        }
        return b.build()
    }

    // What the driver can do about it, on the phone: the car screen never asks for a key or terms itself (§13).
    private fun setupText(u: NavUnavailable) = when (u) {
        NavUnavailable.NO_API_KEY -> "Add your Google Maps API key in the phone app's Settings."
        NavUnavailable.TERMS_NOT_ACCEPTED -> "Open the phone app once and accept the navigation terms."
        NavUnavailable.LOCATION_PERMISSION -> "Allow location access in the phone app."
        NavUnavailable.NOT_AUTHORIZED -> "The Maps API key isn't authorised for the Navigation SDK."
        NavUnavailable.NETWORK -> "No network connection."
        NavUnavailable.UNKNOWN -> "Navigation isn't available right now."
    }

    // Every action here is persistent: the user wants the top-right buttons always on screen, not
    // faded out after a few seconds (DESIGN.md §8).
    /**
     * Search · Report · Recents · More (DESIGN.md §8). Nothing of ours stops a route: the host's own
     * travel-estimate card (bottom-left) carries Google's X, which reaches us as
     * `NavigationManagerCallback.onStopNavigation` and is the one cancel on the car screen (owner,
     * 2026-10-13). Recents leaves the strip while a route is set, to keep the screen estate; it stays
     * under More and in Search's empty state.
     */
    private fun actionStrip(): ActionStrip {
        val strip = ActionStrip.Builder()
        strip.addAction(CarUi.action(carContext, R.drawable.ic_action_search, persistent = true) {
            screenManager.push(SearchScreen(carContext, session))
        })
        strip.addAction(CarUi.action(carContext, R.drawable.ic_action_report, persistent = true) { openReport() })
        if (!guiding) {
            strip.addAction(CarUi.action(carContext, R.drawable.ic_action_recents, persistent = true) {
                screenManager.push(RecentsScreen(carContext, session))
            })
        }
        strip.addAction(CarUi.action(carContext, R.drawable.ic_action_more, persistent = true) {
            screenManager.push(MoreScreen(carContext, session))
        })
        return strip.build()
    }

    /**
     * One-tap report (DESIGN.md §8.5): saves position, direction and the best-known speed limit at
     * the moment of the tap, then asks for an optional spoken comment. Review and Send are phone-only.
     */
    private fun openReport() {
        val fix = deps.alerts().lastFix.value
        if (fix == null) {
            CarToast.makeText(carContext, "Waiting for a GPS fix", CarToast.LENGTH_SHORT).show()
            return
        }
        // Everything is captured at the moment of the tap (§8.5 step 1), before any screen or coroutine.
        val snap = fix.toSnapshot()
        val (limit, source) = session.currentLimit()
        val candidates = deps.alerts().nearby.value.map { it.poi.id to (it.poi.lat to it.poi.lon) }
        // The camera this report is about, for "not there" or "seen again": the one being alerted
        // (last 20 s), else one just passed; none for a new site.
        val related = deps.alerts().active.value?.takeIf { System.currentTimeMillis() - it.timeMs < 20_000 }?.poiId?.takeIf { it >= 0 }
            ?: deps.reports().justPassed(fix, candidates)
        lifecycleScope.launch {
            val id = deps.reports().capture(snap, limit, source, related)
            // The new "My reports" marker stays quiet until the car is 300 m away (§8.5), or it would alert at once.
            deps.reports().localPoiOf(id)?.let { deps.alerts().quietHere(it) }
            // No car microphone (car API < 5 or no permission): the report is saved without a comment (§8.5).
            if (io.autonav.alerts.reports.CarVoiceRecorder.available(carContext)) {
                screenManager.push(VoiceCommentScreen(carContext, session, id))
            } else {
                CarToast.makeText(carContext, "Camera reported. Check it on your phone after the drive.", CarToast.LENGTH_LONG).show()
            }
        }
    }
}

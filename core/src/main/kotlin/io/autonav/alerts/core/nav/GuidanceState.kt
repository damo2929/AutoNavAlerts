// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

/**
 * Everything about a route that the next one must not inherit (DESIGN.md §9.3): `stopGuidance`
 * is the only way guidance ends, and it resets all of it. Immutable; the app holds the latest.
 *
 * The app's `GuidanceController` keeps the current value and replaces it through the transition
 * functions below on each event (preview, Go, reroute, arrival, stop); `EcoReaskPolicy` reads the
 * EV re-ask fields (§8.3a). No threads of its own. Times are milliseconds on the app's clock.
 */
data class GuidanceState(
    /** Turn-by-turn guidance is running (Go was pressed, nothing has stopped it). */
    val guiding: Boolean = false,
    /** The route preview screen is up (also while guiding, when a new destination is previewed, §8). */
    val previewShowing: Boolean = false,
    /** A destination is set on the SDK (a route is drawn), whether or not guidance has started. */
    val hasDestination: Boolean = false,
    /** The final destination's arrival event has fired on this route (§8 "Arrival"). */
    val arrived: Boolean = false,
    /** Route choices offered on the preview (fastest / energy-efficient), and the chosen one. */
    val choices: Int = 0,
    val selectedIndex: Int? = null,
    val selectedEfficient: Boolean = false,
    /** The preview's explanation when the energy-efficient route couldn't be fetched (§8.3a "Fallback"). */
    val routeNote: String? = null,
    /** When Go was pressed; the EV re-ask grace runs from here (§8.3a). */
    val guidanceStartedMs: Long? = null,
    val lastEcoReaskMs: Long? = null,
    val lastAppliedToken: String? = null,
    /** A route-changed event has followed a rerouting one (§8.3a). */
    val sdkRerouteCompleted: Boolean = false,
) {
    /** A destination was set and the preview is up. */
    fun previewing(choices: Int = 1, selectedIndex: Int? = 0, selectedEfficient: Boolean = false, routeNote: String? = null) =
        copy(previewShowing = true, hasDestination = true, arrived = false, choices = choices,
            selectedIndex = selectedIndex, selectedEfficient = selectedEfficient, routeNote = routeNote)

    /** Go was pressed. */
    fun started(nowMs: Long) = copy(
        guiding = true, previewShowing = false, arrived = false,
        guidanceStartedMs = nowMs, lastEcoReaskMs = null, sdkRerouteCompleted = false,
    )

    /** The final destination's arrival event; guidance itself is stopped by `stopGuidance(ARRIVED)` (§8). */
    fun arrived() = copy(arrived = true)

    /** The SDK began a reroute: no EV re-ask until its route-changed event lands (§8.3a). */
    fun rerouting() = copy(sdkRerouteCompleted = false)

    /** A route-changed event; it only counts as a completed reroute while guiding. */
    fun routeChanged() = copy(sdkRerouteCompleted = guiding)

    /** The Routes API was asked again now. */
    fun ecoAsked(nowMs: Long) = copy(lastEcoReaskMs = nowMs)

    /** A route token was set on the SDK; the same token won't be applied twice. */
    fun tokenApplied(token: String) = copy(lastAppliedToken = token)

    /** `stopGuidance(reason)`: nothing survives. */
    fun stopped(): GuidanceState = GuidanceState()

    /**
     * Back on the route preview: the destination is cleared unless guidance is already running
     * (then this is a no-op and the route stays).
     */
    fun previewAbandoned(): GuidanceState = if (guiding) copy(previewShowing = false) else GuidanceState()
}

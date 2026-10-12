// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

/**
 * Everything about a route that the next one must not inherit (DESIGN.md §9.3): `stopGuidance`
 * is the only way guidance ends, and it resets all of it. Immutable; the app holds the latest.
 */
data class GuidanceState(
    val guiding: Boolean = false,
    val previewShowing: Boolean = false,
    val hasDestination: Boolean = false,
    val arrived: Boolean = false,
    /** Route choices offered on the preview (fastest / energy-efficient), and the chosen one. */
    val choices: Int = 0,
    val selectedIndex: Int? = null,
    val selectedEfficient: Boolean = false,
    val routeNote: String? = null,
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

    fun arrived() = copy(arrived = true)

    fun rerouting() = copy(sdkRerouteCompleted = false)

    fun routeChanged() = copy(sdkRerouteCompleted = guiding)

    fun ecoAsked(nowMs: Long) = copy(lastEcoReaskMs = nowMs)

    fun tokenApplied(token: String) = copy(lastAppliedToken = token)

    /** `stopGuidance(reason)`: nothing survives. */
    fun stopped(): GuidanceState = GuidanceState()

    /**
     * Back on the route preview: the destination is cleared unless guidance is already running
     * (then this is a no-op and the route stays).
     */
    fun previewAbandoned(): GuidanceState = if (guiding) copy(previewShowing = false) else GuidanceState()
}

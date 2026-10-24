// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

/**
 * When to ask the Routes API for an energy-efficient route again (DESIGN.md §8.3a): only while
 * guiding on the efficient route, after a grace period from guidance starting, at most once a
 * minute, and only after the SDK's own reroute has completed. A token already applied is skipped.
 *
 * Pure and stateless: the app's `GuidanceController` calls [decide] on its main-thread scope with
 * the values from [GuidanceState], on each off-route or route-changed event. Times are milliseconds
 * on the app's clock; only differences are used. Every Skip carries its reason for the log, since
 * a re-ask racing the SDK's reroute was the prime suspect for the route clearing on the first
 * real-car drive (§16 row 22).
 */
class EcoReaskPolicy(
    // 60 s grace after Go, then at most once a minute (§8.3a "Re-asking").
    private val graceMs: Long = 60_000,
    private val minGapMs: Long = 60_000,
) {
    /** [Skip] with a reason to log, or [Ask]. */
    sealed interface Decision {
        data class Skip(val reason: String) : Decision
        data object Ask : Decision
    }

    /**
     * @param newToken the token the Routes API returned, when deciding whether to apply it; null
     *   when deciding whether to ask at all.
     */
    /**
     * @param guidanceStartedMs when Go was pressed, null before.
     * @param lastReaskMs when the Routes API was last asked, null if never on this route.
     * @param selectedEfficient the driver chose the energy-efficient route on the preview.
     * @param sdkRerouteCompleted a route-changed event has followed the SDK's rerouting one.
     * @param lastAppliedToken the route token last set on the SDK, compared with [newToken].
     */
    fun decide(
        nowMs: Long,
        guidanceStartedMs: Long?,
        lastReaskMs: Long?,
        guiding: Boolean,
        selectedEfficient: Boolean,
        sdkRerouteCompleted: Boolean,
        newToken: String? = null,
        lastAppliedToken: String? = null,
    ): Decision {
        if (!guiding) return Decision.Skip("not guiding")
        if (!selectedEfficient) return Decision.Skip("fastest selected")
        if (guidanceStartedMs == null) return Decision.Skip("guidance not started")
        val sinceStart = nowMs - guidanceStartedMs
        if (sinceStart < graceMs) return Decision.Skip("grace (${sinceStart / 1000} s since start)")
        if (!sdkRerouteCompleted) return Decision.Skip("SDK reroute not completed")
        if (lastReaskMs != null && nowMs - lastReaskMs < minGapMs) return Decision.Skip("asked ${(nowMs - lastReaskMs) / 1000} s ago")
        if (newToken != null && newToken == lastAppliedToken) return Decision.Skip("same token")
        return Decision.Ask
    }
}

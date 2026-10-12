// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

/**
 * When to ask the Routes API for an energy-efficient route again (DESIGN.md §8.3a): only while
 * guiding on the efficient route, after a grace period from guidance starting, at most once a
 * minute, and only after the SDK's own reroute has completed. A token already applied is skipped.
 */
class EcoReaskPolicy(
    private val graceMs: Long = 60_000,
    private val minGapMs: Long = 60_000,
) {
    sealed interface Decision {
        data class Skip(val reason: String) : Decision
        data object Ask : Decision
    }

    /**
     * @param newToken the token the Routes API returned, when deciding whether to apply it; null
     *   when deciding whether to ask at all.
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

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.nav.EcoReaskPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The EV re-ask rules (DESIGN.md §8.3a). */
class EcoReaskPolicyTest {
    private val p = EcoReaskPolicy()

    private fun decide(
        now: Long, started: Long? = 0, last: Long? = null, guiding: Boolean = true, efficient: Boolean = true,
        rerouted: Boolean = true, token: String? = null, applied: String? = null,
    ) = p.decide(now, started, last, guiding, efficient, rerouted, token, applied)

    @Test
    fun graceAfterGuidanceStarts() {
        assertEquals(Decision.Skip("grace (0 s since start)"), decide(0))
        assertEquals(Decision.Skip("grace (59 s since start)"), decide(59_999))
        assertEquals(Decision.Ask, decide(60_000))
        assertTrue(decide(70_000, started = null) is Decision.Skip)
    }

    @Test
    fun atMostOnceAMinute() {
        assertEquals(Decision.Ask, decide(100_000, last = null))
        assertEquals(Decision.Skip("asked 30 s ago"), decide(100_000, last = 70_000))
        assertEquals(Decision.Ask, decide(130_000, last = 70_000))
    }

    @Test
    fun fastestSelectedSkips() {
        assertEquals(Decision.Skip("fastest selected"), decide(100_000, efficient = false))
    }

    @Test
    fun notGuidingSkips() {
        assertEquals(Decision.Skip("not guiding"), decide(100_000, guiding = false))
        // Not guiding wins over every other reason: nothing may race a route that isn't running.
        assertEquals(Decision.Skip("not guiding"), decide(0, guiding = false, efficient = false))
    }

    @Test
    fun onlyAfterTheSdkRerouteCompleted() {
        assertEquals(Decision.Skip("SDK reroute not completed"), decide(100_000, rerouted = false))
        assertEquals(Decision.Ask, decide(100_000, rerouted = true))
    }

    @Test
    fun aTokenAlreadyAppliedIsSkipped() {
        assertEquals(Decision.Skip("same token"), decide(100_000, token = "abc", applied = "abc"))
        assertEquals(Decision.Ask, decide(100_000, token = "abd", applied = "abc"))
        assertEquals(Decision.Ask, decide(100_000, token = "abc", applied = null))
    }
}

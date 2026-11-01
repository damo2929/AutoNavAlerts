// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidance starts only on a route the SDK confirmed (DESIGN.md §9.3, 2026-11-01). */
class RouteReadinessTest {
    // Distinct objects with equal content: readiness goes by identity, like the SDK's Waypoint.
    private class Dest(val name: String)

    @Test
    fun notReadyUntilItsOwnRouteIsOk() {
        val r = RouteReadiness<Dest>()
        val stansted = Dest("Stansted")
        // The destination is set before its route result: Go then must not start guidance (bench 2026-11-01).
        assertFalse(r.isReadyFor(stansted))
        assertFalse(r.isReadyFor(null))
        r.onResult(stansted, ok = true)
        assertTrue(r.isReadyFor(stansted))
        assertFalse(r.isReadyFor(Dest("Stansted")))
    }

    @Test
    fun aRefusedRouteClearsItAndARecoveryRestoresIt() {
        val r = RouteReadiness<Dest>()
        val d = Dest("Derby")
        r.onResult(d, ok = true)
        r.onResult(d, ok = false)
        assertFalse(r.isReadyFor(d))
        r.onResult(d, ok = true)
        assertTrue(r.isReadyFor(d))
    }

    @Test
    fun aNewDestinationWaitsForItsOwnResultAndAnOldFailureDoesNotClearIt() {
        val r = RouteReadiness<Dest>()
        val old = Dest("Banbury")
        val new = Dest("Stansted")
        r.onResult(old, ok = true)
        // The new destination is not ready while the old route is still the one set.
        assertFalse(r.isReadyFor(new))
        r.onResult(new, ok = true)
        // A late failure for the old destination doesn't touch the new route.
        r.onResult(old, ok = false)
        assertTrue(r.isReadyFor(new))
        r.clear()
        assertFalse(r.isReadyFor(new))
    }
}

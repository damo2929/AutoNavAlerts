// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

/**
 * Whether guidance may start: only on a route the Navigation SDK confirmed for the destination being
 * guided to (DESIGN.md §9.3 "Guidance starts only on a route the SDK confirmed", owner 2026-11-01, as
 * Google's own sample starts guidance only on `RouteStatus.OK`). Remembers the destination its last route
 * result was OK for; a result that isn't OK for that destination clears it, and a new destination is not
 * ready until its own result is OK. A request still in flight for the same destination (the preview's
 * fastest / energy-efficient switch) leaves the route already set usable. [T] is the destination's
 * identity (the SDK's `Waypoint`, compared by identity). Main thread, like `GuidanceController`.
 */
class RouteReadiness<T : Any> {
    private var readyFor: T? = null

    /** A route result for [destination]: OK makes it ready; anything else clears it if it was the ready one. */
    fun onResult(destination: T, ok: Boolean) {
        if (ok) readyFor = destination else if (readyFor === destination) readyFor = null
    }

    /** True when the last route result for [destination] was OK. */
    fun isReadyFor(destination: T?): Boolean = destination != null && readyFor === destination

    /** No route any more (a stop or an abandoned preview). */
    fun clear() {
        readyFor = null
    }
}

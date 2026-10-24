// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.view.View

/**
 * Google's live incident reporting (crashes, congestion, police, roadworks…) on the car screen
 * (DESIGN.md §16 #21).
 *
 * As of Navigation SDK 8.0.0 this exists only on the phone views (NavigationView,
 * SupportNavigationFragment); Google's docs say "real-time disruptions are not available on
 * Android Auto". So this looks the methods up by reflection on NavigationViewForAuto: if a future
 * SDK adds them, the feature switches on with no code change, and stays hidden until then.
 *
 * Used by `MapSurface` (`incidentReportingSupported`, `incidentReportingAvailableNow`,
 * `showIncidentPanel`) for More's "Report to Google Maps" row, which `MoreScreen` only adds when
 * [supportedBySdk] is true: nothing is shown on the car screen that doesn't work (hard rule 2).
 * Stateless; called on the main thread with the SDK's car view. The quarterly SDK feature watch
 * (TESTING.md §6.10) is where a change would first be noticed.
 */
object IncidentReporting {
    /** True if this SDK's car view has the reporting API at all. */
    fun supportedBySdk(view: View): Boolean =
        runCatching { view.javaClass.getMethod("showReportIncidentsPanel") }.isSuccess

    /** True when reporting can be used right now (active navigation, supported country). */
    fun availableNow(view: View): Boolean = runCatching {
        view.javaClass.getMethod("isIncidentReportingAvailable").invoke(view) as? Boolean == true
    }.getOrDefault(false)

    /** Opens Google's report panel on the car screen. Returns false if not available. */
    fun showPanel(view: View): Boolean = runCatching {
        if (!availableNow(view)) return false
        view.javaClass.getMethod("showReportIncidentsPanel").invoke(view) as? Boolean ?: true
    }.getOrDefault(false)
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.autonav.alerts.alerts.AlertCoordinator
import io.autonav.alerts.alerts.AlertDispatcher
import io.autonav.alerts.alerts.NavTts
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.nav.AdaptiveLocationSource
import io.autonav.alerts.nav.GuidanceController
import io.autonav.alerts.nav.NavigatorHolder
import io.autonav.alerts.places.PlacesSearch
import io.autonav.alerts.reports.CameraReportRepository
import io.autonav.alerts.settings.SettingsStore

/**
 * The car side's view of the app's Hilt singletons (DESIGN.md §2 "Process model": one process, so
 * the car service and the phone UI share these). Hilt can't inject Car App Library sessions and
 * screens, which the host constructs, so `NavSession` fetches this once in `onCreateScreen` and
 * every screen reads it as `session.deps`. Each accessor returns the one application-scoped
 * instance; nothing here is created or released by the car session.
 *
 * Threads: the accessors are plain lookups and are safe from any thread. What the returned
 * objects need is their own business (§2): Room and network on `Dispatchers.IO`, the alert
 * engine on its own thread, the Car App Library and map calls on the main thread.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CarDeps {
    /** The lazily created Navigation SDK `Navigator`, the terms state and the Maps key hand-over (§3). */
    fun navigator(): NavigatorHolder
    /** Destinations, route choice, start and stop of guidance, reroute events (§3, §9.3). */
    fun guidance(): GuidanceController
    /** Fused and car location at the phone's own rate (§10). */
    fun location(): AdaptiveLocationSource
    /** The alert engine's front end: nearby POIs, verdicts, the active alert, per-set mute (§11, §12). */
    fun alerts(): AlertCoordinator
    /** Routes alert events to the banner, the notification, speech and the cluster (§12). */
    fun dispatcher(): AlertDispatcher
    /** Text-to-speech on the navigation audio channel (§12.3). */
    fun tts(): NavTts
    /** Built-in and user icons, with the bitmap cache the markers and banner draw from (§7, §8.1). */
    fun icons(): IconRepository
    /** POI sets and their Room access (§4). */
    fun pois(): PoiRepository
    /** Places autocomplete, text search and resolution, plus recents and favourites (§8.2). */
    fun places(): PlacesSearch
    /** The camera-report queue and the local "My reports" set (§8.5). */
    fun reports(): CameraReportRepository
    /** The DataStore-backed settings (§13). */
    fun settings(): SettingsStore
    /** Car speed and car GPS from the head unit (§10.6, §10.7). */
    fun carData(): io.autonav.alerts.nav.CarDataSource
    /** Whether the phone is connected to a car or moving, which blocks report sending (§8.5). */
    fun driving(): io.autonav.alerts.reports.DrivingState
    /** Contacts' postal addresses and their geocode cache (§8.3). */
    fun contacts(): io.autonav.alerts.contacts.ContactsSource
    /** OpenStreetMap limits from the downloaded file and the limit-change lookahead (§8.4a). */
    fun speedLimits(): io.autonav.alerts.osm.SpeedLimitRepository
    /** The two batched debug logs, `debug-nav` and `alert-debug` (§15.5). */
    fun eventLog(): io.autonav.alerts.diag.EventLog
    /** The opt-in CSV drive recorder for replay (§15.5). */
    fun driveRecorder(): io.autonav.alerts.diag.DriveRecorder

    companion object {
        /** The entry point for any [context]; the application context is used, so the caller's lifetime doesn't matter. */
        fun from(context: Context): CarDeps = EntryPointAccessors.fromApplication(context.applicationContext, CarDeps::class.java)
    }
}

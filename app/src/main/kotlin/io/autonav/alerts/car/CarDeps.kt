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

/** Hilt can't inject Car App Library sessions/screens, so they read singletons through this. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CarDeps {
    fun navigator(): NavigatorHolder
    fun guidance(): GuidanceController
    fun location(): AdaptiveLocationSource
    fun alerts(): AlertCoordinator
    fun dispatcher(): AlertDispatcher
    fun tts(): NavTts
    fun icons(): IconRepository
    fun pois(): PoiRepository
    fun places(): PlacesSearch
    fun reports(): CameraReportRepository
    fun settings(): SettingsStore
    fun carData(): io.autonav.alerts.nav.CarDataSource
    fun driving(): io.autonav.alerts.reports.DrivingState
    fun contacts(): io.autonav.alerts.contacts.ContactsSource
    fun speedLimits(): io.autonav.alerts.osm.SpeedLimitRepository

    companion object {
        fun from(context: Context): CarDeps = EntryPointAccessors.fromApplication(context.applicationContext, CarDeps::class.java)
    }
}

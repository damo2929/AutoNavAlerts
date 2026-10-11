// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.autonav.alerts.alerts.AlertDispatcher
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.pgw.PgwScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var dispatcher: AlertDispatcher
    @Inject lateinit var pois: PoiRepository
    @Inject lateinit var pgwScheduler: PgwScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        dispatcher.ensureChannel()
        appScope.launch {
            pois.cleanupStaging()
            pois.purgeExpired()
            pgwScheduler.schedule()
        }
    }

    companion object {
        /** Long-lived scope for work that outlives a screen (alert engine, sync triggers). */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

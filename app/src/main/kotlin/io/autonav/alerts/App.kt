// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.autonav.alerts.alerts.AlertDispatcher
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.diag.DriveRecorder
import io.autonav.alerts.diag.EventLog
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
    @Inject lateinit var driving: io.autonav.alerts.reports.DrivingState
    @Inject lateinit var eventLog: EventLog
    /** Created with the process so it follows the `driveRecordingOn` setting from the start (DESIGN.md §15.5). */
    @Inject lateinit var driveRecorder: DriveRecorder
    /** The voice language, applied before anything speaks or routes (DESIGN.md §12.3). */
    @Inject lateinit var voiceLocale: io.autonav.alerts.alerts.VoiceLocale

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // One per process in both debug logs: a second one between a session's create and destroy means the process died.
        eventLog.recordAll(
            "App",
            "start build=${BuildConfig.GIT_COMMIT} version=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG} " +
                "android=${Build.VERSION.SDK_INT} model=${Build.MODEL} pid=${android.os.Process.myPid()}",
        )
        voiceLocale.start()
        dispatcher.ensureChannel()
        driving.observe()
        appScope.launch {
            pois.cleanupStaging()
            pois.purgeExpired()
            pgwScheduler.schedule()
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Memory pressure may be followed by a kill: get the pending 15 s onto disk (DESIGN.md §15.5).
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            eventLog.record(EventLog.Channel.NAV, "App", "trimMemory level=$level")
            // Only a kill-level warning holds the main thread for the write.
            eventLog.flush("trimMemory", wait = level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }
    }

    companion object {
        /** Long-lived scope for work that outlives a screen (alert engine, sync triggers). */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

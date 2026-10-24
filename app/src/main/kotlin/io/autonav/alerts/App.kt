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

/**
 * The one Android `Application` of the single-process app (DESIGN.md §2, §3): Hilt's root, so the
 * car service, the WorkManager workers and the phone UI all share the same
 * singletons (`NavigatorHolder`, `PoiRepository`, `AlertDispatcher`, `EventLog`, ...).
 *
 * At start-up, on the main thread, before any Activity, Service or Worker runs: writes the
 * `App start` line to both debug logs, creates the alert notification channel, starts watching
 * the Android Auto connection for the report gate (DESIGN.md §8.5), and on [appScope] tidies the
 * POI database (staging sets left by an interrupted import, DESIGN.md §4.4; expired pending
 * mobile sites) and arms the PocketGPSWorld update schedule (DESIGN.md §6.2).
 *
 * Lifecycle: created once per process by Android and never destroyed. A second `App start`
 * between a car session's create and destroy in the logs means the process died (DESIGN.md §15.5).
 *
 * Nothing here touches the Navigation SDK or Maps: there is no built-in key, and no SDK call may
 * happen before `NavigatorHolder.applyApiKey()` (AGENTS.md §3.1 rule 5; DESIGN.md §1.4).
 */
@HiltAndroidApp
class App : Application(), Configuration.Provider {
    /** Builds the WorkManager workers (PGW feed check and sync, report submit) with their Hilt dependencies. */
    @Inject lateinit var workerFactory: HiltWorkerFactory
    /** Owns the alert notification channel, which must exist before the first alert (DESIGN.md §12.2). */
    @Inject lateinit var dispatcher: AlertDispatcher
    /** The POI database; tidied at start-up (staging sets, expired POIs). */
    @Inject lateinit var pois: PoiRepository
    /** Arms the daily PocketGPSWorld feed check and the sync it queues (DESIGN.md §6.2). */
    @Inject lateinit var pgwScheduler: PgwScheduler
    /** Watches the Android Auto connection so camera reports are never sent while connected (DESIGN.md §8.5). */
    @Inject lateinit var driving: io.autonav.alerts.reports.DrivingState
    /** The two always-on debug logs (DESIGN.md §15.5). */
    @Inject lateinit var eventLog: EventLog
    /** Created with the process so it follows the `driveRecordingOn` setting from the start (DESIGN.md §15.5). */
    @Inject lateinit var driveRecorder: DriveRecorder

    /**
     * WorkManager's configuration. The manifest removes the default `WorkManagerInitializer`, so
     * WorkManager asks here instead and the workers come from [workerFactory] with their
     * dependencies injected.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /**
     * Process start-up. Only cheap main-thread work runs inline (the log line, the channel, the
     * connection observer); the database tidy-up and the schedule run on [appScope].
     */
    override fun onCreate() {
        super.onCreate()
        // One per process in both debug logs: a second one between a session's create and destroy means the process died.
        eventLog.recordAll(
            "App",
            "start build=${BuildConfig.GIT_COMMIT} version=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG} " +
                "android=${Build.VERSION.SDK_INT} model=${Build.MODEL} pid=${android.os.Process.myPid()}",
        )
        // The channel must exist before the first alert or report notification (DESIGN.md §12.2).
        dispatcher.ensureChannel()
        // Starts the CarConnection observer that gates report sending (DESIGN.md §8.5; AGENTS.md §3.1 rule 8).
        driving.observe()
        // Room work, off the main thread: staging sets left by an interrupted import go (DESIGN.md §4.4),
        // expired pending mobile sites are purged, and the PGW feed check is (re)scheduled (DESIGN.md §6.2).
        appScope.launch {
            pois.cleanupStaging()
            pois.purgeExpired()
            pgwScheduler.schedule()
        }
    }

    /**
     * Memory pressure, from Android on the main thread. A low-memory warning often precedes a kill,
     * so the debug logs' pending 15 s batch is written out (DESIGN.md §15.5). `TRIM_MEMORY_COMPLETE`
     * is the last warning before a kill, so only then is the write waited for; the lower levels
     * return at once so the main thread isn't held for disk I/O.
     */
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
        /**
         * Long-lived scope for work that outlives a screen (alert engine, sync triggers, the
         * remember-on-Go write, bench commands). `SupervisorJob`: one failed job never cancels the
         * rest. `Dispatchers.Default`: never the main thread (DESIGN.md §2 threads); switch to
         * `Dispatchers.IO` inside it for Room and network.
         */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

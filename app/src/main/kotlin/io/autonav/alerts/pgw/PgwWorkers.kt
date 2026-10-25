// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.pgw

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.BuildConfig
import io.autonav.alerts.R
import io.autonav.alerts.core.pgw.PgwConfig
import io.autonav.alerts.core.pgw.PgwPages
import io.autonav.alerts.core.pgw.PgwUpdateFeed
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.data.ReportStatus
import io.autonav.alerts.nav.GuidanceController
import io.autonav.alerts.reports.CameraReportRepository
import io.autonav.alerts.reports.DrivingState
import io.autonav.alerts.settings.PgwState
import io.autonav.alerts.settings.SettingsStore
import io.autonav.alerts.util.notifyIfAllowed
import io.autonav.alerts.util.network
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the PocketGPSWorld WorkManager jobs (DESIGN.md §6.2, §6.2a): the daily feed check and the one-off
 * download. A Hilt singleton. `App` calls [schedule] at startup and the phone's PGW account screen calls it
 * again when the login or the auto-sync switch changes; the feed check calls [queueSync]; the phone's
 * **Sync now** and **Force re-download** buttons call [syncNow]. Unique work names keep one of each job.
 * Switching auto-sync off, or removing the login, cancels the feed check: no login, no automation (§6.4).
 */
@Singleton
class PgwScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    private val wm get() = WorkManager.getInstance(context)

    /** (Re)schedules the daily feed check, or cancels it when auto-sync is off or there is no saved login. */
    suspend fun schedule() {
        val s = settings.current()
        if (!s.pgwAutoSync || settings.pgwCredentials() == null) {
            wm.cancelUniqueWork(FEED_WORK)
            return
        }
        // §6.2: every 24 h with a 4 h flex window, any network (the feed is tiny), exponential backoff from 1 h on failure.
        val req = PeriodicWorkRequestBuilder<PgwFeedCheckWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS)
            .setConstraints(network().build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        // KEEP: a job already scheduled keeps its timing; rescheduling never resets the clock.
        wm.enqueueUniquePeriodicWork(FEED_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /**
     * Sync now (checks the feed first, downloads only if newer) or force a re-download without the check
     * (§6.2 "Sync now … Force re-download"). Any network; REPLACE so a pressed button always runs.
     */
    fun syncNow(force: Boolean) {
        val req = OneTimeWorkRequestBuilder<PgwSyncWorker>()
            .setInputData(workDataOf(PgwSyncWorker.KEY_FORCE to force, PgwSyncWorker.KEY_CHECK_FEED to !force))
            .setConstraints(network().build())
            .build()
        wm.enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * The automatic download queued by the feed check (§6.2): any network unless the user set Wi-Fi only,
     * battery not low, exponential backoff from 1 h (the worker gives up after 3 attempts, §6.2 "Backoff"),
     * KEEP so a download already queued or running isn't restarted.
     */
    suspend fun queueSync() {
        val wifiOnly = settings.current().pgwWifiOnly
        val req = OneTimeWorkRequestBuilder<PgwSyncWorker>()
            .setConstraints(network(wifiOnly).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.KEEP, req)
    }

    companion object {
        /** Unique work names: one feed check and one sync at a time. */
        const val FEED_WORK = "pgw-feed-check"
        const val SYNC_WORK = "pgw-sync"
    }
}

/** The feed's newest release recorded as the latest known (§6.2): what the phone's status shows, and what a sync compares with the installed version. */
private fun PgwState.withLatest(r: FeedResult.Releases) = copy(latestVersion = r.newest?.version, latestSummary = r.newest?.summary)

/**
 * Daily conditional GET of the RSS feed; queues a download on a new release (DESIGN.md §6.2, §6.2a). A
 * WorkManager periodic job built by Hilt (`@HiltWorker`), scheduled by [PgwScheduler.schedule]; runs on
 * WorkManager's background thread, with the network call on Dispatchers.IO inside `PgwClient`. The feed is
 * anonymous and public (§6.4), so this job never logs in.
 */
@HiltWorker
class PgwFeedCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val client: PgwClient,
    private val settings: SettingsStore,
    private val scheduler: PgwScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val state = settings.currentPgw()
        // §6.3 "RSS feed unreachable": fail quietly, retry with backoff, and let the 14-day safety net still sync.
        val r = runCatching { client.checkFeed(state.feedEtag, state.feedLastModified) }
            .getOrElse { Log.w(TAG, "Feed check failed: ${it.message}"); return safetyNet(state.lastSyncMs).let { Result.retry() } }
        val now = System.currentTimeMillis()
        when (r) {
            FeedResult.NotModified -> settings.updatePgw { it.copy(lastFeedCheckMs = now) }
            is FeedResult.Releases -> {
                val newest = r.newest
                settings.updatePgw { it.withLatest(r).copy(lastFeedCheckMs = now, feedEtag = r.etag, feedLastModified = r.lastModified) }
                // A download is due when the feed is newer than what's installed (a first run counts as newer), or
                // the user switched package (OV2 ↔ iGO), or the import layout changed and the sets must be rebuilt (§5.2).
                val wanted = settings.current().pgwDownload.idPackage
                if (newest != null && (newest.isNewerThan(state.installedVersion) ||
                        state.installedPackage != wanted || state.installedLayout != PgwSyncWorker.IMPORT_LAYOUT)) {
                    settings.updatePgw { it.copy(pendingVersion = newest.version) }
                    scheduler.queueSync()
                    return Result.success()
                }
            }
        }
        safetyNet(state.lastSyncMs)
        return Result.success()
    }

    /** Sync anyway if nothing has succeeded for 14 days (§6.2 "Safety net": a feed that stopped, or a missed item). */
    private suspend fun safetyNet(lastSyncMs: Long) {
        if (System.currentTimeMillis() - lastSyncMs > 14 * 24 * 3_600_000L) scheduler.queueSync()
    }

    companion object { private const val TAG = "PgwFeedCheck" }
}

/**
 * The automatic update (DESIGN.md §6.2a): log in if needed → download the user's zip → read its version →
 * import into staging and swap (`PoiRepository.importPgwZip`, §4.4) → record the installed version and post a
 * quiet notification. A one-off WorkManager job built by Hilt, queued by [PgwScheduler.queueSync] (from the feed
 * check) or [PgwScheduler.syncNow] (the phone's buttons, with [KEY_FORCE] / [KEY_CHECK_FEED]). Runs on
 * WorkManager's background thread; the network and Room work is on Dispatchers.IO inside the callees.
 * Failures follow the table in §6.3. Hard rule 6 (AGENTS.md §3.1): the zip is the user's own download, kept in
 * private cache only until imported, then deleted.
 */
@HiltWorker
class PgwSyncWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val client: PgwClient,
    private val settings: SettingsStore,
    private val repo: PoiRepository,
    /** True while guidance runs (`GuidanceController.guiding`); a JVM test passes its own (§15.2). */
    private val guiding: () -> Boolean,
) : CoroutineWorker(context, params) {
    @AssistedInject constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        client: PgwClient,
        settings: SettingsStore,
        repo: PoiRepository,
        guidance: GuidanceController,
    ) : this(context, params, client, settings, repo, { guidance.guiding.value })

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val now = System.currentTimeMillis()
        // Sync now: a fresh (unconditional) feed read, then stop if the installed data is already that version
        // and was built for the chosen package and this import layout (§6.2 "Sync now checks the feed").
        if (inputData.getBoolean(KEY_CHECK_FEED, false)) {
            runCatching { client.checkFeed(null, null) }.getOrNull()?.let { r ->
                if (r is FeedResult.Releases) settings.updatePgw { it.withLatest(r).copy(pendingVersion = r.newest?.version) }
            }
            val st = settings.currentPgw()
            val samePackage = st.installedPackage == settings.current().pgwDownload.idPackage && st.installedLayout == IMPORT_LAYOUT
            if (!force && samePackage && st.installedVersion != null && st.latestVersion != null && st.latestVersion == st.installedVersion) {
                settings.updatePgw { it.copy(lastResult = "Already up to date (${st.installedVersion})", lastSyncMs = now) }
                return Result.success()
            }
        }
        // Don't swap cameras mid-journey (DESIGN.md §6.2): retry shortly after guidance ends.
        // (The whole job waits, not only the swap; §6.2 would let the download and staging go ahead.)
        if (guiding()) return Result.retry()

        val zip = File(client.cacheDir(), "pgw-${now}.zip")
        return try {
            val pkg = settings.current().pgwDownload
            client.download(zip, pkg)
            // The installed version comes from Release.txt in the zip, the source of truth (§5.2), with the
            // feed's version as the fallback (§6.2a step 3). ZipFile reads the central directory (§5.2 "Reading the zip").
            val release = ZipFile(zip).use { z ->
                z.getEntry(PgwConfig.RELEASE_FILE)?.let { e -> z.getInputStream(e).bufferedReader().readText() }
            }
            val version = release?.let(PgwUpdateFeed::versionFromReleaseTxt)?.let { "${it.first}.${it.second}" }
                ?: settings.currentPgw().pendingVersion ?: "unknown"
            val outcome = repo.importPgwZip(zip, version)
            // §6.3 "Import fails validation": the staging set was discarded by the repository; the old data stays.
            if (outcome.failed) {
                settings.updatePgw { it.copy(lastResult = outcome.message, lastSyncMs = now) }
                return Result.failure()
            }
            val summary = settings.currentPgw().latestSummary
            settings.updatePgw { it.copy(installedVersion = version, installedPackage = pkg.idPackage, installedLayout = IMPORT_LAYOUT, pendingVersion = null, lastResult = "Updated to $version: ${outcome.message}", lastSyncMs = now) }
            // §6.1 step 4: a quiet notification with the feed's own summary of the release.
            notify("Camera database updated to $version", listOfNotNull(summary, outcome.message).joinToString(" "))
            Result.success()
        } catch (e: PgwAuthException) {
            // §6.3 "Wrong credentials": no retry (the next feed check will requeue once the login is fixed); notify once.
            failed("Login problem: ${e.message}", "PocketGPSWorld login failed" to "Open AutoNavAlerts to fix your login.")
        } catch (e: PgwSubscriptionException) {
            // §6.2a "Expired subscription": nothing more is downloaded until the user acts.
            failed("Subscription required: ${e.message}", "PocketGPSWorld subscription needed" to (e.message ?: ""))
        } catch (e: PgwParseException) {
            // §6.3 "Site layout changed": keep the old data; the phone's status line points at manual import.
            failed("Site changed: ${e.message}. Use manual import.")
        } catch (e: Exception) {
            // §6.3 "Network/timeout": WorkManager's backoff (1 h, exponential), 3 attempts in all (§6.2 "Backoff").
            failed("Network problem: ${e.message}", retry = runAttemptCount < 3)
        } finally {
            // Debug builds keep the last download for diagnosis (adb pull …/cache/pgw/last.zip).
            if (BuildConfig.DEBUG && zip.exists()) zip.copyTo(File(client.cacheDir(), "last.zip"), overwrite = true)
            // §6.1 step 2.4: the zip is deleted after import, whatever happened.
            zip.delete()
        }
    }

    /** A sync that didn't get there (§6.3): [result] goes on the phone's status line, then [notice] (title to text) is shown if there is one; [retry] for a transient failure. */
    private suspend fun failed(result: String, notice: Pair<String, String>? = null, retry: Boolean = false): Result {
        settings.updatePgw { it.copy(lastResult = result) }
        notice?.let { (title, text) -> notify(title, text) }
        return if (retry) Result.retry() else Result.failure()
    }

    /** The result notification on its own low-importance channel (§6.1 step 4 "quiet"); skipped without the notification permission. */
    private fun notify(title: String, text: String) {
        val ctx = applicationContext
        notifyIfAllowed(ctx, CHANNEL, "Camera database", NotificationManager.IMPORTANCE_LOW) { nm ->
            nm.notify(30_001, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_action_report)
                .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).build())
        }
    }

    companion object {
        /**
         * Bump when the way PGW zips are turned into sets changes; the feed check then forces a re-download so
         * the sets are rebuilt (§5.2 "IMPORT_LAYOUT forces a re-download"). History: 2 = one set per type file;
         * 5 = one set per file, with the icon supplied for each file (§5.2a).
         */
        const val IMPORT_LAYOUT = 5 // 5 = one set per file, with the icon supplied for each file
        /** Input data keys set by [PgwScheduler.syncNow]: skip the version check; read the feed first. */
        const val KEY_FORCE = "force"
        const val KEY_CHECK_FEED = "check_feed"
        /** Notification channel for sync results. */
        const val CHANNEL = "pgw_sync"
    }
}

/**
 * Sends the reviewed, queued reports to PocketGPSWorld (DESIGN.md §8.5). A one-off WorkManager job built by Hilt,
 * only ever queued by the phone's Send and Send all buttons ([enqueue]); there is no auto-send. Hard rule 8
 * (AGENTS.md §3.1): it refuses to run while the phone is on Android Auto or has moved in the last 2 minutes
 * (`DrivingState`) and retries later, so nothing is ever sent from the car or while driving.
 */
@HiltWorker
class ReportSubmitWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val client: PgwClient,
    private val reports: CameraReportRepository,
    private val driving: DrivingState,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Never send while driving (DESIGN.md §8.5): wait and retry once stopped.
        if (driving.blockedReason() != null) return Result.retry()
        for (r in reports.pending()) {
            reports.update(r.copy(status = ReportStatus.SENDING))
            // idCam from the related camera's PGW id when the report is about a known camera (§8.5 "Camera IDs").
            val res = runCatching { client.submit(reports.toSubmissionWithId(r)) }
            // PGW's confirmation wording isn't known yet (DESIGN.md §16 #19): a logged-in 200 response
            // counts as sent, and the user can check it on the PGW site.
            val ok = res.getOrNull()?.let { PgwPages.loginState(it) == PgwPages.LoginState.LOGGED_IN } == true
            // A failed report is left FAILED for the user to resend or open in the browser (§8.5 "Fallback"); the job itself doesn't retry it.
            if (ok) reports.markSent(r) else reports.update(r.copy(
                status = ReportStatus.FAILED,
                lastError = res.exceptionOrNull()?.message ?: "Not accepted (logged out?)",
            ))
        }
        return Result.success()
    }

    companion object {
        /**
         * Queues a send from the phone's Reports tab. APPEND_OR_REPLACE: a press while a job is waiting chains a
         * new run after it, so reports queued meanwhile are picked up; linear backoff of 2 minutes for the
         * "still driving" retries.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "pgw-report-submit", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ReportSubmitWorker>()
                    .setConstraints(network().build())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}

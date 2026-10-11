// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.pgw

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.R
import io.autonav.alerts.core.pgw.PgwConfig
import io.autonav.alerts.core.pgw.PgwUpdateFeed
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.nav.GuidanceController
import io.autonav.alerts.settings.SettingsStore
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules the daily feed check (DESIGN.md §6.2). */
@Singleton
class PgwScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    private val wm get() = WorkManager.getInstance(context)

    suspend fun schedule() {
        val s = settings.current()
        if (!s.pgwAutoSync || settings.pgwCredentials() == null) {
            wm.cancelUniqueWork(FEED_WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<PgwFeedCheckWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(FEED_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Sync now (checks the feed first) or force a re-download. */
    fun syncNow(force: Boolean) {
        val req = OneTimeWorkRequestBuilder<PgwSyncWorker>()
            .setInputData(workDataOf(PgwSyncWorker.KEY_FORCE to force, PgwSyncWorker.KEY_CHECK_FEED to !force))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.REPLACE, req)
    }

    suspend fun queueSync() {
        val wifiOnly = settings.current().pgwWifiOnly
        val req = OneTimeWorkRequestBuilder<PgwSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.KEEP, req)
    }

    companion object {
        const val FEED_WORK = "pgw-feed-check"
        const val SYNC_WORK = "pgw-sync"
    }
}

/** Daily conditional GET of the RSS feed; queues a download on a new release (DESIGN.md §6.2). */
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
        val r = runCatching { client.checkFeed(state.feedEtag, state.feedLastModified) }
            .getOrElse { Log.w(TAG, "Feed check failed: ${it.message}"); return safetyNet(state.lastSyncMs).let { Result.retry() } }
        val now = System.currentTimeMillis()
        when (r) {
            FeedResult.NotModified -> settings.updatePgw { it.copy(lastFeedCheckMs = now) }
            is FeedResult.Releases -> {
                val newest = r.newest
                settings.updatePgw {
                    it.copy(lastFeedCheckMs = now, feedEtag = r.etag, feedLastModified = r.lastModified,
                        latestVersion = newest?.version, latestSummary = newest?.summary)
                }
                if (newest != null && newest.isNewerThan(state.installedVersion)) {
                    settings.updatePgw { it.copy(pendingVersion = newest.version) }
                    scheduler.queueSync()
                    return Result.success()
                }
            }
        }
        safetyNet(state.lastSyncMs)
        return Result.success()
    }

    /** Sync anyway if nothing has succeeded for 14 days. */
    private suspend fun safetyNet(lastSyncMs: Long) {
        if (System.currentTimeMillis() - lastSyncMs > 14 * 24 * 3_600_000L) scheduler.queueSync()
    }

    companion object { private const val TAG = "PgwFeedCheck" }
}

/** Log in (if needed) → download → check → import into staging → swap (DESIGN.md §6.2a). */
@HiltWorker
class PgwSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val client: PgwClient,
    private val settings: SettingsStore,
    private val repo: PoiRepository,
    private val guidance: GuidanceController,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val now = System.currentTimeMillis()
        if (inputData.getBoolean(KEY_CHECK_FEED, false)) {
            runCatching { client.checkFeed(null, null) }.getOrNull()?.let { r ->
                if (r is FeedResult.Releases) settings.updatePgw { it.copy(latestVersion = r.newest?.version, latestSummary = r.newest?.summary, pendingVersion = r.newest?.version) }
            }
            val st = settings.currentPgw()
            if (!force && st.installedVersion != null && st.latestVersion != null && st.latestVersion == st.installedVersion) {
                settings.updatePgw { it.copy(lastResult = "Already up to date (${st.installedVersion})", lastSyncMs = now) }
                return Result.success()
            }
        }
        // Don't swap cameras mid-journey (DESIGN.md §6.2): retry shortly after guidance ends.
        if (guidance.guiding.value) return Result.retry()

        val zip = File(client.cacheDir(), "pgw-${now}.zip")
        return try {
            client.downloadIgo(zip)
            val release = ZipFile(zip).use { z ->
                z.getEntry(PgwConfig.RELEASE_FILE)?.let { e -> z.getInputStream(e).bufferedReader().readText() }
            }
            val version = release?.let(PgwUpdateFeed::versionFromReleaseTxt)?.let { "${it.first}.${it.second}" }
                ?: settings.currentPgw().pendingVersion ?: "unknown"
            val outcome = repo.importPgwZip(zip, version)
            if (outcome.failed) {
                settings.updatePgw { it.copy(lastResult = outcome.message, lastSyncMs = now) }
                return Result.failure()
            }
            val summary = settings.currentPgw().latestSummary
            settings.updatePgw { it.copy(installedVersion = version, pendingVersion = null, lastResult = "Updated to $version: ${outcome.message}", lastSyncMs = now) }
            notify("Camera database updated to $version", listOfNotNull(summary, outcome.message).joinToString(" "))
            Result.success()
        } catch (e: PgwAuthException) {
            settings.updatePgw { it.copy(lastResult = "Login problem: ${e.message}") }
            notify("PocketGPSWorld login failed", "Open AutoNavAlerts to fix your login.")
            Result.failure()
        } catch (e: PgwSubscriptionException) {
            settings.updatePgw { it.copy(lastResult = "Subscription required: ${e.message}") }
            notify("PocketGPSWorld subscription needed", e.message ?: "")
            Result.failure()
        } catch (e: PgwParseException) {
            settings.updatePgw { it.copy(lastResult = "Site changed: ${e.message}. Use manual import.") }
            Result.failure()
        } catch (e: Exception) {
            settings.updatePgw { it.copy(lastResult = "Network problem: ${e.message}") }
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            zip.delete()
        }
    }

    private fun notify(title: String, text: String) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Camera database", NotificationManager.IMPORTANCE_LOW))
        }
        nm.notify(30_001, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_action_report)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).build())
    }

    companion object {
        const val KEY_FORCE = "force"
        const val KEY_CHECK_FEED = "check_feed"
        const val CHANNEL = "pgw_sync"
    }
}

/** Sends one reviewed report (only ever started from the phone's Send button or auto-send). */
@HiltWorker
class ReportSubmitWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val client: PgwClient,
    private val reports: io.autonav.alerts.reports.CameraReportRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val queued = reports.pending()
        for (r in queued) {
            reports.update(r.copy(status = io.autonav.alerts.data.ReportStatus.SENDING))
            val res = runCatching { client.submit(reports.toSubmission(r)) }
            // PGW's confirmation wording isn't known yet (DESIGN.md §16 #19): a logged-in 200 response
            // counts as sent, and the user can check it on the PGW site.
            val ok = res.getOrNull()?.let { io.autonav.alerts.core.pgw.PgwPages.loginState(it) == io.autonav.alerts.core.pgw.PgwPages.LoginState.LOGGED_IN } == true
            reports.update(r.copy(
                status = if (ok) io.autonav.alerts.data.ReportStatus.SENT else io.autonav.alerts.data.ReportStatus.FAILED,
                lastError = res.exceptionOrNull()?.message ?: if (!ok) "Not accepted (logged out?)" else null,
            ))
        }
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "pgw-report-submit", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ReportSubmitWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
        }
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.pgw

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.autonav.alerts.data.AppDatabase
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.settings.AppSettings
import io.autonav.alerts.settings.PgwState
import io.autonav.alerts.settings.SettingsStore
import io.autonav.alerts.testing.DataFixtures
import io.autonav.alerts.testing.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The automatic PocketGPSWorld update end to end on the JVM (DESIGN.md §6.2 feed check and safety net, §6.2a
 * the unattended sync, §6.3 failures; the PgwFeedCheckWorker and "automatic update" rows of §15.2): the real
 * `PgwFeedCheckWorker`, `PgwSyncWorker`, `PgwScheduler`, `PgwClient`, `SettingsStore` and `PoiRepository`
 * against a made-up site on MockWebServer, WorkManager's test driver and an in-memory database. Hard rule 6:
 * every zip is made up. Workers run on the test thread through runBlocking; WorkManager executes synchronously.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PgwWorkersTest {
    private val site = FakePgwSite()
    private lateinit var context: Application
    private lateinit var settings: SettingsStore
    private lateinit var client: PgwClient
    private lateinit var scheduler: PgwScheduler
    private lateinit var db: AppDatabase
    private lateinit var repo: PoiRepository
    private var guiding = false
    private val nm get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before fun setUp() = runBlocking<Unit> {
        FakeAndroidKeyStore.install()
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings = SettingsStore(context)
        settings.update { AppSettings() }
        settings.updatePgw { PgwState() }
        settings.setPgwCredentials("driver", "secret")
        site.server.start()
        client = PgwClient(context, settings, site.base(), site.rss())
        scheduler = PgwScheduler(context, settings)
        db = DataFixtures.memoryDb(context)
        repo = PoiRepository(context, db, IconRepository(context))
        client.cacheDir().deleteRecursively()
    }

    @After fun tearDown() {
        site.server.shutdown()
        db.close()
    }

    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = when (workerClassName) {
            PgwFeedCheckWorker::class.java.name -> PgwFeedCheckWorker(appContext, workerParameters, client, settings, scheduler)
            PgwSyncWorker::class.java.name -> PgwSyncWorker(appContext, workerParameters, client, settings, repo) { guiding }
            else -> null
        }
    }

    private fun feedCheck() = runBlocking { TestListenableWorkerBuilder<PgwFeedCheckWorker>(context).setWorkerFactory(factory).build().doWork() }
    private fun sync(force: Boolean = false, checkFeed: Boolean = false, attempt: Int = 0) = runBlocking {
        TestListenableWorkerBuilder<PgwSyncWorker>(context).setWorkerFactory(factory).setRunAttemptCount(attempt)
            .setInputData(workDataOf(PgwSyncWorker.KEY_FORCE to force, PgwSyncWorker.KEY_CHECK_FEED to checkFeed)).build().doWork()
    }

    private fun queuedSyncs(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(PgwScheduler.SYNC_WORK).get()
    private fun pgw() = runBlocking { settings.currentPgw() }
    private fun installed(version: String, lastSyncMs: Long = System.currentTimeMillis()) = runBlocking<Unit> {
        settings.updatePgw { it.copy(installedVersion = version, installedPackage = 1, installedLayout = PgwSyncWorker.IMPORT_LAYOUT, lastSyncMs = lastSyncMs) }
    }

    /** A made-up OV2 download over 50 KB: one fixed and one pending file, an optional Release.txt, and a help page as filler. */
    private fun download(release: String? = "Version 99.002 (02 Jan 99)") = File(context.cacheDir, "served.zip").let { f ->
        DataFixtures.zip(f, listOfNotNull(
            "_Need_Help.htm" to DataFixtures.padding(),
            "pocketgps_uk_gatso_30.ov2" to DataFixtures.ov2(Triple(52.3, -1.5, "E-GATSO:11@30")),
            "pocketgps_uk_pmobile_30.ov2" to DataFixtures.ov2(Triple(52.4, -1.6, "pMOBILE:12@30")),
            release?.let { "Release.txt" to it.toByteArray() },
        ), stored = setOf("_Need_Help.htm"))
        f.readBytes()
    }

    // --- Feed check (§6.2) -----------------------------------------------------------------

    @Test fun a304QueuesNothingAndRecordsTheCheck() {
        installed("99.1")
        runBlocking { settings.updatePgw { it.copy(feedEtag = "\"e1\"", feedLastModified = "Mon, 01 Jan 2099 00:00:00 GMT") } }
        site.feed = MockResponse().setResponseCode(304)

        assertEquals(ListenableWorker.Result.success(), feedCheck())

        assertTrue(queuedSyncs().isEmpty())
        assertTrue(pgw().lastFeedCheckMs > 0)
        assertEquals("\"e1\"", site.requests.single().ifNoneMatch)
        assertEquals("Mon, 01 Jan 2099 00:00:00 GMT", site.requests.single().ifModifiedSince)
    }

    @Test fun theSameVersionQueuesNothingButKeepsTheValidators() {
        installed("99.1")
        site.feed = site.feedOf("version 99.1:01 Jan 99" to "Same as installed")

        assertEquals(ListenableWorker.Result.success(), feedCheck())

        assertTrue(queuedSyncs().isEmpty())
        assertEquals("99.1", pgw().latestVersion)
        assertEquals("Same as installed", pgw().latestSummary)
        assertEquals("\"e2\"", pgw().feedEtag)
        assertNull(pgw().pendingVersion)
    }

    @Test fun aNewerVersionQueuesExactlyOneSync() {
        installed("99.1")
        site.feed = site.feedOf("version 99.2:02 Jan 99" to "New", "version 99.1:01 Jan 99" to "Old")

        feedCheck()
        feedCheck()

        val queued = queuedSyncs()
        assertEquals(1, queued.size)
        assertEquals(WorkInfo.State.ENQUEUED, queued.single().state)
        assertEquals("99.2", pgw().pendingVersion)
        // §6.2: any network unless Wi-Fi only, and battery not low.
        assertEquals(NetworkType.CONNECTED, queued.single().constraints.requiredNetworkType)
        assertTrue(queued.single().constraints.requiresBatteryNotLow())
    }

    @Test fun wifiOnlyHoldsTheQueuedSyncForAnUnmeteredNetwork() {
        runBlocking { settings.update { it.copy(pgwWifiOnly = true) } }
        site.feed = site.feedOf("version 99.2:02 Jan 99" to "New")
        feedCheck()
        assertEquals(NetworkType.UNMETERED, queuedSyncs().single().constraints.requiredNetworkType)
    }

    @Test fun aFirstRunOrAChangedPackageOrLayoutQueuesASync() {
        site.feed = site.feedOf("version 99.1:01 Jan 99" to "x")
        feedCheck()
        assertEquals(1, queuedSyncs().size)

        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManager.getInstance(context).pruneWork().result.get()
        installed("99.1")
        runBlocking { settings.updatePgw { it.copy(installedPackage = 288) } }
        feedCheck()
        assertEquals(1, queuedSyncs().size)

        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManager.getInstance(context).pruneWork().result.get()
        installed("99.1")
        runBlocking { settings.updatePgw { it.copy(installedLayout = PgwSyncWorker.IMPORT_LAYOUT - 1) } }
        feedCheck()
        assertEquals(1, queuedSyncs().size)
    }

    @Test fun anUnparseableTitleFallsBackToAHashOfTheItem() {
        installed("99.1")
        site.feed = site.feedOf("Camera database refreshed" to "Made-up summary")

        feedCheck()

        val hash = pgw().latestVersion!!
        assertTrue(hash, hash.startsWith("h:") && hash.length == 18)
        assertEquals(hash, pgw().pendingVersion)
        assertEquals(1, queuedSyncs().size)

        // Once that hash is installed, the same item is not new any more; a changed item is.
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManager.getInstance(context).pruneWork().result.get()
        installed(hash)
        feedCheck()
        assertTrue(queuedSyncs().isEmpty())
        site.feed = site.feedOf("Camera database refreshed" to "Another made-up summary")
        feedCheck()
        assertEquals(1, queuedSyncs().size)
    }

    @Test fun aFeedThatIsDownStillSyncsAfter14DaysWithoutOne() {
        site.feed = MockResponse().setResponseCode(500)
        installed("99.1", lastSyncMs = System.currentTimeMillis() - 13L * 24 * 3_600_000)
        assertEquals(ListenableWorker.Result.retry(), feedCheck())
        assertTrue(queuedSyncs().isEmpty())

        installed("99.1", lastSyncMs = System.currentTimeMillis() - 15L * 24 * 3_600_000)
        assertEquals(ListenableWorker.Result.retry(), feedCheck())
        assertEquals(1, queuedSyncs().size)
    }

    @Test fun theSafetyNetAlsoRunsWhenTheFeedSaysNothingChanged() {
        site.feed = MockResponse().setResponseCode(304)
        installed("99.1", lastSyncMs = System.currentTimeMillis() - 15L * 24 * 3_600_000)
        assertEquals(ListenableWorker.Result.success(), feedCheck())
        assertEquals(1, queuedSyncs().size)
    }

    @Test fun theDailyCheckIsScheduledOnlyWithAutoSyncAndALogin() = runBlocking<Unit> {
        val wm = WorkManager.getInstance(context)
        scheduler.schedule()
        val feed = wm.getWorkInfosForUniqueWork(PgwScheduler.FEED_WORK).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, feed.state)
        assertEquals(24 * 3_600_000L, feed.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(4 * 3_600_000L, feed.periodicityInfo!!.flexIntervalMillis)

        settings.update { it.copy(pgwAutoSync = false) }
        scheduler.schedule()
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(PgwScheduler.FEED_WORK).get().single().state)

        settings.update { it.copy(pgwAutoSync = true) }
        settings.setPgwCredentials(null, null)
        wm.pruneWork().result.get()
        scheduler.schedule()
        assertTrue(wm.getWorkInfosForUniqueWork(PgwScheduler.FEED_WORK).get().none { it.state == WorkInfo.State.ENQUEUED })
    }

    // --- Sync (§6.2a, §6.3) ------------------------------------------------------------------

    @Test fun aSyncDownloadsImportsRecordsAndNotifiesQuietly() {
        site.zip = download()
        runBlocking { settings.updatePgw { it.copy(pendingVersion = "99.2", latestSummary = "Made-up release summary.") } }
        runBlocking { settings.setPgwCookies("PHPSESSID=stale; expires=Thu, 01 Jan 2015 00:00:00 GMT; path=/") }

        assertEquals(ListenableWorker.Result.success(), sync())

        // Expired cookie: one login, then on (§15.2).
        assertEquals(1, site.logins())
        val st = pgw()
        assertEquals("99.2", st.installedVersion)
        assertEquals(1, st.installedPackage)
        assertEquals(PgwSyncWorker.IMPORT_LAYOUT, st.installedLayout)
        assertNull(st.pendingVersion)
        assertEquals("Updated to 99.2: Imported 2 cameras in 2 sets", st.lastResult)
        assertTrue(st.lastSyncMs > 0)
        val sets = runBlocking { db.poiSets().active().map { it.name }.toSet() }
        assertEquals(setOf("PGW fixed cameras · 30 mph", "PGW pending mobile sites (unverified) · 30 mph"), sets)
        // §6.1 step 4: one quiet notification with the feed's summary; the zip is gone from the cache.
        val n = nm.allNotifications.single()
        assertEquals("Camera database updated to 99.2", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("Made-up release summary. Imported 2 cameras in 2 sets", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, context.getSystemService(NotificationManager::class.java).getNotificationChannel(PgwSyncWorker.CHANNEL).importance)
        assertTrue(client.cacheDir().listFiles()!!.none { it.name.startsWith("pgw-") })
    }

    @Test fun releaseTxtIsTheVersionEvenWhenTheFeedSaidAnother() {
        // §15.2 asks for a warning on a mismatch; as built Release.txt wins silently and the import goes ahead.
        site.zip = download(release = "Version 99.003 (03 Jan 99)")
        runBlocking { settings.updatePgw { it.copy(pendingVersion = "99.2") } }
        assertEquals(ListenableWorker.Result.success(), sync())
        assertEquals("99.3", pgw().installedVersion)
        assertTrue(pgw().lastResult!!.startsWith("Updated to 99.3"))
    }

    @Test fun withoutReleaseTxtTheFeedsVersionIsUsed() {
        site.zip = download(release = null)
        runBlocking { settings.updatePgw { it.copy(pendingVersion = "99.4") } }
        assertEquals(ListenableWorker.Result.success(), sync())
        assertEquals("99.4", pgw().installedVersion)
    }

    @Test fun whileGuidingTheSyncWaitsAndTouchesNothing() {
        guiding = true
        site.zip = download()
        assertEquals(ListenableWorker.Result.retry(), sync())
        assertTrue(site.requests.isEmpty())
        assertTrue(runBlocking { db.poiSets().active() }.isEmpty())
        assertNull(pgw().installedVersion)

        guiding = false
        assertEquals(ListenableWorker.Result.success(), sync())
        assertEquals("99.2", pgw().installedVersion)
    }

    @Test fun aWrongPasswordFailsOnceWithOneNotificationAndKeepsTheOldData() {
        site.zip = download()
        assertEquals(ListenableWorker.Result.success(), sync())
        val before = runBlocking { db.poiSets().active().map { it.id }.toSet() }
        context.getSystemService(NotificationManager::class.java).cancelAll()
        site.password = "changed-on-the-site"
        runBlocking { client.forgetSession() }

        assertEquals(ListenableWorker.Result.failure(), sync())

        assertEquals("Login problem: Login failed: check your username and password", pgw().lastResult)
        assertEquals("99.2", pgw().installedVersion)
        assertEquals(before, runBlocking { db.poiSets().active().map { it.id }.toSet() })
        val n = nm.allNotifications.single()
        assertEquals("PocketGPSWorld login failed", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("Open AutoNavAlerts to fix your login.", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
    }

    @Test fun aLapsedSubscriptionFailsWithItsOwnNotification() {
        site.downloadPage = FakePgwSite.DownloadPage.NO_LINK
        assertEquals(ListenableWorker.Result.failure(), sync())
        assertEquals("Subscription required: No download link: is your subscription active?", pgw().lastResult)
        assertEquals("PocketGPSWorld subscription needed", nm.allNotifications.single().extras.getString(NotificationCompat.EXTRA_TITLE))
    }

    @Test fun aChangedSiteFailsQuietlyPointingAtManualImport() {
        site.zip = DataFixtures.padding()
        site.zipType = "text/html"
        assertEquals(ListenableWorker.Result.failure(), sync())
        assertEquals("Site changed: Download wasn't a zip (text/html). Use manual import.", pgw().lastResult)
        assertTrue(nm.allNotifications.isEmpty())
    }

    @Test fun aNetworkProblemRetriesForThreeAttemptsThenStops() {
        site.zip = download()
        site.zipCode = 502
        assertEquals(ListenableWorker.Result.retry(), sync(attempt = 0))
        assertEquals(ListenableWorker.Result.retry(), sync(attempt = 2))
        assertEquals(ListenableWorker.Result.failure(), sync(attempt = 3))
        assertEquals("Network problem: Download HTTP 502", pgw().lastResult)
        assertTrue(nm.allNotifications.isEmpty())
        assertTrue(client.cacheDir().listFiles().orEmpty().none { it.name.startsWith("pgw-") })
    }

    @Test fun aDownloadThatFailsImportLeavesTheInstalledVersionAlone() {
        installed("99.1")
        site.zip = File(context.cacheDir, "nocams.zip").let { f ->
            DataFixtures.zip(f, listOf("_Need_Help.htm" to DataFixtures.padding(), "Release.txt" to "Version 99.002".toByteArray()), stored = setOf("_Need_Help.htm"))
            f.readBytes()
        }
        assertEquals(ListenableWorker.Result.failure(), sync())
        assertEquals("99.1", pgw().installedVersion)
        assertTrue(pgw().lastResult!!, pgw().lastResult!!.startsWith("No camera files in the download"))
    }

    @Test fun syncNowStopsWhenTheFeedShowsTheInstalledVersion() {
        installed("99.1", lastSyncMs = 0)
        site.feed = site.feedOf("version 99.1:01 Jan 99" to "x")
        assertEquals(ListenableWorker.Result.success(), sync(checkFeed = true))
        assertEquals("Already up to date (99.1)", pgw().lastResult)
        assertTrue(pgw().lastSyncMs > 0)
        assertEquals(listOf("/speed-camera-rss.php"), site.requests.map { it.path })
        // The "Sync now" read is unconditional: no validators sent.
        assertNull(site.requests.single().ifNoneMatch)
    }

    @Test fun forceReDownloadIgnoresTheVersion() {
        installed("99.2")
        site.zip = download()
        assertEquals(ListenableWorker.Result.success(), sync(force = true))
        assertTrue(site.requests.any { it.path.startsWith("/temp/users/") })
        assertTrue(site.requests.none { it.path == "/speed-camera-rss.php" })
        assertEquals("99.2", pgw().installedVersion)
    }

    @Test fun syncNowAndForceAreQueuedAsOneReplaceableJob() {
        scheduler.syncNow(force = false)
        scheduler.syncNow(force = true)
        val jobs = queuedSyncs()
        assertEquals(1, jobs.count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(NetworkType.CONNECTED, jobs.single { it.state == WorkInfo.State.ENQUEUED }.constraints.requiredNetworkType)
        assertFalse(jobs.single { it.state == WorkInfo.State.ENQUEUED }.constraints.requiresBatteryNotLow())
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.osm

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.autonav.alerts.core.osm.NorthernIreland
import io.autonav.alerts.core.osm.OsmLimitFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.cos

/**
 * `SpeedLimitRepository` over a small made-up limits file (DESIGN.md §8.4a: one bulk download, never live
 * lookups; the file read a few tiles at a time in the background; the old file kept until a new one is
 * complete; Delete and Cancel), plus the download job's queueing and `SpeedLimitWorker`'s fixed facts (the
 * regions, Northern Ireland from the Ireland file, the data date). The Geofabrik download itself isn't run
 * here: it is pinned piece by piece in `RemoteZipTest` and the core tests. Lookups are polled from the test
 * thread while the repository loads on `App.appScope`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SpeedLimitRepositoryTest {
    private lateinit var context: Context
    private lateinit var repo: SpeedLimitRepository

    private val baseLat = 53.05
    private val baseLon = 0.05
    private fun latAt(northM: Double) = baseLat + northM / 111_195.0
    private fun lonAt(eastM: Double) = baseLon + eastM / (111_195.0 * cos(Math.toRadians(baseLat)))

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        repo = SpeedLimitRepository(context)
        repo.file.delete()
    }

    @After fun tearDown() { repo.replaced(); repo.file.delete() }

    /** Writes the limits file the worker would: due north from the base, 50 mph to 400 m, then 30 mph to 1 km. */
    private fun writeFile(date: String = "2026-10-11") {
        val spill = File(repo.file.parentFile, "test.spill")
        OsmLimitFile.Writer(spill).use { w ->
            w.add(50, doubleArrayOf(lonAt(0.0), latAt(0.0), lonAt(0.0), latAt(200.0), lonAt(0.0), latAt(400.0)))
            w.add(30, doubleArrayOf(lonAt(0.0), latAt(400.0), lonAt(0.0), latAt(700.0), lonAt(0.0), latAt(1_000.0)))
            w.write(repo.file, date)
        }
    }

    /** Asks until the background load has put the tiles in memory (or 5 s pass). */
    private fun <T> eventually(ask: () -> T?): T? {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            ask()?.let { return it }
            Thread.sleep(10)
        }
        return null
    }

    @Test fun limitsComeFromTheFileOnceItsTilesAreLoaded() {
        writeFile()
        assertEquals(50.0, eventually { repo.limitAt(latAt(100.0), lonAt(0.0), 0.0) })
        assertEquals(30.0, repo.limitAt(latAt(800.0), lonAt(0.0), 0.0))
        // Off every road: no limit, never a guess.
        assertNull(repo.limitAt(latAt(100.0), lonAt(500.0), 0.0))
    }

    @Test fun theNextChangeAheadAndTheRoadAheadComeFromTheSameTiles() {
        writeFile()
        eventually { repo.limitAt(latAt(100.0), lonAt(0.0), 0.0) }

        val change = repo.nextChange(latAt(100.0), lonAt(0.0), 0.0, routeAhead = null)!!
        assertEquals(30, change.newMph)
        assertEquals(300.0, change.distanceM, 5.0)
        assertFalse(change.viaRoute)
        // Heading south on the 30 stretch, the change ahead is back up to 50.
        assertEquals(50, repo.nextChange(latAt(600.0), lonAt(0.0), 180.0, routeAhead = null)!!.newMph)

        val road = repo.roadAhead(latAt(100.0), lonAt(0.0), 0.0, 500.0)!!
        assertTrue(road.size >= 2)
        assertTrue(road.last().lat > latAt(100.0))
    }

    @Test fun withoutAFileEveryLookupIsEmptyAndNothingIsShown() {
        assertNull(repo.limitAt(latAt(100.0), lonAt(0.0), 0.0))
        Thread.sleep(100)
        assertNull(repo.limitAt(latAt(100.0), lonAt(0.0), 0.0))
        assertNull(repo.nextChange(latAt(100.0), lonAt(0.0), 0.0, null))
        assertNull(repo.roadAhead(latAt(100.0), lonAt(0.0), 0.0, 500.0))
        assertNull(repo.stats())
        assertNull(repo.reader())
    }

    @Test fun statsDescribeTheFileOnDisk() {
        writeFile(date = "2026-10-12")
        val s = repo.stats()!!
        assertEquals("2026-10-12", s.dataDate)
        assertEquals(2L, s.roads)
        assertEquals(1, s.tiles)
        assertEquals(repo.file.length(), s.bytes)
    }

    @Test fun aReplacedFileIsReadAfreshAndDeleteRemovesIt() {
        assertNull(repo.limitAt(latAt(100.0), lonAt(0.0), 0.0))
        Thread.sleep(100)
        // The "no file" memo is cleared when the worker swaps a file in (§8.4a "renamed over the old file").
        writeFile()
        repo.replaced()
        assertEquals(50.0, eventually { repo.limitAt(latAt(100.0), lonAt(0.0), 0.0) })

        repo.delete()
        assertFalse(repo.file.exists())
        assertNull(repo.limitAt(latAt(100.0), lonAt(0.0), 0.0))
        assertNull(repo.stats())
    }

    @Test fun aDownloadIsOneJobOnWifiByDefaultAndCancelStopsIt() {
        val wm = WorkManager.getInstance(context)
        repo.download(wifiOnly = true)
        assertEquals("Waiting for Wi-Fi", repo.progress.value!!.step)
        val job = wm.getWorkInfosForUniqueWork(SpeedLimitRepository.WORK).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, job.state)
        assertEquals(NetworkType.UNMETERED, job.constraints.requiredNetworkType)

        // Pressing Update again replaces the waiting job (REPLACE), on any network once Wi-Fi only is off.
        repo.download(wifiOnly = false)
        assertEquals("Waiting for a connection", repo.progress.value!!.step)
        val jobs = wm.getWorkInfosForUniqueWork(SpeedLimitRepository.WORK).get()
        assertEquals(1, jobs.count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(NetworkType.CONNECTED, jobs.single { it.state == WorkInfo.State.ENQUEUED }.constraints.requiredNetworkType)

        repo.cancel()
        assertNull(repo.progress.value)
        assertTrue(wm.getWorkInfosForUniqueWork(SpeedLimitRepository.WORK).get().none { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test fun theWorkerFetchesTheFourUkRegionsEnglandLast() {
        val regions = SpeedLimitWorker.REGIONS
        assertEquals(listOf("Wales", "Scotland", "Northern Ireland", "England"), regions.map { it.name })
        assertTrue(regions.all { it.url.startsWith("https://download.geofabrik.de/europe/") && it.url.endsWith("-latest-free.shp.zip") })
        assertTrue(regions.single { it.name == "Northern Ireland" }.url.contains("ireland-and-northern-ireland"))
        // Only Northern Ireland is filtered (to its border and to whole-mph limits); Great Britain keeps every road.
        val belfast = doubleArrayOf(-5.93, 54.597, -5.92, 54.598)
        val dublin = doubleArrayOf(-6.26, 53.349, -6.25, 53.35)
        val ni = regions.single { it.name == "Northern Ireland" }
        assertEquals(NorthernIreland.keeps(30, belfast), ni.keeps(30, belfast))
        assertFalse(ni.keeps(30, dublin))
        assertTrue(regions.single { it.name == "Wales" }.keeps(31, dublin))
    }

    @Test fun theDataDateComesFromTheExtractsFileName() {
        assertEquals("2026-10-11", SpeedLimitWorker.dateOf("https://download.geofabrik.de/europe/united-kingdom/england-261011-free.shp.zip"))
        assertEquals("", SpeedLimitWorker.dateOf("https://download.geofabrik.de/europe/united-kingdom/england-latest-free.shp.zip"))
        assertNotNull(repo.progress)
    }
}

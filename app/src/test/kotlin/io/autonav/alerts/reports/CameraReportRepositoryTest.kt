// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.reports

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.data.AppDatabase
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.data.LimitSource
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.data.PoiSourceType
import io.autonav.alerts.data.ReportKind
import io.autonav.alerts.data.ReportStatus
import io.autonav.alerts.phone.MainActivity
import io.autonav.alerts.testing.DataFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `CameraReportRepository` against an in-memory database (DESIGN.md §8.5): the one-tap capture saves a DRAFT
 * and a provisional 24 h "My reports" marker; review on the phone moves, relabels or removes it and hides a
 * camera reported gone; Undo, Discard and a send delete the recording; the end-of-drive reminder reads
 * "1 camera report to check" or "N camera reports to check" and needs the notification permission. Hard rule 8:
 * nothing here sends; the worker that does is gated on `DrivingState`. Runs on the test thread.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class CameraReportRepositoryTest {
    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var pois: PoiRepository
    private lateinit var reports: CameraReportRepository
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = DataFixtures.memoryDb(app)
        pois = PoiRepository(app, db, IconRepository(app))
        reports = CameraReportRepository(app, db, pois)
    }

    @After fun tearDown() = db.close()

    private val snap = ReportSnapshot(52.48, -1.90, heading = 274.4, speedMps = 13.0, timeMs = 0L)

    @Test fun aTapSavesADraftAndAProvisionalMarkerFacingTheWayOfTravel() = runBlocking<Unit> {
        val before = System.currentTimeMillis()
        val id = reports.capture(snap, limit = 30, limitSource = LimitSource.GOOGLE, relatedPoiId = null)

        val r = db.reports().get(id)!!
        assertEquals(ReportStatus.DRAFT, r.status)
        assertEquals(ReportKind.UNSPECIFIED, r.kind)
        assertEquals(DirectionType.ONE_WAY, r.directionType)
        assertEquals(274.4f, r.heading)
        assertEquals(LimitSource.GOOGLE, r.limitSource)
        val marker = pois.poi(r.localPoiId!!)!!
        assertEquals(PoiSourceType.REPORT, db.poiSets().get(marker.setId)!!.source)
        assertEquals(DirectionType.ONE_WAY, marker.directionType)
        assertEquals(274.4f, marker.bearing)
        assertEquals(SpeedUnit.MPH, marker.speedUnit)
        assertTrue(marker.expiresAt!! in before + 24 * 3_600_000L..System.currentTimeMillis() + 24 * 3_600_000L)

        // No heading at the tap: no direction to claim, so the report and marker are ANY.
        val noHeading = reports.capture(snap.copy(heading = null), null, LimitSource.NONE, null)
        val r2 = db.reports().get(noHeading)!!
        assertEquals(DirectionType.ANY, r2.directionType)
        assertNull(pois.poi(r2.localPoiId!!)!!.bearing)
        assertNull(pois.poi(r2.localPoiId!!)!!.speedUnit)
        assertEquals(setOf(noHeading, id), reports.reports.first().map { it.id }.toSet())
    }

    @Test fun undoRemovesTheRowTheMarkerAndTheRecording() = runBlocking<Unit> {
        val id = reports.capture(snap, 30, LimitSource.CAMERA, null)
        val audio = reports.audioFile(id).apply { writeBytes(ByteArray(16)) }
        db.reports().update(db.reports().get(id)!!.copy(audioPath = audio.path))
        val marker = db.reports().get(id)!!.localPoiId!!

        reports.undo(id)

        assertNull(db.reports().get(id))
        assertNull(pois.poi(marker))
        assertFalse(audio.exists())
    }

    @Test fun reviewMakesAFixedCameraPermanentAndMovesIt() = runBlocking<Unit> {
        val id = reports.capture(snap, 30, LimitSource.GOOGLE, null)
        val r = db.reports().get(id)!!
        reports.saveReview(r.copy(kind = ReportKind.NEW_FIXED, lat = 52.49, lon = -1.91, speedLimit = 40, directionType = DirectionType.BOTH, limitSource = LimitSource.USER))

        val m = pois.poi(r.localPoiId!!)!!
        assertEquals("Fixed camera (reported)", m.name)
        assertEquals(52.49, m.lat, 0.0)
        assertEquals(io.autonav.alerts.core.poi.Grid.cellOf(52.49, -1.91), m.gridCell)
        assertEquals(40, m.speedLimit)
        assertEquals(DirectionType.BOTH, m.directionType)
        assertNull(m.expiresAt)

        // A mobile site keeps its 24 h expiry even after review.
        val mobile = reports.capture(snap, 30, LimitSource.GOOGLE, null)
        val rm = db.reports().get(mobile)!!
        reports.saveReview(rm.copy(kind = ReportKind.MOBILE_SEEN))
        assertNotNull(pois.poi(rm.localPoiId!!)!!.expiresAt)
    }

    @Test fun cameraGoneRemovesOurMarkerAndHidesTheKnownCamera() = runBlocking<Unit> {
        val pgw = db.poiSets().insert(DataFixtures.set("PGW fixed cameras", source = PoiSourceType.PGW, format = "OV2:gatso_30"))
        val camera = pois.addReportPoi(DataFixtures.poi(pgw, 52.48, -1.90, sourceId = "3209"))
        val id = reports.capture(snap, 30, LimitSource.CAMERA, relatedPoiId = camera)
        val r = db.reports().get(id)!!

        reports.queueForSending(r.copy(kind = ReportKind.CAMERA_GONE))

        assertNull(pois.poi(r.localPoiId!!))
        assertTrue(pois.poi(camera)!!.hiddenByReport)
        val queued = reports.pending().single()
        assertEquals(ReportStatus.QUEUED, queued.status)
        // idCam comes from the known camera's PGW id: Action 3, remove (§8.5).
        val sub = reports.toSubmissionWithId(queued)
        assertEquals("3209", sub.idCam)
        assertEquals(3, sub.action)
    }

    @Test fun aWrongDetailReportNeedsNoMarkerOfItsOwn() = runBlocking<Unit> {
        val id = reports.capture(snap, 30, LimitSource.CAMERA, null)
        val r = db.reports().get(id)!!
        reports.saveReview(r.copy(kind = ReportKind.WRONG_LIMIT))
        assertNull(pois.poi(r.localPoiId!!))
    }

    @Test fun discardKeepsTheRowOutOfTheListAndSendingDeletesTheRecording() = runBlocking<Unit> {
        val a = reports.capture(snap, 30, LimitSource.NONE, null)
        val audioA = reports.audioFile(a).apply { writeBytes(ByteArray(8)) }
        db.reports().update(db.reports().get(a)!!.copy(audioPath = audioA.path))
        val ra = db.reports().get(a)!!
        reports.discard(ra)
        assertEquals(ReportStatus.DISCARDED, db.reports().get(a)!!.status)
        assertNull(db.reports().get(a)!!.audioPath)
        assertFalse(audioA.exists())
        assertNull(pois.poi(ra.localPoiId!!))
        assertTrue(reports.reports.first().none { it.id == a })

        val b = reports.capture(snap, 30, LimitSource.NONE, null)
        val audioB = reports.audioFile(b).apply { writeBytes(ByteArray(8)) }
        reports.markSent(db.reports().get(b)!!.copy(audioPath = audioB.path, lastError = "earlier failure"))
        val sent = db.reports().get(b)!!
        assertEquals(ReportStatus.SENT, sent.status)
        assertNull(sent.lastError)
        assertFalse(audioB.exists())
    }

    @Test fun theReminderCountsDraftsInItsTitleAndOpensTheReportsTab() = runBlocking<Unit> {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        reports.remindIfDrafts()
        assertTrue("no drafts, no reminder", nm.allNotifications.isEmpty())

        reports.capture(snap, 30, LimitSource.NONE, null)
        reports.remindIfDrafts()
        val one = nm.allNotifications.single()
        assertEquals("1 camera report to check", one.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("Check the details and press Send when you've stopped.", one.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        val intent = shadowOf(one.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals(MainActivity.TAB_REPORTS, intent.getIntExtra(MainActivity.EXTRA_TAB, -1))

        reports.capture(snap, 30, LimitSource.NONE, null)
        reports.capture(snap, 30, LimitSource.NONE, null)
        // Queued and discarded reports aren't drafts.
        val q = reports.capture(snap, 30, LimitSource.NONE, null)
        reports.queueForSending(db.reports().get(q)!!.copy(kind = ReportKind.NEW_FIXED))
        reports.remindIfDrafts()
        assertEquals("3 camera reports to check", nm.allNotifications.single().extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, app.getSystemService(NotificationManager::class.java).getNotificationChannel("camera_reports").importance)
    }

    @Test fun theReminderNeedsTheNotificationPermission() = runBlocking<Unit> {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        reports.capture(snap, 30, LimitSource.NONE, null)
        reports.remindIfDrafts()
        assertTrue(nm.allNotifications.isEmpty())
    }
}

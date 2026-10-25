// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.reports

import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.data.CameraReportEntity
import io.autonav.alerts.data.LimitSource
import io.autonav.alerts.data.ReportKind
import io.autonav.alerts.data.ReportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The report → PGW form mapping of DESIGN.md §8.5 ("Mapping our report types", the field table) and the
 * "just passed" pick, pinned as they were before the codes moved onto `ReportKind`: Action 1 add for every
 * kind without a known camera id; with one, 4 for a mobile seen again, 3 for a camera gone, 2 for a WRONG_*;
 * Type 2 mobile, 4 SPECS, 5 red light, 10 RedSpeed, 0 otherwise; the comment's trailer; the country box.
 */
class CameraReportSubmissionTest {
    private fun report(kind: ReportKind, comment: String? = null, lat: Double = 53.38, lon: Double = -1.47, heading: Float? = 274f, relatedPoiId: Long? = null) =
        CameraReportEntity(
            kind = kind, lat = lat, lon = lon, heading = heading, speedMps = 13f, accuracyM = null, road = null, speedLimit = 30,
            directionType = DirectionType.ONE_WAY, relatedPoiId = relatedPoiId, comment = comment, createdAt = 0L,
            status = ReportStatus.QUEUED, limitSource = LimitSource.GOOGLE,
        )

    @Test fun actionAndTypePerKind() {
        val expected = mapOf(
            ReportKind.UNSPECIFIED to Triple(1, 1, 0),
            ReportKind.MOBILE_SEEN to Triple(1, 4, 2),
            ReportKind.NEW_FIXED to Triple(1, 1, 0),
            ReportKind.NEW_AVERAGE to Triple(1, 1, 4),
            ReportKind.NEW_RED_LIGHT to Triple(1, 1, 5),
            ReportKind.NEW_RED_LIGHT_SPEED to Triple(1, 1, 10),
            ReportKind.CAMERA_GONE to Triple(1, 3, 0),
            ReportKind.WRONG_LIMIT to Triple(1, 2, 0),
            ReportKind.WRONG_DIRECTION to Triple(1, 2, 0),
            ReportKind.WRONG_TYPE to Triple(1, 2, 0),
        )
        for (kind in ReportKind.entries) {
            val (addAction, knownAction, type) = expected.getValue(kind)
            val add = CameraReportRepository.toSubmission(report(kind))
            val known = CameraReportRepository.toSubmission(report(kind), "3209")
            assertEquals(kind.name, addAction, add.action)
            assertEquals(kind.name, knownAction, known.action)
            assertEquals(kind.name, type, add.type)
            assertEquals(kind.name, type, known.type)
            assertNull(add.idCam)
            assertEquals("3209", known.idCam)
        }
    }

    @Test fun commentCarriesTheTranscriptAndTheTrailer() {
        val s = CameraReportRepository.toSubmission(report(ReportKind.WRONG_LIMIT, comment = " van on the bridge. "), "7")
        assertEquals("van on the bridge. wrong limit. Reported via AutoNavAlerts; heading 274°; limit from Google", s.comments)
        val mobile = CameraReportRepository.toSubmission(report(ReportKind.MOBILE_SEEN, relatedPoiId = 5L, heading = null))
        assertEquals("Mobile seen again at a known site. Reported via AutoNavAlerts; limit from Google", mobile.comments)
        assertEquals(274, s.heading)
        assertNull(mobile.heading)
        assertEquals(30, s.speed)
        assertEquals(DirectionType.ONE_WAY, s.directionType)
    }

    @Test fun countryIsIrelandInsideTheBoxButNotNorthernIreland() {
        assertEquals(0, CameraReportRepository.toSubmission(report(ReportKind.NEW_FIXED)).countryCode)
        assertEquals(1, CameraReportRepository.toSubmission(report(ReportKind.NEW_FIXED, lat = 53.35, lon = -6.26)).countryCode)
        assertEquals(0, CameraReportRepository.toSubmission(report(ReportKind.NEW_FIXED, lat = 54.6, lon = -5.93)).countryCode)
    }

    @Test fun justPassedIsTheNearestCameraBehindWithin300m() {
        val fix = Fix(lat = 53.0, lon = -1.0, speedMps = 13.0, heading = 0.0, timeMs = 0L)
        // 0.001° of latitude is about 111 m: one camera 111 m behind, one 222 m behind, one 111 m ahead, one 400 m behind.
        val candidates = listOf(1L to (52.999 to -1.0), 2L to (52.998 to -1.0), 3L to (53.001 to -1.0), 4L to (52.9964 to -1.0))
        assertEquals(1L, CameraReportRepository.justPassed(fix, candidates))
        assertNull(CameraReportRepository.justPassed(fix.copy(heading = null), candidates))
        assertNull(CameraReportRepository.justPassed(fix, listOf(3L to (53.001 to -1.0))))
    }
}

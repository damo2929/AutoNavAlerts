// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.reports

import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.data.CameraReportEntity
import io.autonav.alerts.data.LimitSource
import io.autonav.alerts.data.ReportKind
import io.autonav.alerts.data.ReportStatus
import io.autonav.alerts.nav.LimitAnnouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DESIGN.md §8.5 beyond `CameraReportSubmissionTest`: the report type guessed from the spoken comment (step 3:
 * "mobile", "van" → Mobile; "average", "specs" → Average speed; "red light"; "not there", "gone" → Camera not
 * there; otherwise Not sure) with the order of its checks, the My reports labels, the "just passed" geometry at
 * its edges, and the comment trailer and country box for the cases the other test leaves out.
 */
class CameraReportGuessTest {
    private fun guess(t: String) = CameraReportRepository.guessKind(t)

    @Test fun theDesignsExamplePhrases() {
        assertEquals(ReportKind.MOBILE_SEEN, guess("Mobile camera on the bridge"))
        assertEquals(ReportKind.MOBILE_SEEN, guess("there's a van in the lay-by"))
        assertEquals(ReportKind.NEW_AVERAGE, guess("average speed cameras start here"))
        assertEquals(ReportKind.NEW_AVERAGE, guess("SPECS gantry"))
        assertEquals(ReportKind.NEW_RED_LIGHT, guess("red light camera at the junction"))
        assertEquals(ReportKind.CAMERA_GONE, guess("camera's not there any more"))
        assertEquals(ReportKind.CAMERA_GONE, guess("it's gone"))
        assertEquals(ReportKind.UNSPECIFIED, guess("something on a pole"))
        assertEquals(ReportKind.UNSPECIFIED, guess(""))
    }

    @Test fun theOtherKeywordsOfEachKind() {
        for (t in listOf("removed", "no camera here")) assertEquals(t, ReportKind.CAMERA_GONE, guess(t))
        for (t in listOf("red light and speed", "a redspeed", "red speed camera")) assertEquals(t, ReportKind.NEW_RED_LIGHT_SPEED, guess(t))
        assertEquals(ReportKind.NEW_RED_LIGHT, guess("traffic light camera"))
        assertEquals(ReportKind.NEW_AVERAGE, guess("new section control"))
        for (t in listOf("laser", "speed gun", "police car with a camera")) assertEquals(t, ReportKind.MOBILE_SEEN, guess(t))
        for (t in listOf("wrong limit", "the limit is wrong", "speed limit wrong")) assertEquals(t, ReportKind.WRONG_LIMIT, guess(t))
        for (t in listOf("fixed camera", "a Gatso", "truvelo", "static camera", "yellow camera")) assertEquals(t, ReportKind.NEW_FIXED, guess(t))
    }

    @Test fun notThereIsCheckedBeforeEverythingElse() {
        assertEquals(ReportKind.CAMERA_GONE, guess("the mobile van is not there"))
        assertEquals(ReportKind.CAMERA_GONE, guess("average speed cameras removed"))
        assertEquals(ReportKind.CAMERA_GONE, guess("red light camera gone"))
    }

    @Test fun laterChecksLoseToEarlierOnes() {
        // Red light + speed before red light; red light before average; average before mobile; mobile before wrong limit; all before fixed.
        assertEquals(ReportKind.NEW_RED_LIGHT_SPEED, guess("red light and speed camera"))
        assertEquals(ReportKind.NEW_RED_LIGHT, guess("red light on the average speed section"))
        assertEquals(ReportKind.NEW_AVERAGE, guess("average speed van"))
        assertEquals(ReportKind.MOBILE_SEEN, guess("van, and the wrong limit"))
        assertEquals(ReportKind.WRONG_LIMIT, guess("fixed camera, wrong limit"))
        // Case doesn't matter.
        assertEquals(ReportKind.NEW_RED_LIGHT, guess("RED LIGHT"))
    }

    @Test fun keywordsMatchInsideOtherWords() {
        // Plain substring matching, as the code is today: "van" inside "advance" and "caravan", "gun" inside "begun".
        assertEquals(ReportKind.MOBILE_SEEN, guess("advance warning sign"))
        assertEquals(ReportKind.MOBILE_SEEN, guess("caravan site entrance"))
        assertEquals(ReportKind.MOBILE_SEEN, guess("roadworks have begun"))
        // "wrong direction" and "wrong type" have no keyword of their own.
        assertEquals(ReportKind.UNSPECIFIED, guess("wrong direction"))
    }

    @Test fun labelsAreTheMyReportsNames() {
        assertEquals("Mobile camera (reported)", CameraReportRepository.label(ReportKind.MOBILE_SEEN))
        assertEquals("Camera (type to check)", CameraReportRepository.label(ReportKind.UNSPECIFIED))
        assertEquals("Red light + speed camera (reported)", CameraReportRepository.label(ReportKind.NEW_RED_LIGHT_SPEED))
        assertEquals("Camera not there", CameraReportRepository.label(ReportKind.CAMERA_GONE))
        // Every kind has its own label.
        assertEquals(ReportKind.entries.size, ReportKind.entries.map(CameraReportRepository::label).toSet().size)
    }

    private val fix = Fix(lat = 53.0, lon = -1.0, speedMps = 13.0, heading = 90.0, timeMs = 0L)
    private fun at(bearing: Double, m: Double) = LimitAnnouncer.project(fix.lat, fix.lon, bearing, m)

    @Test fun justPassedNeedsMoreThan90DegreesOffTheHeading() {
        // Heading east: a camera 100 m off to the side at 85° isn't behind; 100° off is.
        assertNull(CameraReportRepository.justPassed(fix, listOf(1L to at(5.0, 100.0))))
        assertNull(CameraReportRepository.justPassed(fix, listOf(1L to at(175.0, 100.0))))
        assertEquals(2L, CameraReportRepository.justPassed(fix, listOf(2L to at(350.0, 100.0))))
        assertEquals(3L, CameraReportRepository.justPassed(fix, listOf(3L to at(190.0, 100.0))))
        assertEquals(4L, CameraReportRepository.justPassed(fix, listOf(4L to at(270.0, 100.0))))
    }

    @Test fun justPassedWithin300mOnlyAndTheNearestWins() {
        assertEquals(1L, CameraReportRepository.justPassed(fix, listOf(1L to at(270.0, 295.0))))
        assertNull(CameraReportRepository.justPassed(fix, listOf(1L to at(270.0, 305.0))))
        assertNull(CameraReportRepository.justPassed(fix, emptyList()))
        // Nearest behind wins regardless of list order; a nearer one ahead is ignored.
        val c = listOf(5L to at(250.0, 200.0), 6L to at(80.0, 10.0), 7L to at(280.0, 50.0))
        assertEquals(7L, CameraReportRepository.justPassed(fix, c))
        assertEquals(7L, CameraReportRepository.justPassed(fix, c.reversed()))
    }

    private fun report(kind: ReportKind, comment: String? = null, road: String? = null, source: LimitSource = LimitSource.NONE, lat: Double = 53.38, lon: Double = -1.47) =
        CameraReportEntity(
            kind = kind, lat = lat, lon = lon, heading = 89.6f, speedMps = 13f, accuracyM = null, road = road, speedLimit = null,
            directionType = DirectionType.BOTH, relatedPoiId = null, comment = comment, createdAt = 0L,
            status = ReportStatus.QUEUED, limitSource = source,
        )

    @Test fun commentTrailerCases() {
        // A blank transcript is left out; the heading is rounded; the road comes last; no "limit from Google" unless it was.
        assertEquals("Reported via AutoNavAlerts; heading 90°; road A61",
            CameraReportRepository.toSubmission(report(ReportKind.NEW_FIXED, comment = "   ", road = "A61")).comments)
        assertEquals("Reported via AutoNavAlerts; heading 90°",
            CameraReportRepository.toSubmission(report(ReportKind.NEW_FIXED, source = LimitSource.USER)).comments)
        // Trailing dots of the transcript are trimmed to one full stop.
        assertEquals("van seen... wait. Reported via AutoNavAlerts; heading 90°",
            CameraReportRepository.toSubmission(report(ReportKind.MOBILE_SEEN, comment = "van seen... wait...")).comments)
        // A mobile seen with no known site isn't called "seen again".
        assertEquals("Reported via AutoNavAlerts; heading 90°; limit from Google",
            CameraReportRepository.toSubmission(report(ReportKind.MOBILE_SEEN, source = LimitSource.GOOGLE)).comments)
        // Every WRONG_* kind names itself.
        assertEquals("wrong direction. Reported via AutoNavAlerts; heading 90°",
            CameraReportRepository.toSubmission(report(ReportKind.WRONG_DIRECTION)).comments)
        assertEquals("wrong type. Reported via AutoNavAlerts; heading 90°",
            CameraReportRepository.toSubmission(report(ReportKind.WRONG_TYPE)).comments)
    }

    @Test fun submissionCarriesThePositionDirectionAndNoLimit() {
        val s = CameraReportRepository.toSubmission(report(ReportKind.NEW_FIXED))
        assertEquals(53.38, s.lat, 0.0)
        assertEquals(-1.47, s.lon, 0.0)
        assertEquals(90, s.heading)
        assertNull(s.speed)
        assertEquals(DirectionType.BOTH, s.directionType)
    }

    @Test fun countryBoxEdges() {
        fun country(lat: Double, lon: Double) = CameraReportRepository.toSubmission(report(ReportKind.NEW_FIXED, lat = lat, lon = lon)).countryCode
        // Cork, Galway, Donegal (north of 54°N but west of 8.2°W: the Republic).
        assertEquals(1, country(51.9, -8.47))
        assertEquals(1, country(53.27, -9.05))
        assertEquals(1, country(54.95, -8.3))
        // Derry (north of 54°N, east of 8.2°W): Northern Ireland, so UK.
        assertEquals(0, country(55.0, -7.3))
        // The box: west of 5.3°W, 51.4–55.5°N.
        assertEquals(0, country(51.3, -9.0))
        assertEquals(0, country(55.6, -9.0))
        assertEquals(0, country(53.0, -5.2))
        // Cornwall: west of 5.3°W but south of the box.
        assertEquals(0, country(50.1, -5.5))
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.reports

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.R
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.pgw.PgwSubmission
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.Grid
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.data.AppDatabase
import io.autonav.alerts.data.CameraReportEntity
import io.autonav.alerts.data.LimitSource
import io.autonav.alerts.data.PoiEntity
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.data.ReportKind
import io.autonav.alerts.data.ReportStatus
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Everything saved the moment Report is tapped (DESIGN.md §8.5). */
data class ReportSnapshot(
    val lat: Double,
    val lon: Double,
    val heading: Double?,
    val speedMps: Double,
    val timeMs: Long,
)

fun Fix.toSnapshot() = ReportSnapshot(lat, lon, heading, speedMps, timeMs)

/**
 * Camera reports: captured in one tap on the car screen, buffered as drafts, reviewed on the phone
 * after the drive, and sent to PGW only when the user presses Send and isn't driving.
 */
@Singleton
class CameraReportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val pois: PoiRepository,
) {
    val reports: Flow<List<CameraReportEntity>> = db.reports().observe()

    fun audioFile(id: Long) = File(File(context.filesDir, "report-audio").apply { mkdirs() }, "report-$id.pcm")

    /** One-tap capture. Adds a provisional "My reports" POI (expires in 24 h unless reviewed). */
    suspend fun capture(snap: ReportSnapshot, limit: Int?, limitSource: LimitSource, relatedPoiId: Long?): Long {
        val setId = pois.reportsSetId()
        val dir = if (snap.heading != null) DirectionType.ONE_WAY else DirectionType.ANY
        val poiId = pois.addReportPoi(
            PoiEntity(
                setId = setId, categoryId = null, lat = snap.lat, lon = snap.lon, name = "Reported camera (to check)",
                speedLimit = limit, speedUnit = limit?.let { SpeedUnit.MPH }, directionType = dir,
                bearing = snap.heading?.toFloat(), gridCell = Grid.cellOf(snap.lat, snap.lon),
                expiresAt = System.currentTimeMillis() + 24 * 3_600_000L,
            ),
        )
        return db.reports().insert(
            CameraReportEntity(
                kind = ReportKind.UNSPECIFIED, lat = snap.lat, lon = snap.lon, heading = snap.heading?.toFloat(),
                speedMps = snap.speedMps.toFloat(), accuracyM = null, road = null, speedLimit = limit,
                directionType = dir, relatedPoiId = relatedPoiId, comment = null, createdAt = System.currentTimeMillis(),
                status = ReportStatus.DRAFT, limitSource = limitSource, localPoiId = poiId,
            ),
        )
    }

    /** Stores the recorded comment and transcribes it in the background. */
    suspend fun attachAudio(id: Long, pcm: File) {
        val r = db.reports().get(id) ?: return
        db.reports().update(r.copy(audioPath = pcm.path, transcription = "PENDING"))
        transcribe(id)
    }

    suspend fun transcribe(id: Long) {
        val r = db.reports().get(id) ?: return
        val pcm = r.audioPath?.let(::File) ?: return
        val text = Transcriber.transcribe(context, pcm)
        val now = db.reports().get(id) ?: return
        db.reports().update(
            if (text != null) now.copy(
                comment = listOfNotNull(now.comment?.takeIf { it.isNotBlank() }, text).joinToString(". "),
                transcription = "DONE",
                kind = if (now.kind == ReportKind.UNSPECIFIED) guessKind(text) else now.kind,
            ) else now.copy(transcription = "FAILED"),
        )
    }

    /** Undo straight after reporting. */
    suspend fun undo(id: Long) {
        val r = db.reports().get(id) ?: return
        r.localPoiId?.let { pois.deletePoi(it) }
        r.audioPath?.let { File(it).delete() }
        db.reports().delete(id)
    }

    /** Saves review edits and applies them to the local "My reports" POI. */
    suspend fun saveReview(r: CameraReportEntity) {
        db.reports().update(r)
        val local = r.localPoiId?.let { pois.poi(it) }
        when (r.kind) {
            ReportKind.CAMERA_GONE -> {
                local?.let { pois.deletePoi(it.id) }
                r.relatedPoiId?.let { pois.setHidden(it, true) }
            }
            ReportKind.WRONG_LIMIT, ReportKind.WRONG_DIRECTION, ReportKind.WRONG_TYPE -> local?.let { pois.deletePoi(it.id) }
            else -> local?.let {
                pois.updatePoi(it.copy(
                    lat = r.lat, lon = r.lon, gridCell = Grid.cellOf(r.lat, r.lon), name = label(r.kind),
                    speedLimit = r.speedLimit, speedUnit = r.speedLimit?.let { SpeedUnit.MPH },
                    directionType = r.directionType, bearing = r.heading,
                    expiresAt = if (r.kind == ReportKind.MOBILE_SEEN || r.kind == ReportKind.UNSPECIFIED) it.expiresAt else null,
                ))
            }
        }
    }

    /** Queues a reviewed report; the worker only sends when not driving. */
    suspend fun queueForSending(r: CameraReportEntity) {
        saveReview(r)
        db.reports().update(r.copy(status = ReportStatus.QUEUED, lastError = null))
    }

    suspend fun discard(r: CameraReportEntity) {
        if (r.status == ReportStatus.DRAFT) r.localPoiId?.let { pois.deletePoi(it) }
        r.audioPath?.let { File(it).delete() }
        db.reports().update(r.copy(status = ReportStatus.DISCARDED, audioPath = null))
    }

    suspend fun update(r: CameraReportEntity) = db.reports().update(r)
    suspend fun pending(): List<CameraReportEntity> = db.reports().withStatus(ReportStatus.QUEUED)
    suspend fun drafts(): List<CameraReportEntity> = db.reports().withStatus(ReportStatus.DRAFT)

    suspend fun markSent(r: CameraReportEntity) {
        r.audioPath?.let { File(it).delete() }
        db.reports().update(r.copy(status = ReportStatus.SENT, lastError = null, audioPath = null))
    }

    /** End of drive: "N camera reports to check" (DESIGN.md §8.5). */
    suspend fun remindIfDrafts() {
        val n = drafts().size
        if (n == 0) return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Camera reports", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = Intent(context, io.autonav.alerts.phone.MainActivity::class.java)
            .putExtra(io.autonav.alerts.phone.MainActivity.EXTRA_TAB, io.autonav.alerts.phone.MainActivity.TAB_REPORTS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(30_100, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_action_report)
            .setContentTitle(if (n == 1) "1 camera report to check" else "$n camera reports to check")
            .setContentText("Check the details and press Send when you've stopped.")
            .setContentIntent(pi).setAutoCancel(true).build())
    }

    /** Like [toSubmission], filling idCam from the related camera's PGW id when known. */
    suspend fun toSubmissionWithId(r: CameraReportEntity): PgwSubmission {
        val id = r.relatedPoiId?.let { pois.poi(it) }?.sourceId
        val base = toSubmission(r, id)
        return base
    }

    /** Maps a report onto PGW's form fields (DESIGN.md §8.5). */
    fun toSubmission(r: CameraReportEntity, pgwCameraId: String? = null): PgwSubmission {
        val (action, type) = when (r.kind) {
            ReportKind.UNSPECIFIED -> 1 to 0
            ReportKind.MOBILE_SEEN -> (if (pgwCameraId != null) 4 else 1) to 2
            ReportKind.NEW_FIXED -> 1 to 0
            ReportKind.NEW_AVERAGE -> 1 to 4
            ReportKind.NEW_RED_LIGHT -> 1 to 5
            ReportKind.NEW_RED_LIGHT_SPEED -> 1 to 10
            ReportKind.CAMERA_GONE -> (if (pgwCameraId != null) 3 else 1) to 0
            ReportKind.WRONG_LIMIT, ReportKind.WRONG_DIRECTION, ReportKind.WRONG_TYPE -> (if (pgwCameraId != null) 2 else 1) to 0
        }
        val details = buildString {
            r.comment?.takeIf { it.isNotBlank() }?.let { append(it.trim().trimEnd('.')).append(". ") }
            if (r.kind == ReportKind.MOBILE_SEEN && r.relatedPoiId != null) append("Mobile seen again at a known site. ")
            if (r.kind.name.startsWith("WRONG")) append(r.kind.name.lowercase().replace('_', ' ')).append(". ")
            append("Reported via AutoNavAlerts")
            r.heading?.let { append("; heading ").append(it.roundToInt()).append("°") }
            if (r.limitSource == LimitSource.GOOGLE) append("; limit from Google")
            r.road?.let { append("; road ").append(it) }
        }
        val country = if (r.lon < -5.3 && r.lat in 51.4..55.5 && !(r.lat > 54.0 && r.lon > -8.2)) 1 else 0
        return PgwSubmission(action, pgwCameraId, r.lat, r.lon, type, r.speedLimit, r.heading?.roundToInt(), r.directionType, details, country)
    }

    /** The closest camera behind you within 300 m: the one you've just passed. */
    fun justPassed(fix: Fix, candidates: List<Pair<Long, Pair<Double, Double>>>): Long? {
        val h = fix.heading ?: return null
        return candidates.filter { (_, ll) ->
            val d = Geo.distanceM(fix.lat, fix.lon, ll.first, ll.second)
            d <= 300 && Geo.absAngleDiffDeg(Geo.bearingDeg(fix.lat, fix.lon, ll.first, ll.second), h) > 90
        }.minByOrNull { (_, ll) -> Geo.distanceM(fix.lat, fix.lon, ll.first, ll.second) }?.first
    }

    companion object {
        private const val CHANNEL = "camera_reports"

        /** Guesses the report type from the spoken comment (confirmed at review). */
        fun guessKind(text: String): ReportKind {
            val t = text.lowercase()
            return when {
                listOf("not there", "gone", "removed", "no camera").any { it in t } -> ReportKind.CAMERA_GONE
                listOf("red light and speed", "redspeed", "red speed").any { it in t } -> ReportKind.NEW_RED_LIGHT_SPEED
                "red light" in t || "traffic light" in t -> ReportKind.NEW_RED_LIGHT
                listOf("average", "specs", "section").any { it in t } -> ReportKind.NEW_AVERAGE
                listOf("mobile", "van", "laser", "gun", "police").any { it in t } -> ReportKind.MOBILE_SEEN
                listOf("wrong limit", "limit is wrong", "speed limit wrong").any { it in t } -> ReportKind.WRONG_LIMIT
                listOf("fixed", "gatso", "truvelo", "static", "yellow camera").any { it in t } -> ReportKind.NEW_FIXED
                else -> ReportKind.UNSPECIFIED
            }
        }

        fun label(kind: ReportKind) = when (kind) {
            ReportKind.UNSPECIFIED -> "Camera (type to check)"
            ReportKind.MOBILE_SEEN -> "Mobile camera (reported)"
            ReportKind.NEW_FIXED -> "Fixed camera (reported)"
            ReportKind.NEW_AVERAGE -> "Average speed camera (reported)"
            ReportKind.NEW_RED_LIGHT -> "Red light camera (reported)"
            ReportKind.NEW_RED_LIGHT_SPEED -> "Red light + speed camera (reported)"
            ReportKind.CAMERA_GONE -> "Camera not there"
            ReportKind.WRONG_LIMIT -> "Wrong speed limit"
            ReportKind.WRONG_DIRECTION -> "Wrong direction"
            ReportKind.WRONG_TYPE -> "Wrong camera type"
        }
    }
}

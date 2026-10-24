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

/** Everything saved the moment Report is tapped (DESIGN.md §8.5 "Car flow" step 1): position, heading (degrees true, null if unknown), speed in m/s and the time. */
data class ReportSnapshot(
    val lat: Double,
    val lon: Double,
    val heading: Double?,
    val speedMps: Double,
    val timeMs: Long,
)

/** The alert engine's latest fix, frozen at the tap (car GPS when fresh, otherwise the phone, §10.7). */
fun Fix.toSnapshot() = ReportSnapshot(lat, lon, heading, speedMps, System.currentTimeMillis())

/**
 * Camera reports (DESIGN.md §8.5): captured in one tap on the car screen, buffered as drafts, reviewed on the
 * phone after the drive, and sent to PocketGPSWorld only when the user presses Send and isn't driving. A Hilt
 * singleton; Room calls run on Dispatchers.IO through the DAOs. Callers: `HomeScreen` ([capture], [justPassed],
 * [localPoiOf]), `VoiceCommentScreen` ([attachAudio], [undo]), `NavSession` ([remindIfDrafts] when guidance
 * ends or the car disconnects), the phone's Reports tab ([saveReview], [queueForSending], [discard],
 * [transcribe], [toSubmission]) and `ReportSubmitWorker` ([pending], [update], [markSent], [toSubmissionWithId]).
 * Upholds hard rule 8 (AGENTS.md §3.1): the car only captures; review, edits and Send exist on the phone only,
 * and nothing is ever sent from the car or while driving (`DrivingState` guards the worker and the button).
 */
@Singleton
class CameraReportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val pois: PoiRepository,
) {
    /** The Reports tab's list: everything not discarded, newest first. */
    val reports: Flow<List<CameraReportEntity>> = db.reports().observe()

    /** Where a report's recording lives: private storage, `files/report-audio/report-<id>.pcm`, deleted once sent or discarded (§8.5 "Privacy"). */
    fun audioFile(id: Long) = File(File(context.filesDir, "report-audio").apply { mkdirs() }, "report-$id.pcm")

    /**
     * One-tap capture (§8.5 "Car flow" step 1): saves the snapshot as a DRAFT with [limit] (mph) and where it came
     * from, and [relatedPoiId] for the nearest known camera, and adds a provisional "My reports" POI at the spot,
     * one-way in the direction of travel, which expires in 24 h unless the review makes it permanent. The caller
     * then tells `AlertCoordinator.quietHere` to keep the new marker silent until the car is 300 m away.
     * Returns the report id. The type is not asked for in the car: it stays UNSPECIFIED until the comment or review.
     */
    suspend fun capture(snap: ReportSnapshot, limit: Int?, limitSource: LimitSource, relatedPoiId: Long?): Long {
        val setId = pois.reportsSetId()
        // A heading at the tap makes the report one-way in that direction (§8.5 "saved as one-way"); none means ANY.
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

    /** The marker POI a report put on the map, if it still has one. */
    suspend fun localPoiOf(id: Long): Long? = db.reports().get(id)?.localPoiId

    /** Stores the recorded comment (§8.5 step 2) and transcribes it; the caller runs this off the main thread so the car screen isn't held. */
    suspend fun attachAudio(id: Long, pcm: File) {
        val r = db.reports().get(id) ?: return
        db.reports().update(r.copy(audioPath = pcm.path, transcription = "PENDING"))
        transcribe(id)
    }

    /**
     * Speech to text for a report's recording (§8.5 "Speech to text"), also the review screen's retry. A result is
     * appended to any comment already typed, and sets the type by [guessKind] if it is still UNSPECIFIED;
     * a failure leaves the audio for playback and marks the transcription FAILED.
     */
    suspend fun transcribe(id: Long) {
        val r = db.reports().get(id) ?: return
        val pcm = r.audioPath?.let(::File) ?: return
        val text = Transcriber.transcribe(context, pcm)
        // Re-read: the user may have edited the report on the phone while recognition ran.
        val now = db.reports().get(id) ?: return
        db.reports().update(
            if (text != null) now.copy(
                comment = listOfNotNull(now.comment?.takeIf { it.isNotBlank() }, text).joinToString(". "),
                transcription = "DONE",
                kind = if (now.kind == ReportKind.UNSPECIFIED) guessKind(text) else now.kind,
            ) else now.copy(transcription = "FAILED"),
        )
    }

    /** Undo from the 5 s alert after the tap (§8.5 step 3): the marker, the recording and the row all go. */
    suspend fun undo(id: Long) {
        val r = db.reports().get(id) ?: return
        r.localPoiId?.let { pois.deletePoi(it) }
        r.audioPath?.let { File(it).delete() }
        db.reports().delete(id)
    }

    /**
     * Saves review edits from the phone (§8.5 "Review on the phone") and applies them to the local "My reports"
     * POI: a "camera not there" removes our marker and hides the existing camera (`hiddenByReport`); a WRONG_*
     * report about an existing camera needs no marker of its own; anything else moves and relabels the marker
     * with the reviewed position, limit and direction, and makes it permanent unless it is a mobile site or
     * still unspecified (those keep their 24 h expiry).
     */
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

    /** Send on the phone: saves the review and marks the report QUEUED; `ReportSubmitWorker` sends it once the phone isn't driving (§8.5). */
    suspend fun queueForSending(r: CameraReportEntity) {
        saveReview(r)
        db.reports().update(r.copy(status = ReportStatus.QUEUED, lastError = null))
    }

    /** Discard on the phone: a draft's marker goes, the recording is deleted (§8.5 "Privacy"), the row stays as DISCARDED. */
    suspend fun discard(r: CameraReportEntity) {
        if (r.status == ReportStatus.DRAFT) r.localPoiId?.let { pois.deletePoi(it) }
        r.audioPath?.let { File(it).delete() }
        db.reports().update(r.copy(status = ReportStatus.DISCARDED, audioPath = null))
    }

    /** Plain save (the worker's SENDING / FAILED moves). */
    suspend fun update(r: CameraReportEntity) = db.reports().update(r)
    /** Reports waiting to be sent, for the worker. */
    suspend fun pending(): List<CameraReportEntity> = db.reports().withStatus(ReportStatus.QUEUED)
    /** Reports not yet reviewed, for the end-of-drive reminder. */
    suspend fun drafts(): List<CameraReportEntity> = db.reports().withStatus(ReportStatus.DRAFT)

    /** The worker's success path: SENT, and the recording deleted (§8.5 "Audio files are deleted once the report is sent"). */
    suspend fun markSent(r: CameraReportEntity) {
        r.audioPath?.let { File(it).delete() }
        db.reports().update(r.copy(status = ReportStatus.SENT, lastError = null, audioPath = null))
    }

    /**
     * End of drive: the "N camera reports to check" notification that opens the phone's Reports tab (DESIGN.md
     * §8.5 "End of drive"). Nothing is shown when there are no drafts or no notification permission.
     */
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

    /** Like [toSubmission], filling idCam from the related camera's PGW id when known (`Poi.sourceId`, carried in the OV2 download's names, §5.2a; the iGO download has none). */
    suspend fun toSubmissionWithId(r: CameraReportEntity): PgwSubmission {
        val id = r.relatedPoiId?.let { pois.poi(it) }?.sourceId
        val base = toSubmission(r, id)
        return base
    }

    /**
     * Maps a report onto PGW's form fields (DESIGN.md §8.5 "Mapping our report types" and the field table):
     * Action 1 add, 2 change, 3 remove, 4 mobile seen again (2–4 only with a known camera id, else an add with the
     * details in the comment); Type 0 not sure, 2 mobile, 4 SPECS, 5 red light, 10 RedSpeed. The comment carries
     * the transcript plus "Reported via AutoNavAlerts; heading …; road …"; the country is picked from the position.
     * Pure, so the phone's "Open in browser" fallback can show the same fields (§8.5 "Fallback").
     */
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
        // mapCountry2: 0 United Kingdom, 1 Ireland (§8.5 field table). A rough box for the island of Ireland (west of
        // 5.3°W, 51.4–55.5°N) less its north-east corner (north of 54°N and east of 8.2°W), which is Northern Ireland.
        val country = if (r.lon < -5.3 && r.lat in 51.4..55.5 && !(r.lat > 54.0 && r.lon > -8.2)) 1 else 0
        return PgwSubmission(action, pgwCameraId, r.lat, r.lon, type, r.speedLimit, r.heading?.roundToInt(), r.directionType, details, country)
    }

    /**
     * The closest camera behind you within 300 m: the one you've just passed, so a tap right after a camera can
     * mean "not there" or "mobile seen again" about it (§8.5 "the nearest camera already in the database").
     * [candidates] are (poi id, (lat, lon)) from the alert cache; "behind" is a bearing more than 90° off the heading.
     * Null without a heading.
     */
    fun justPassed(fix: Fix, candidates: List<Pair<Long, Pair<Double, Double>>>): Long? {
        val h = fix.heading ?: return null
        return candidates.filter { (_, ll) ->
            val d = Geo.distanceM(fix.lat, fix.lon, ll.first, ll.second)
            d <= 300 && Geo.absAngleDiffDeg(Geo.bearingDeg(fix.lat, fix.lon, ll.first, ll.second), h) > 90
        }.minByOrNull { (_, ll) -> Geo.distanceM(fix.lat, fix.lon, ll.first, ll.second) }?.first
    }

    companion object {
        private const val CHANNEL = "camera_reports"

        /** Guesses the report type from the spoken comment (§8.5 step 3: "mobile", "van" → Mobile; "average", "specs" → Average speed; "red light"; "not there", "gone" → Camera not there; otherwise Not sure), confirmed at review. Order matters: "not there" is tested before anything else. */
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

        /** The "My reports" marker's name per type, shown on the map and said as `{category}` when the set has no category (§7.2). */
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

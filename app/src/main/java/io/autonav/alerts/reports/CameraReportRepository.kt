// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.reports

import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.pgw.PgwSubmission
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.Grid
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.data.AppDatabase
import io.autonav.alerts.data.CameraReportEntity
import io.autonav.alerts.data.PoiEntity
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.data.ReportKind
import io.autonav.alerts.data.ReportStatus
import io.autonav.alerts.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Position saved the moment Report was tapped (DESIGN.md §8.5). */
data class ReportSnapshot(val lat: Double, val lon: Double, val heading: Double?, val speedMps: Double, val timeMs: Long)

fun Fix.toSnapshot() = ReportSnapshot(lat, lon, heading, speedMps, timeMs)

/** Camera reports: saved locally, added to "My reports", and sent to PGW only when the user says so. */
@Singleton
class CameraReportRepository @Inject constructor(
    private val db: AppDatabase,
    private val pois: PoiRepository,
    private val settings: SettingsStore,
) {
    val reports: Flow<List<CameraReportEntity>> = db.reports().observe()

    suspend fun create(kind: ReportKind, snap: ReportSnapshot, speedLimit: Int?, relatedPoiId: Long? = null): Long {
        val review = settings.current().reviewReportsFirst
        val id = db.reports().insert(
            CameraReportEntity(
                kind = kind, lat = snap.lat, lon = snap.lon, heading = snap.heading?.toFloat(),
                speedMps = snap.speedMps.toFloat(), accuracyM = null, road = null, speedLimit = speedLimit,
                directionType = if (snap.heading != null) DirectionType.ONE_WAY else DirectionType.ANY,
                relatedPoiId = relatedPoiId, comment = null, createdAt = System.currentTimeMillis(),
                status = if (review) ReportStatus.DRAFT else ReportStatus.QUEUED,
            ),
        )
        applyLocally(kind, snap, speedLimit, relatedPoiId)
        return id
    }

    /** Undo straight after reporting. */
    suspend fun undo(id: Long) {
        val r = db.reports().get(id) ?: return
        r.relatedPoiId?.let { if (r.kind == ReportKind.CAMERA_GONE) pois.setHidden(it, false) }
        db.reports().delete(id)
    }

    private suspend fun applyLocally(kind: ReportKind, snap: ReportSnapshot, speed: Int?, related: Long?) {
        when (kind) {
            ReportKind.CAMERA_GONE -> related?.let { pois.setHidden(it, true) }
            ReportKind.MOBILE_SEEN, ReportKind.NEW_FIXED, ReportKind.NEW_AVERAGE,
            ReportKind.NEW_RED_LIGHT, ReportKind.NEW_RED_LIGHT_SPEED -> {
                val setId = pois.reportsSetId()
                pois.addReportPoi(
                    PoiEntity(
                        setId = setId, categoryId = null, lat = snap.lat, lon = snap.lon, name = label(kind),
                        speedLimit = speed, speedUnit = speed?.let { SpeedUnit.MPH },
                        directionType = if (snap.heading != null) DirectionType.ONE_WAY else DirectionType.ANY,
                        bearing = snap.heading?.toFloat(), gridCell = Grid.cellOf(snap.lat, snap.lon),
                        expiresAt = if (kind == ReportKind.MOBILE_SEEN) System.currentTimeMillis() + 24 * 3_600_000L else null,
                    ),
                )
            }
            else -> Unit
        }
    }

    suspend fun update(r: CameraReportEntity) = db.reports().update(r)
    suspend fun discard(r: CameraReportEntity) = db.reports().update(r.copy(status = ReportStatus.DISCARDED))
    suspend fun pending(): List<CameraReportEntity> = db.reports().withStatus(ReportStatus.QUEUED)
    suspend fun drafts(): List<CameraReportEntity> = db.reports().withStatus(ReportStatus.DRAFT)

    /** Maps a report onto PGW's form fields (DESIGN.md §8.5). */
    fun toSubmission(r: CameraReportEntity): PgwSubmission {
        val (action, type) = when (r.kind) {
            ReportKind.MOBILE_SEEN -> 1 to 2
            ReportKind.NEW_FIXED -> 1 to 0
            ReportKind.NEW_AVERAGE -> 1 to 4
            ReportKind.NEW_RED_LIGHT -> 1 to 5
            ReportKind.NEW_RED_LIGHT_SPEED -> 1 to 10
            ReportKind.CAMERA_GONE -> 3 to 0
            ReportKind.WRONG_LIMIT, ReportKind.WRONG_DIRECTION, ReportKind.WRONG_TYPE -> 2 to 0
        }
        val details = buildString {
            r.comment?.takeIf { it.isNotBlank() }?.let { append(it).append(". ") }
            if (r.kind == ReportKind.MOBILE_SEEN && r.relatedPoiId != null) append("Mobile seen again at a known site. ")
            if (r.kind.name.startsWith("WRONG")) append(r.kind.name.lowercase().replace('_', ' ')).append(". ")
            append("Reported via AutoNavAlerts")
            r.heading?.let { append("; heading ").append(it.roundToInt()).append("°") }
            r.road?.let { append("; road ").append(it) }
        }
        val country = if (r.lon < -5.3 && r.lat in 51.4..55.5 && !(r.lat > 54.0 && r.lon > -8.2)) 1 else 0
        return PgwSubmission(action, null, r.lat, r.lon, type, r.speedLimit, r.heading?.roundToInt(), r.directionType, details, country)
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
        fun label(kind: ReportKind) = when (kind) {
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

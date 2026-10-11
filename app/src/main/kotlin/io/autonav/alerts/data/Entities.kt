// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.core.poi.ZoneRole

enum class PoiSourceType { PGW, PGW_PENDING, FILE, REPORT }
enum class SetState { ACTIVE, STAGING }

/** One alert stage's settings as stored (DESIGN.md §4.1). */
data class StageCols(
    val enabled: Boolean,
    val mode: DistanceMode,
    val metres: Int,
    val seconds: Int?,
)

@Entity(tableName = "poi_set")
data class PoiSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: PoiSourceType,
    val format: String,
    val enabled: Boolean = true,
    val showOnMap: Boolean = true,
    val alertsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val defaultIconId: String,
    val firstTemplate: String,
    val secondTemplate: String,
    val passingTemplate: String,
    @Embedded(prefix = "a1_") val firstAlert: StageCols,
    @Embedded(prefix = "a2_") val secondAlert: StageCols,
    val minZoom: Float = 11f,
    val state: SetState = SetState.ACTIVE,
    val replacesSetId: Long? = null,
    val poiCount: Int = 0,
    val version: String? = null,
    val lastUpdated: Long,
)

@Entity(
    tableName = "poi_category",
    foreignKeys = [ForeignKey(entity = PoiSetEntity::class, parentColumns = ["id"], childColumns = ["setId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("setId"), Index(value = ["setId", "sourceCode"], unique = true)],
)
data class PoiCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val setId: Long,
    val sourceCode: String,
    val name: String,
    val iconId: String? = null,
    val firstTemplate: String? = null,
    val secondTemplate: String? = null,
    val passingTemplate: String? = null,
    val alertsEnabled: Boolean = true,
    val soundEnabled: Boolean? = null,
    val zoneRole: ZoneRole = ZoneRole.NONE,
)

@Entity(
    tableName = "poi",
    foreignKeys = [ForeignKey(entity = PoiSetEntity::class, parentColumns = ["id"], childColumns = ["setId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["setId", "gridCell"]), Index("gridCell"), Index("categoryId")],
)
data class PoiEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val setId: Long,
    val categoryId: Long?,
    val lat: Double,
    val lon: Double,
    val name: String?,
    val speedLimit: Int?,
    val speedUnit: SpeedUnit?,
    val directionType: DirectionType,
    val bearing: Float?,
    val gridCell: Long,
    val hiddenByReport: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val expiresAt: Long? = null,
    /** The source's own id for this POI, e.g. the PGW camera id (sent as idCam in reports). */
    @ColumnInfo(defaultValue = "NULL") val sourceId: String? = null,
)

enum class PlaceKind { RECENT, FAVOURITE, HOME, WORK }

@Entity(tableName = "place", indices = [Index("lastUsedAt")])
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placeId: String?,
    val name: String,
    val address: String?,
    val lat: Double,
    val lon: Double,
    val kind: PlaceKind = PlaceKind.RECENT,
    val lastUsedAt: Long,
    val useCount: Int = 1,
)

enum class ReportKind { UNSPECIFIED, MOBILE_SEEN, NEW_FIXED, NEW_AVERAGE, NEW_RED_LIGHT, NEW_RED_LIGHT_SPEED, CAMERA_GONE, WRONG_LIMIT, WRONG_DIRECTION, WRONG_TYPE }
/** Where a report's captured speed limit came from (DESIGN.md §8.5). */
enum class LimitSource { GOOGLE, CAMERA, NONE, USER }

enum class ReportStatus { DRAFT, QUEUED, SENDING, SENT, FAILED, DISCARDED }

@Entity(tableName = "camera_report", indices = [Index("status")])
data class CameraReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ReportKind,
    val lat: Double,
    val lon: Double,
    val heading: Float?,
    val speedMps: Float?,
    val accuracyM: Float?,
    val road: String?,
    val speedLimit: Int?,
    val directionType: DirectionType,
    val relatedPoiId: Long?,
    val comment: String?,
    val createdAt: Long,
    val status: ReportStatus,
    val lastError: String? = null,
    /** Private 16 kHz mono PCM recording of the spoken comment, if any. */
    val audioPath: String? = null,
    val limitSource: LimitSource = LimitSource.NONE,
    /** "My reports" POI added for this report, so review can update or remove it. */
    val localPoiId: Long? = null,
    /** PENDING while speech-to-text runs; FAILED if it couldn't transcribe. */
    val transcription: String? = null,
)

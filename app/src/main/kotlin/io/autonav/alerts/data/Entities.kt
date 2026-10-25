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

/**
 * Where a POI set came from (DESIGN.md §4.1 `PoiSource`, §13 item 8). PGW and PGW_PENDING are the
 * PocketGPSWorld download (the verified cameras, and the unverified pending mobile sites, §5.2);
 * FILE is a POI file the user picked by hand (§5); REPORT is the local "My reports" set (§8.5).
 * The phone's Cameras tab lists PGW, PGW_PENDING and REPORT sets; its POIs tab lists FILE sets only.
 */
enum class PoiSourceType { PGW, PGW_PENDING, FILE, REPORT }
/** A set's place in the staging swap (DESIGN.md §4.4): STAGING while an import fills it, ACTIVE once swapped in. */
enum class SetState { ACTIVE, STAGING }

/**
 * One alert stage's settings as stored (DESIGN.md §4.1 `AlertStage`, §11.1), embedded in [PoiSetEntity]
 * under the `a1_` (1st alert) and `a2_` (2nd alert) column prefixes. `toStage()` and `toCols()` in
 * PoiRepository.kt convert to and from the core `AlertStage` the engine uses.
 */
data class StageCols(
    /** The 1st alert is always on (`StageRules`); the 2nd can be turned off (§11.1). */
    val enabled: Boolean,
    /** FIXED: alert at [metres]. TIME: alert [seconds] ahead at the current speed, never closer than [metres] (§11.1). */
    val mode: DistanceMode,
    /** Metres: the FIXED distance, or the floor in TIME mode (defaults 242 m and 142 m, §11.1). */
    val metres: Int,
    /** TIME mode seconds. Kept in the DB but ignored since the global lead-time slider overrides it (§11.1). */
    val seconds: Int?,
)

/**
 * A POI set: one imported file, or one file of a PocketGPSWorld download (DESIGN.md §4.1 `PoiSet`).
 * Hard rule 9 (AGENTS.md §3.1): every file in a PGW or zip download becomes its own set, never merged,
 * and the name comes from the file, never from a prompt. Written by `PoiRepository.storeImport` on
 * Dispatchers.IO through the staging swap (§4.4); read by `AlertCoordinator`, `PoiMarkerLayer`, the
 * phone's set lists and the settings backup (§13.4, which matches sets by [name]).
 */
@Entity(tableName = "poi_set")
data class PoiSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Shown in lists and spoken as `{category}` when a POI has no category (§7.2). From the file name (`PoiRepository.setNameFor`) or the PGW file table (§5.2); the user can rename it. */
    val name: String,
    val source: PoiSourceType,
    /**
     * The import key, not only the format: "OV2:gatso_30" for a PGW file, "KML:file:<zip>/<file>" for a zip
     * entry, or a bare "KML" for a single picked file, so a re-import or sync finds the set it replaces (§5, §5.2).
     */
    val format: String,
    /** Master switch: off keeps the set out of every map and engine query (§4.2 `alertsOn`). */
    val enabled: Boolean = true,
    /** Markers drawn for this set (§8.1). */
    val showOnMap: Boolean = true,
    /** Alerts for this set (§4.2). Pending mobile sites are on since DB v9 (§5.2). */
    val alertsEnabled: Boolean = true,
    /** Spoken alerts for this set; on makes the passing alert compulsory (§4.2 `soundOn`, §11.1). */
    val soundEnabled: Boolean = true,
    /** "builtin:…" or "user:…" (§7.1); the last resort of the icon resolution in §4.2. */
    val defaultIconId: String,
    /** The three stage templates, 1st, 2nd and passing (§11.1), using only the placeholders in §7.2. */
    val firstTemplate: String,
    val secondTemplate: String,
    val passingTemplate: String,
    @Embedded(prefix = "a1_") val firstAlert: StageCols,
    @Embedded(prefix = "a2_") val secondAlert: StageCols,
    /** Markers are hidden below this map zoom, which also keeps the viewport query small (§4.3). */
    val minZoom: Float = 11f,
    val state: SetState = SetState.ACTIVE,
    /** For a STAGING set: the ACTIVE set it replaces on the swap (§4.4). */
    val replacesSetId: Long? = null,
    /** Rows imported, for the lists; not kept in step with later single-POI edits. */
    val poiCount: Int = 0,
    /** The PGW database version the set came from, e.g. "24.101" (§6.1 step 4); null for a hand import. */
    val version: String? = null,
    /** Epoch millis of the import. */
    val lastUpdated: Long,
)

/**
 * A category inside a set (DESIGN.md §4.1 `PoiCategory`): an iGO type code, an OV2 camera type, a KML
 * folder or a GPX `<type>` (§5). Null columns mean "use the set's value" (§4.2). The user's choices here
 * survive a re-import or sync because `PoiRepository.storeImport` carries the old category rows over
 * to the new set, matched on [sourceCode] (§4.4 step 2).
 */
@Entity(
    tableName = "poi_category",
    foreignKeys = [ForeignKey(entity = PoiSetEntity::class, parentColumns = ["id"], childColumns = ["setId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("setId"), Index(value = ["setId", "sourceCode"], unique = true)],
)
data class PoiCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Owning set; deleting the set cascades here (§4.4). */
    val setId: Long,
    /** The source's own code, unique within a set: iGO "1", OV2 "GATSO", a KML folder name (§5). */
    val sourceCode: String,
    /** Display name, e.g. "Fixed camera": the `{category}` placeholder (§7.2). */
    val name: String,
    /** Icon override, or null for the set's default (§4.2). */
    val iconId: String? = null,
    /** Template overrides per stage, or null for the set's (§4.2). */
    val firstTemplate: String? = null,
    val secondTemplate: String? = null,
    val passingTemplate: String? = null,
    /** Alerts for this category, and-ed with the set's switch (§4.2). */
    val alertsEnabled: Boolean = true,
    /** Sound override, or null for the set's (§4.2). */
    val soundEnabled: Boolean? = null,
    /** ZONE_POINT for average-speed cameras, so the engine chains them into zones (§11 step 8); NONE otherwise. */
    val zoneRole: ZoneRole = ZoneRole.NONE,
)

/**
 * One point of interest (DESIGN.md §4.1 `Poi`). Filled by `PoiRepository.storeImport` from the
 * importers' `ImportRow` (§5) in batches of 2,000, or one at a time by `CameraReportRepository` for
 * "My reports" (§8.5). Read by grid cell for the alert cache and the map (§4.3), never per fix.
 */
@Entity(
    tableName = "poi",
    foreignKeys = [ForeignKey(entity = PoiSetEntity::class, parentColumns = ["id"], childColumns = ["setId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["setId", "gridCell"]), Index("gridCell"), Index("categoryId")],
)
data class PoiEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Owning set; deleting the set cascades here (§4.4). */
    val setId: Long,
    /** Category within the set, or null when the source has none (alerts then say the set name, §7.2). */
    val categoryId: Long?,
    /** WGS84 degrees. The importer rejects anything outside −90..90 / −180..180 and the point (0,0) (§5.1). */
    val lat: Double,
    val lon: Double,
    /** The source's own name text (an OV2 entry name, a KML `<name>`); null for iGO rows. */
    val name: String?,
    /** Speed limit in [speedUnit]; null when the source gives none or 0 (§5.2). */
    val speedLimit: Int?,
    /** MPH for UK PGW packages. The panel and the alerts convert to the display units (§8.4). */
    val speedUnit: SpeedUnit?,
    /**
     * ANY, ONE_WAY or BOTH (§4.1). With a [bearing] the camera alerts only for traffic heading that way
     * (BOTH: either way along it). This is the camera-direction hard rule (§11 step 3, AGENTS.md §3.1 rule 1):
     * a camera with a bearing in its data is never treated as ANY.
     */
    val directionType: DirectionType,
    /** The direction of travel the POI applies to, degrees clockwise from true north, 0–359.x; null forces ANY (§4.1, §5.2). */
    val bearing: Float?,
    /** 0.01° cell key, `Grid.cellOf(lat, lon)` (§4.3): the alert cache and the viewport query select on it. */
    val gridCell: Long,
    /** "Camera not there" was reported on the phone (§8.5): left out of every query until the next sync replaces the set. */
    val hiddenByReport: Boolean = false,
    /** Epoch millis after which the POI is dropped (a reported mobile site: 24 h, §8.5); null means permanent. `PoiDao.deleteExpired` purges at startup. */
    @ColumnInfo(defaultValue = "NULL") val expiresAt: Long? = null,
    /** The source's own id for this POI, e.g. the PGW camera id (sent as idCam in reports). */
    @ColumnInfo(defaultValue = "NULL") val sourceId: String? = null,
)

/** RECENT or FAVOURITE only: Home and Work from Google were removed (DESIGN.md §8.2a; DB v8 deleted those rows). */
enum class PlaceKind { RECENT, FAVOURITE }

/**
 * A recent or favourite destination (DESIGN.md §8.2 `Place`). Written by `PlacesSearch.remember` for
 * every destination picked; shown in the car's Search empty state and the phone's Places screen.
 * Recents are trimmed to the newest 200 (`PlaceDao.trimRecents`, §8.2); favourites have no limit.
 */
@Entity(tableName = "place", indices = [Index("lastUsedAt")])
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Google Place ID when known: the dedupe key in `PlacesSearch.remember` (§8.2). */
    val placeId: String?,
    val name: String,
    val address: String?,
    val lat: Double,
    val lon: Double,
    val kind: PlaceKind = PlaceKind.RECENT,
    /** Epoch millis; orders the lists and decides which recents are trimmed (§8.2). */
    val lastUsedAt: Long,
    /** Times navigated to; bumped on each repeat (§8.2). */
    val useCount: Int = 1,
)

/**
 * What a camera report says (DESIGN.md §8.5). UNSPECIFIED until the spoken comment or the phone review
 * picks one. Each carries what `CameraReportRepository` needs of it: [label], the "My reports" marker's name
 * (shown on the map and said as `{category}` when the set has no category, §7.2), and PGW's form codes
 * (§8.5 "Mapping our report types"): [type] (0 not sure, 2 mobile, 4 SPECS, 5 red light, 10 RedSpeed) and
 * [knownAction], the Action sent when the report names a known camera (2 change, 3 remove, 4 mobile seen
 * again; 1 add otherwise, and always without a known camera). Stored by name only.
 */
enum class ReportKind(val label: String, val type: Int, val knownAction: Int) {
    UNSPECIFIED("Camera (type to check)", 0, 1),
    MOBILE_SEEN("Mobile camera (reported)", 2, 4),
    NEW_FIXED("Fixed camera (reported)", 0, 1),
    NEW_AVERAGE("Average speed camera (reported)", 4, 1),
    NEW_RED_LIGHT("Red light camera (reported)", 5, 1),
    NEW_RED_LIGHT_SPEED("Red light + speed camera (reported)", 10, 1),
    CAMERA_GONE("Camera not there", 0, 3),
    WRONG_LIMIT("Wrong speed limit", 0, 2),
    WRONG_DIRECTION("Wrong direction", 0, 2),
    WRONG_TYPE("Wrong camera type", 0, 2),
}
/** Where a report's captured speed limit came from (DESIGN.md §8.5): GOOGLE, CAMERA or NONE at the tap; USER once confirmed or changed at review. */
enum class LimitSource { GOOGLE, CAMERA, NONE, USER }

/**
 * A report's life (DESIGN.md §8.5): DRAFT from the car tap; QUEUED by Send on the phone; SENDING, SENT
 * and FAILED set by `ReportSubmitWorker`; DISCARDED by the user (the row is kept, the audio deleted).
 */
enum class ReportStatus { DRAFT, QUEUED, SENDING, SENT, FAILED, DISCARDED }

/**
 * One camera report (DESIGN.md §4.1 `CameraReport`, §8.5). Position, heading, speed and limit are the
 * snapshot taken the moment Report was tapped in the car; everything else is added on the phone at review.
 * Hard rule 8 (AGENTS.md §3.1): review and Send happen on the phone only, never in the car or while driving.
 */
@Entity(tableName = "camera_report", indices = [Index("status")])
data class CameraReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ReportKind,
    /** Where Report was tapped; nudged at review (§8.5). */
    val lat: Double,
    val lon: Double,
    /** Heading at the tap, degrees true: PGW's "Heading / direction of travel" (§8.5). */
    val heading: Float?,
    /** Speed at the tap, metres per second. */
    val speedMps: Float?,
    /** Fix accuracy in metres (§4.1). Not filled yet: the capture and the review both leave it null. */
    val accuracyM: Float?,
    /** Navigation SDK road name (§4.1, §8.5 "road"). Not filled yet: the capture and the review both leave it null. */
    val road: String?,
    /** Limit in mph (see [limitSource]); confirmed or set at review (§8.5). */
    val speedLimit: Int?,
    /** ONE_WAY using [heading] when one was known at the tap, else ANY; "One way / Both ways" at review (§8.5). */
    val directionType: DirectionType,
    /** The known camera this is about (CAMERA_GONE, WRONG_*, a mobile seen again), the source of `idCam` (§8.5). */
    val relatedPoiId: Long?,
    /** The transcript, merged with anything typed; edited on the phone only (§8.5). */
    val comment: String?,
    /** Epoch millis of the tap. */
    val createdAt: Long,
    val status: ReportStatus,
    /** Why the last send failed, shown on the Reports tab. */
    val lastError: String? = null,
    /** Private 16 kHz mono PCM recording of the spoken comment, if any. Deleted once sent or discarded (§8.5). */
    val audioPath: String? = null,
    val limitSource: LimitSource = LimitSource.NONE,
    /** "My reports" POI added for this report, so review can update or remove it. */
    val localPoiId: Long? = null,
    /** PENDING while speech-to-text runs; FAILED if it couldn't transcribe. */
    val transcription: String? = null,
)

/**
 * Where a contact's address was found (DESIGN.md §8.3). Keyed by a hash of the address, not the address,
 * so no contact text sits in the database. Table added in DB v4 (`MIGRATION_3_4`); written by
 * `ContactsSource.remember`, looked up again after 90 days, emptied by Settings → Permissions → Clear contact locations.
 */
@Entity(tableName = "contact_geocode")
data class ContactGeocodeEntity(
    /** SHA-256 of the normalised address (`ContactMatch.key`, §8.3). */
    @PrimaryKey val key: String,
    /** Google Place ID of the chosen result, when it had one. */
    val placeId: String?,
    val lat: Double,
    val lon: Double,
    /** The chosen result's address, shown on the row instead of the contact's own text. */
    val formattedAddress: String?,
    /** Epoch millis of the lookup; also orders `ContactsScreen` (most recently located first). */
    val updatedAt: Long,
)


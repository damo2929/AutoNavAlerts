// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The Room DAOs for `poi.db` (DESIGN.md §4.1). Only `PoiRepository`, `PlacesSearch`, `ContactsSource` and
 * `CameraReportRepository` call them; everything else goes through those repositories. All calls run on
 * Dispatchers.IO (§2). "Active" everywhere means `state = 'ACTIVE'`: a STAGING set being filled by an
 * import is invisible until the swap (§4.4).
 */
@Dao
interface PoiSetDao {
    /** The sets the user sees, live: the phone's POIs and Cameras tabs and the car's layer screen (`PoiRepository.activeSets`). */
    @Query("SELECT * FROM poi_set WHERE state = 'ACTIVE' ORDER BY name")
    fun observeActive(): Flow<List<PoiSetEntity>>

    /** One-off read of the active sets, for the alert cache reload and the settings backup (`PoiRepository.sets`). */
    @Query("SELECT * FROM poi_set WHERE state = 'ACTIVE'")
    suspend fun active(): List<PoiSetEntity>

    /** The set an import replaces (`PoiRepository.storeImport`), or one the editor loads. */
    @Query("SELECT * FROM poi_set WHERE id = :id")
    suspend fun get(id: Long): PoiSetEntity?

    /** Live view of one set. No caller at present. */
    @Query("SELECT * FROM poi_set WHERE id = :id")
    fun observe(id: Long): Flow<PoiSetEntity?>

    /** Finds the one set of a source: the "My reports" set, `source = REPORT` (`PoiRepository.reportsSetId`, §8.5). */
    @Query("SELECT * FROM poi_set WHERE source = :source AND state = 'ACTIVE' LIMIT 1")
    suspend fun bySource(source: PoiSourceType): PoiSetEntity?

    /** Finds the set a file replaces on re-import or sync, by its import key (`PoiSetEntity.format`, §5, §5.2). */
    @Query("SELECT * FROM poi_set WHERE format = :format AND state = 'ACTIVE' LIMIT 1")
    suspend fun byFormat(format: String): PoiSetEntity?

    /** Every PocketGPSWorld set, so a sync can remove the ones no longer in the download (§5.2 "sets from an older layout"). */
    @Query("SELECT * FROM poi_set WHERE source IN ('PGW', 'PGW_PENDING') AND state = 'ACTIVE'")
    suspend fun pgwSets(): List<PoiSetEntity>

    /** Inserts a new (usually STAGING) set and returns its id (§4.4 step 1). */
    @Insert
    suspend fun insert(set: PoiSetEntity): Long

    /** The set editor's saves, the swap's "mark ACTIVE" (§4.4 step 2), and the Cameras tab's switches. */
    @Update
    suspend fun update(set: PoiSetEntity)

    /** Several sets at once: a group switch on the Cameras tab, or a backup restore (§13.4). */
    @Update
    suspend fun updateAll(sets: List<PoiSetEntity>)

    /** Deleting a set cascades to its categories and POIs (§4.1 foreign keys). */
    @Query("DELETE FROM poi_set WHERE id = :id")
    suspend fun delete(id: Long)

    /** Drops any STAGING set left by an import the process didn't finish; run at startup (§4.4 step 4). */
    @Query("DELETE FROM poi_set WHERE state = 'STAGING'")
    suspend fun deleteStaging()
}

/** Categories within sets (DESIGN.md §4.1 `PoiCategory`). */
@Dao
interface PoiCategoryDao {
    /** A set's categories: the set editor's list, and the swap's copy of the user's customisations to the new set (§4.4 step 2). */
    @Query("SELECT * FROM poi_category WHERE setId = :setId ORDER BY name")
    suspend fun forSet(setId: Long): List<PoiCategoryEntity>

    /** Every category, for the alert cache's icon and template resolution (`PoiRepository.categories`, §4.2). */
    @Query("SELECT * FROM poi_category")
    suspend fun all(): List<PoiCategoryEntity>

    /** IGNORE: `(setId, sourceCode)` is unique, so a repeated code in one import keeps the first row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: PoiCategoryEntity): Long

    /** The set editor's per-category icon, template, alerts and zone-role changes. */
    @Update
    suspend fun update(c: PoiCategoryEntity)
}

/** POIs (DESIGN.md §4.1 `Poi`). The engine never calls these per fix: it reads cells into memory (§4.3). */
@Dao
interface PoiDao {
    /** One import batch of up to 2,000 rows, inside one transaction (§4.4 step 1). */
    @Insert
    suspend fun insertAll(pois: List<PoiEntity>)

    /** One "My reports" marker (`CameraReportRepository.capture`, §8.5). */
    @Insert
    suspend fun insert(poi: PoiEntity): Long

    /**
     * The alert cache's reload and the map's viewport query (§4.3): every POI in the given cells whose set is
     * active and enabled, not hidden by a "camera not there" report, and not expired at [now]. The caller
     * chunks [cells] (SQLite caps the variables in one statement at 999).
     */
    @Query(
        "SELECT p.* FROM poi p JOIN poi_set s ON s.id = p.setId " +
            "WHERE s.state = 'ACTIVE' AND s.enabled = 1 AND p.gridCell IN (:cells) AND p.hiddenByReport = 0 " +
            "AND (p.expiresAt IS NULL OR p.expiresAt > :now)",
    )
    suspend fun inCells(cells: List<Long>, now: Long): List<PoiEntity>

    /** One POI by id: the related camera of a report, or a report's own marker (§8.5). */
    @Query("SELECT * FROM poi WHERE id = :id")
    suspend fun get(id: Long): PoiEntity?

    /** Review edits applied to a report's marker (`CameraReportRepository.saveReview`). */
    @Update
    suspend fun update(poi: PoiEntity)

    /** Removes a report's marker on Undo, Discard or a "camera not there" review (§8.5). */
    @Query("DELETE FROM poi WHERE id = :id")
    suspend fun delete(id: Long)

    /** "Camera not there" reported: the camera stays in the data but out of every query (§8.5). */
    @Query("UPDATE poi SET hiddenByReport = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    /** Live count for a set, where `poiCount` on the set might be stale. */
    @Query("SELECT COUNT(*) FROM poi WHERE setId = :setId")
    suspend fun count(setId: Long): Int

    /** Drops expired reported mobile sites (24 h, §8.5); run at startup by `PoiRepository.purgeExpired`. */
    @Query("DELETE FROM poi WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    /** A few POIs of a set for the phone's map preview (§13 item 5: 50 samples with their direction arrows). */
    @Query("SELECT * FROM poi WHERE setId = :setId LIMIT :limit")
    suspend fun sample(setId: Long, limit: Int): List<PoiEntity>
}

/** Recents and favourites (DESIGN.md §8.2). Called through `PlacesSearch`. */
@Dao
interface PlaceDao {
    /**
     * The search's empty state and the phone's Places screen: favourites first, then recents, newest first (§8.2).
     * The HOME/WORK cases in the ordering are left over from before DB v8 removed those kinds (§8.2a); no row has them now.
     */
    @Query("SELECT * FROM place ORDER BY CASE kind WHEN 'HOME' THEN 0 WHEN 'WORK' THEN 1 WHEN 'FAVOURITE' THEN 2 ELSE 3 END, lastUsedAt DESC LIMIT :limit")
    fun observe(limit: Int = 50): Flow<List<PlaceEntity>>

    /** One-off read in the same order as [observe] (`PlacesSearch.recents`). */
    @Query("SELECT * FROM place ORDER BY CASE kind WHEN 'HOME' THEN 0 WHEN 'WORK' THEN 1 WHEN 'FAVOURITE' THEN 2 ELSE 3 END, lastUsedAt DESC LIMIT :limit")
    suspend fun list(limit: Int = 50): List<PlaceEntity>

    /** The dedupe lookup when a destination is remembered: same Google Place ID, same row (§8.2). */
    @Query("SELECT * FROM place WHERE placeId = :placeId LIMIT 1")
    suspend fun byPlaceId(placeId: String): PlaceEntity?

    /** Newest place of a kind. Left over from Home/Work (§8.2a); no caller at present. */
    @Query("SELECT * FROM place WHERE kind = :kind ORDER BY lastUsedAt DESC LIMIT 1")
    suspend fun byKind(kind: PlaceKind): PlaceEntity?

    /** Deletes every place of a kind. Left over from Home/Work (§8.2a); no caller at present. */
    @Query("DELETE FROM place WHERE kind = :kind")
    suspend fun deleteKind(kind: PlaceKind)

    /** A new recent, or a deleted row put back by Undo (`PlacesSearch.restore`). */
    @Insert
    suspend fun insert(p: PlaceEntity): Long

    /** Bumps `lastUsedAt`/`useCount` on a repeat visit, or flips RECENT and FAVOURITE (§8.2). */
    @Update
    suspend fun update(p: PlaceEntity)

    /** Swipe-to-delete on the phone, or the delete action on a car row (§8.2). */
    @Delete
    suspend fun delete(p: PlaceEntity)

    /** "Clear all recents" (§8.2); favourites stay. */
    @Query("DELETE FROM place WHERE kind = 'RECENT'")
    suspend fun clearRecents()

    /** Keeps the newest 200 recents, oldest removed first (§8.2 "Limits"); run after every insert. */
    @Query("DELETE FROM place WHERE kind = 'RECENT' AND id NOT IN (SELECT id FROM place WHERE kind = 'RECENT' ORDER BY lastUsedAt DESC LIMIT 200)")
    suspend fun trimRecents()
}

/** Camera reports (DESIGN.md §8.5). Called through `CameraReportRepository`; the phone's Reports tab is the only reader of the list. */
@Dao
interface CameraReportDao {
    /** The Reports tab, newest first; discarded reports stay in the table but out of the list. */
    @Query("SELECT * FROM camera_report WHERE status != 'DISCARDED' ORDER BY createdAt DESC")
    fun observe(): Flow<List<CameraReportEntity>>

    /** DRAFT for the end-of-drive reminder, QUEUED for the submit worker (§8.5). */
    @Query("SELECT * FROM camera_report WHERE status = :status")
    suspend fun withStatus(status: ReportStatus): List<CameraReportEntity>

    /** One report, re-read before each update so a transcription finishing late doesn't overwrite review edits. */
    @Query("SELECT * FROM camera_report WHERE id = :id")
    suspend fun get(id: Long): CameraReportEntity?

    /** The one-tap capture in the car (§8.5). */
    @Insert
    suspend fun insert(r: CameraReportEntity): Long

    /** Every later change: audio attached, transcript, review edits, status moves. */
    @Update
    suspend fun update(r: CameraReportEntity)

    /** Only for Undo right after the tap; Discard keeps the row with status DISCARDED. */
    @Query("DELETE FROM camera_report WHERE id = :id")
    suspend fun delete(id: Long)
}

/** The contact address cache (DESIGN.md §8.3). Called through `ContactsSource`. */
@Dao
interface ContactGeocodeDao {
    /** Lookup by the address hash; the caller applies the 90-day age limit. `key` is quoted because it is an SQL keyword. */
    @Query("SELECT * FROM contact_geocode WHERE `key` = :key")
    suspend fun get(key: String): ContactGeocodeEntity?

    /** REPLACE: a fresh lookup for the same address overwrites the old entry. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: ContactGeocodeEntity)

    /** Settings → Permissions → Clear contact locations (§8.3). */
    @Query("DELETE FROM contact_geocode")
    suspend fun clear()
}


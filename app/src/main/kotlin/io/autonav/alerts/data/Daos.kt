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

@Dao
interface PoiSetDao {
    @Query("SELECT * FROM poi_set WHERE state = 'ACTIVE' ORDER BY name")
    fun observeActive(): Flow<List<PoiSetEntity>>

    @Query("SELECT * FROM poi_set WHERE state = 'ACTIVE'")
    suspend fun active(): List<PoiSetEntity>

    @Query("SELECT * FROM poi_set WHERE id = :id")
    suspend fun get(id: Long): PoiSetEntity?

    @Query("SELECT * FROM poi_set WHERE id = :id")
    fun observe(id: Long): Flow<PoiSetEntity?>

    @Query("SELECT * FROM poi_set WHERE source = :source AND state = 'ACTIVE' LIMIT 1")
    suspend fun bySource(source: PoiSourceType): PoiSetEntity?

    @Insert
    suspend fun insert(set: PoiSetEntity): Long

    @Update
    suspend fun update(set: PoiSetEntity)

    @Query("DELETE FROM poi_set WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM poi_set WHERE state = 'STAGING'")
    suspend fun deleteStaging()
}

@Dao
interface PoiCategoryDao {
    @Query("SELECT * FROM poi_category WHERE setId = :setId ORDER BY name")
    suspend fun forSet(setId: Long): List<PoiCategoryEntity>

    @Query("SELECT * FROM poi_category WHERE setId = :setId ORDER BY name")
    fun observeForSet(setId: Long): Flow<List<PoiCategoryEntity>>

    @Query("SELECT * FROM poi_category")
    suspend fun all(): List<PoiCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: PoiCategoryEntity): Long

    @Update
    suspend fun update(c: PoiCategoryEntity)
}

@Dao
interface PoiDao {
    @Insert
    suspend fun insertAll(pois: List<PoiEntity>)

    @Insert
    suspend fun insert(poi: PoiEntity): Long

    @Query(
        "SELECT p.* FROM poi p JOIN poi_set s ON s.id = p.setId " +
            "WHERE s.state = 'ACTIVE' AND s.enabled = 1 AND p.gridCell IN (:cells) AND p.hiddenByReport = 0 " +
            "AND (p.expiresAt IS NULL OR p.expiresAt > :now)",
    )
    suspend fun inCells(cells: List<Long>, now: Long): List<PoiEntity>

    @Query("SELECT * FROM poi WHERE id = :id")
    suspend fun get(id: Long): PoiEntity?

    @Update
    suspend fun update(poi: PoiEntity)

    @Query("DELETE FROM poi WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE poi SET hiddenByReport = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("SELECT COUNT(*) FROM poi WHERE setId = :setId")
    suspend fun count(setId: Long): Int

    @Query("DELETE FROM poi WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("SELECT * FROM poi WHERE setId = :setId LIMIT :limit")
    suspend fun sample(setId: Long, limit: Int): List<PoiEntity>
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM place ORDER BY CASE kind WHEN 'HOME' THEN 0 WHEN 'WORK' THEN 1 WHEN 'FAVOURITE' THEN 2 ELSE 3 END, lastUsedAt DESC LIMIT :limit")
    fun observe(limit: Int = 50): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM place ORDER BY CASE kind WHEN 'HOME' THEN 0 WHEN 'WORK' THEN 1 WHEN 'FAVOURITE' THEN 2 ELSE 3 END, lastUsedAt DESC LIMIT :limit")
    suspend fun list(limit: Int = 50): List<PlaceEntity>

    @Query("SELECT * FROM place WHERE placeId = :placeId LIMIT 1")
    suspend fun byPlaceId(placeId: String): PlaceEntity?

    @Insert
    suspend fun insert(p: PlaceEntity): Long

    @Update
    suspend fun update(p: PlaceEntity)

    @Delete
    suspend fun delete(p: PlaceEntity)

    @Query("DELETE FROM place WHERE kind = 'RECENT'")
    suspend fun clearRecents()

    @Query("DELETE FROM place WHERE kind = 'RECENT' AND id NOT IN (SELECT id FROM place WHERE kind = 'RECENT' ORDER BY lastUsedAt DESC LIMIT 200)")
    suspend fun trimRecents()
}

@Dao
interface CameraReportDao {
    @Query("SELECT * FROM camera_report WHERE status != 'DISCARDED' ORDER BY createdAt DESC")
    fun observe(): Flow<List<CameraReportEntity>>

    @Query("SELECT * FROM camera_report WHERE status = :status")
    suspend fun withStatus(status: ReportStatus): List<CameraReportEntity>

    @Query("SELECT * FROM camera_report WHERE id = :id")
    suspend fun get(id: Long): CameraReportEntity?

    @Insert
    suspend fun insert(r: CameraReportEntity): Long

    @Update
    suspend fun update(r: CameraReportEntity)

    @Query("DELETE FROM camera_report WHERE id = :id")
    suspend fun delete(id: Long)
}

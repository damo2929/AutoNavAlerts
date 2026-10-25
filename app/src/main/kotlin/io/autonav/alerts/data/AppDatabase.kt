// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The one Room database, `poi.db` (DESIGN.md §4.1): POI sets, categories and POIs, places, camera reports
 * and the contact geocode cache. A Hilt singleton from [DatabaseModule], shared by the car service and the
 * phone UI (§2). Every DAO call is a suspend function or a Flow, so callers run on Dispatchers.IO (§2).
 * The whole file is excluded from Android backup because it holds PocketGPSWorld data (§6.4; hard rule 6,
 * AGENTS.md §3.1), so a reinstall re-syncs. The version history is the list of migrations below, one per
 * schema step; the DB version named in DESIGN.md ("DB v7") is the `version` here.
 */
@Database(
    entities = [PoiSetEntity::class, PoiCategoryEntity::class, PoiEntity::class, PlaceEntity::class, CameraReportEntity::class, ContactGeocodeEntity::class],
    // v9: pending mobile sites alert by default (MIGRATION_8_9, DESIGN.md §5.2). Bump this with every new migration.
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun poiSets(): PoiSetDao
    abstract fun categories(): PoiCategoryDao
    abstract fun pois(): PoiDao
    abstract fun places(): PlaceDao
    abstract fun reports(): CameraReportDao
    abstract fun contactGeocodes(): ContactGeocodeDao

    companion object {
        /** File name is referenced by the backup exclusion rules (DESIGN.md §6.4). */
        const val NAME = "poi.db"
    }
}

/** A schema step that is only SQL: each statement in [sql] runs in order, in Room's migration transaction. */
private fun migration(from: Int, to: Int, vararg sql: String) = object : Migration(from, to) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (statement in sql) db.execSQL(statement)
    }
}

/** v2: voice comments and review fields on camera reports (DESIGN.md §8.5): the recording path, the limit's source, the local marker and the transcription state. */
val MIGRATION_1_2 = migration(
    1, 2,
    "ALTER TABLE camera_report ADD COLUMN audioPath TEXT",
    "ALTER TABLE camera_report ADD COLUMN limitSource TEXT NOT NULL DEFAULT 'NONE'",
    "ALTER TABLE camera_report ADD COLUMN localPoiId INTEGER",
    "ALTER TABLE camera_report ADD COLUMN transcription TEXT",
)

/** v3: source ids on POIs: the PGW camera id carried in each OV2 entry's name, sent as `idCam` in reports (DESIGN.md §5.2a, §8.5). */
val MIGRATION_2_3 = migration(2, 3, "ALTER TABLE poi ADD COLUMN sourceId TEXT DEFAULT NULL")

/** v4: where contacts' addresses were found (DESIGN.md §8.3). */
val MIGRATION_3_4 = migration(
    3, 4,
    "CREATE TABLE IF NOT EXISTS `contact_geocode` (`key` TEXT NOT NULL, `placeId` TEXT, `lat` REAL NOT NULL, " +
        "`lon` REAL NOT NULL, `formattedAddress` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
)

/**
 * v5: OpenStreetMap speed limits fetched by tile from Overpass as you drove, in two tables of their own (`osm_limit`
 * with its tile index, `osm_tile`), which v6 drops; nothing in between read them. Stepping through makes nothing,
 * so an install still on v4 ends with the same schema as one that built and dropped them (DESIGN.md §8.4a "Replaced").
 */
val MIGRATION_4_5 = migration(4, 5)

/** v6: the Overpass speed-limit tiles are replaced by one downloaded file (DESIGN.md §8.4a); IF EXISTS, since v5 may have been skipped over. */
val MIGRATION_5_6 = migration(5, 6, "DROP TABLE IF EXISTS `osm_limit`", "DROP TABLE IF EXISTS `osm_tile`")

/**
 * v7: alerts default to seconds ahead at the current speed, 1st 15 s and 2nd 7 s (DESIGN.md §11.1).
 * Sets still on the old defaults move to the new ones; sets the user changed are left alone.
 * The seconds column is an integer, so N/2 = 7.5 is stored as 7; the global slider overrides it anyway (§11.1).
 */
val MIGRATION_6_7 = migration(
    6, 7,
    "UPDATE poi_set SET a1_mode = 'TIME', a1_metres = 242, a1_seconds = 15 " +
        "WHERE a1_mode = 'TIME' AND a1_metres = 457 AND a1_seconds = 30",
    "UPDATE poi_set SET a2_mode = 'TIME', a2_metres = 142, a2_seconds = 7 " +
        "WHERE a2_mode = 'FIXED' AND a2_metres = 274",
)

/** Home and Work from Google were removed (DESIGN.md §8.2a): any stored rows go, and PlaceKind no longer has them. */
val MIGRATION_7_8 = migration(7, 8, "DELETE FROM place WHERE kind IN ('HOME', 'WORK')")

/** Pending mobile sites alert by default now (DESIGN.md §5.2, owner 2026-10-23): off was only ever the default. */
val MIGRATION_8_9 = migration(8, 9, "UPDATE poi_set SET alertsEnabled = 1 WHERE source = 'PGW_PENDING'")

/** Hilt binding for the one database. Opened lazily on first use; migrations run then. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun db(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            // Last resort for a version with no migration path (a downgrade, or a step forgotten above): the
            // data is wiped and the cameras re-synced (§6.4). Every release step must have a migration instead.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
}

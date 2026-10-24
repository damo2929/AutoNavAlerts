// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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

/** v2: voice comments and review fields on camera reports (DESIGN.md §8.5): the recording path, the limit's source, the local marker and the transcription state. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE camera_report ADD COLUMN audioPath TEXT")
        db.execSQL("ALTER TABLE camera_report ADD COLUMN limitSource TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE camera_report ADD COLUMN localPoiId INTEGER")
        db.execSQL("ALTER TABLE camera_report ADD COLUMN transcription TEXT")
    }
}

/** v3: source ids on POIs: the PGW camera id carried in each OV2 entry's name, sent as `idCam` in reports (DESIGN.md §5.2a, §8.5). */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE poi ADD COLUMN sourceId TEXT DEFAULT NULL")
    }
}

/** v4: where contacts' addresses were found (DESIGN.md §8.3). */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `contact_geocode` (`key` TEXT NOT NULL, `placeId` TEXT, `lat` REAL NOT NULL, " +
                "`lon` REAL NOT NULL, `formattedAddress` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}

/** v5: OpenStreetMap speed limits fetched by tile from Overpass as you drove. Superseded by v6; kept so an old install can still step through (DESIGN.md §8.4a "Replaced"). */
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `osm_limit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tile` INTEGER NOT NULL, " +
                "`lat1` REAL NOT NULL, `lon1` REAL NOT NULL, `lat2` REAL NOT NULL, `lon2` REAL NOT NULL, `mph` REAL NOT NULL, `dir` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_osm_limit_tile` ON `osm_limit` (`tile`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `osm_tile` (`key` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, `ways` INTEGER NOT NULL, " +
                "`segments` INTEGER NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}

/** v6: the Overpass speed-limit tiles are replaced by one downloaded file (DESIGN.md §8.4a). */
val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `osm_limit`")
        db.execSQL("DROP TABLE IF EXISTS `osm_tile`")
    }
}

/**
 * v7: alerts default to seconds ahead at the current speed, 1st 15 s and 2nd 7 s (DESIGN.md §11.1).
 * Sets still on the old defaults move to the new ones; sets the user changed are left alone.
 */
val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("UPDATE poi_set SET a1_mode = 'TIME', a1_metres = 242, a1_seconds = 15 " +
            "WHERE a1_mode = 'TIME' AND a1_metres = 457 AND a1_seconds = 30")
        // The seconds column is an integer, so N/2 = 7.5 is stored as 7; the global slider overrides it anyway (§11.1).
        db.execSQL("UPDATE poi_set SET a2_mode = 'TIME', a2_metres = 142, a2_seconds = 7 " +
            "WHERE a2_mode = 'FIXED' AND a2_metres = 274")
    }
}

/** Home and Work from Google were removed (DESIGN.md §8.2a): any stored rows go, and PlaceKind no longer has them. */
val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM place WHERE kind IN ('HOME', 'WORK')")
    }
}

/** Pending mobile sites alert by default now (DESIGN.md §5.2, owner 2026-10-23): off was only ever the default. */
val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("UPDATE poi_set SET alertsEnabled = 1 WHERE source = 'PGW_PENDING'")
    }
}

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

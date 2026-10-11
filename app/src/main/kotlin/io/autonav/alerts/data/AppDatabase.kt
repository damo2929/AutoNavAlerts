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

@Database(
    entities = [PoiSetEntity::class, PoiCategoryEntity::class, PoiEntity::class, PlaceEntity::class, CameraReportEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun poiSets(): PoiSetDao
    abstract fun categories(): PoiCategoryDao
    abstract fun pois(): PoiDao
    abstract fun places(): PlaceDao
    abstract fun reports(): CameraReportDao

    companion object {
        /** File name is referenced by the backup exclusion rules (DESIGN.md §6.4). */
        const val NAME = "poi.db"
    }
}

/** v2: voice comments and review fields on camera reports (DESIGN.md §8.5). */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE camera_report ADD COLUMN audioPath TEXT")
        db.execSQL("ALTER TABLE camera_report ADD COLUMN limitSource TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE camera_report ADD COLUMN localPoiId INTEGER")
        db.execSQL("ALTER TABLE camera_report ADD COLUMN transcription TEXT")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun db(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
}

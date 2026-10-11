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
    version = 1,
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

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun db(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
}

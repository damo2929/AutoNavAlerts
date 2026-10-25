// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `poi.db`'s migrations (DESIGN.md §4.1 "DB v9", §5.2 the pending-mobile switch, §8.2a Home/Work removal,
 * §8.4a the dropped Overpass tables, §11.1 the stage defaults). The schemas aren't exported
 * (`exportSchema = false`), so MigrationTestHelper has nothing to read: instead a file at an old version is
 * written with raw SQL and opened through the production `DatabaseModule.db` chain, whose Room validation
 * fails the open if any table, column or index differs from the entities, and whose destructive fallback
 * would show up here as lost rows. Runs on the test thread.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class DbMigrationTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AppDatabase.NAME)
    }

    /** Writes a database file at [version] from [sql], then returns it opened through the app's own builder. */
    private fun migrate(version: Int, sql: List<String>, name: String = AppDatabase.NAME): AppDatabase {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { raw ->
            sql.forEach(raw::execSQL)
            raw.version = version
        }
        return if (name == AppDatabase.NAME) DatabaseModule.db(context)
        else Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*ALL).allowMainThreadQueries().build()
    }

    private fun AppDatabase.rows(sql: String): List<List<String?>> = openHelper.writableDatabase.query(sql).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
    }

    /** Tables and indices by name with their SQL, leaving out SQLite's and Room's own bookkeeping. */
    private fun AppDatabase.schema(): Map<String, String?> =
        rows("SELECT name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name")
            .associate { it[0]!! to it[1] }

    /** Columns of [table] as (name, type, not null, primary key): what Room's validation compares. */
    private fun AppDatabase.columns(table: String) = rows("PRAGMA table_info(`$table`)").map { listOf(it[1], it[2], it[3], it[5]) }

    @Test fun version1OpensThroughEveryStepWithItsDataAndTheCurrentSchema() = runBlocking<Unit> {
        val db = migrate(1, V1_SCHEMA + listOf(
            // A set still on the pre-v7 defaults (1st TIME 457 m / 30 s, 2nd FIXED 274 m) and one the user changed.
            set(1, "Old defaults", "FILE", a1 = "'TIME', 457, 30", a2 = "'FIXED', 274, NULL"),
            set(2, "User changed", "FILE", a1 = "'FIXED', 600, NULL", a2 = "'FIXED', 300, NULL"),
            set(3, "PGW pending mobile sites (unverified)", "PGW_PENDING", alerts = 0),
            set(4, "Switched off by user", "PGW", alerts = 0),
            "INSERT INTO poi (setId, categoryId, lat, lon, name, speedLimit, speedUnit, directionType, bearing, gridCell, hiddenByReport) " +
                "VALUES (1, NULL, 52.0, -1.0, 'x', 30, 'MPH', 'ONE_WAY', 90.0, 1, 0)",
            "INSERT INTO place (placeId, name, address, lat, lon, kind, lastUsedAt, useCount) VALUES (NULL, 'Home', NULL, 52, -1, 'HOME', 1, 1)",
            "INSERT INTO place (placeId, name, address, lat, lon, kind, lastUsedAt, useCount) VALUES (NULL, 'Work', NULL, 52, -1, 'WORK', 2, 1)",
            "INSERT INTO place (placeId, name, address, lat, lon, kind, lastUsedAt, useCount) VALUES (NULL, 'Shop', NULL, 52, -1, 'RECENT', 3, 1)",
            "INSERT INTO camera_report (kind, lat, lon, heading, speedMps, accuracyM, road, speedLimit, directionType, relatedPoiId, comment, createdAt, status, lastError) " +
                "VALUES ('MOBILE_SEEN', 52.0, -1.0, 90.0, 13.0, NULL, NULL, 30, 'ONE_WAY', NULL, 'van', 5, 'DRAFT', NULL)",
        ))

        // §11.1 (v7): sets still on the old defaults move to 1st TIME 242 m / 15 s and 2nd TIME 142 m / 7 s; changed sets stay.
        val sets = db.poiSets().active().associateBy { it.name }
        assertEquals(StageCols(true, io.autonav.alerts.core.alerts.DistanceMode.TIME, 242, 15), sets.getValue("Old defaults").firstAlert)
        assertEquals(StageCols(true, io.autonav.alerts.core.alerts.DistanceMode.TIME, 142, 7), sets.getValue("Old defaults").secondAlert)
        assertEquals(600, sets.getValue("User changed").firstAlert.metres)
        assertEquals(300, sets.getValue("User changed").secondAlert.metres)
        // §5.2 (v9): pending mobile sites alert by default; other sets keep the user's switch.
        assertTrue(sets.getValue("PGW pending mobile sites (unverified)").alertsEnabled)
        assertFalse(sets.getValue("Switched off by user").alertsEnabled)
        // §8.2a (v8): Home and Work rows are gone, recents stay.
        assertEquals(listOf("Shop"), db.places().list().map { it.name })
        // §8.5 (v2) and §5.2a (v3): the new columns arrive with their defaults on old rows.
        val report = db.reports().withStatus(ReportStatus.DRAFT).single()
        assertEquals(LimitSource.NONE, report.limitSource)
        assertEquals(null, report.localPoiId)
        assertEquals(null, db.pois().get(1)!!.sourceId)
        // §8.3 (v4): the contact cache exists and works.
        db.contactGeocodes().put(ContactGeocodeEntity("k", null, 1.0, 2.0, null, 3))
        assertEquals(mapOf("k" to 3L), db.contactGeocodes().usedAt())

        // Same tables, columns and indices as a database created fresh at v9.
        val fresh = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        assertEquals(fresh.schema().keys, db.schema().keys)
        for (table in listOf("poi_set", "poi_category", "poi", "place", "camera_report", "contact_geocode")) {
            assertEquals(table, fresh.columns(table), db.columns(table))
        }
        assertEquals(9, db.openHelper.readableDatabase.version)
        fresh.close()
        db.close()
    }

    @Test fun version4SkippingTheOverpassTablesEndsLikeVersion5WithThem() {
        // v4 → v5 makes nothing now; v5 → v6 drops the Overpass tables only if they exist (§8.4a "Replaced").
        val fromV4 = migrate(4, V4_SCHEMA, name = "from-v4.db")
        val fromV5 = migrate(5, V4_SCHEMA + OLD_V5_OSM_TABLES, name = "from-v5.db")
        val s4 = fromV4.schema()
        assertEquals(s4, fromV5.schema())
        assertFalse(s4.keys.any { it.startsWith("osm_") || it.contains("osm_limit") })
        fromV4.close()
        fromV5.close()
    }

    @Test fun eachMigrationStepIsOneVersionAndTheChainHasNoGap() {
        val steps = ALL.map { it.startVersion to it.endVersion }
        assertEquals((1..8).map { it to it + 1 }, steps)
    }

    private companion object {
        val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)

        const val POI_SET = "CREATE TABLE IF NOT EXISTS `poi_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
            "`source` TEXT NOT NULL, `format` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `showOnMap` INTEGER NOT NULL, `alertsEnabled` INTEGER NOT NULL, " +
            "`soundEnabled` INTEGER NOT NULL, `defaultIconId` TEXT NOT NULL, `firstTemplate` TEXT NOT NULL, `secondTemplate` TEXT NOT NULL, " +
            "`passingTemplate` TEXT NOT NULL, `minZoom` REAL NOT NULL, `state` TEXT NOT NULL, `replacesSetId` INTEGER, `poiCount` INTEGER NOT NULL, " +
            "`version` TEXT, `lastUpdated` INTEGER NOT NULL, `a1_enabled` INTEGER NOT NULL, `a1_mode` TEXT NOT NULL, `a1_metres` INTEGER NOT NULL, " +
            "`a1_seconds` INTEGER, `a2_enabled` INTEGER NOT NULL, `a2_mode` TEXT NOT NULL, `a2_metres` INTEGER NOT NULL, `a2_seconds` INTEGER)"
        val CATEGORY = listOf(
            "CREATE TABLE IF NOT EXISTS `poi_category` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `setId` INTEGER NOT NULL, `sourceCode` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `iconId` TEXT, `firstTemplate` TEXT, `secondTemplate` TEXT, `passingTemplate` TEXT, `alertsEnabled` INTEGER NOT NULL, " +
                "`soundEnabled` INTEGER, `zoneRole` TEXT NOT NULL, FOREIGN KEY(`setId`) REFERENCES `poi_set`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_poi_category_setId` ON `poi_category` (`setId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_poi_category_setId_sourceCode` ON `poi_category` (`setId`, `sourceCode`)",
        )
        fun poi(sourceId: Boolean) = listOf(
            "CREATE TABLE IF NOT EXISTS `poi` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `setId` INTEGER NOT NULL, `categoryId` INTEGER, " +
                "`lat` REAL NOT NULL, `lon` REAL NOT NULL, `name` TEXT, `speedLimit` INTEGER, `speedUnit` TEXT, `directionType` TEXT NOT NULL, " +
                "`bearing` REAL, `gridCell` INTEGER NOT NULL, `hiddenByReport` INTEGER NOT NULL, `expiresAt` INTEGER DEFAULT NULL" +
                (if (sourceId) ", `sourceId` TEXT DEFAULT NULL" else "") +
                ", FOREIGN KEY(`setId`) REFERENCES `poi_set`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_poi_setId_gridCell` ON `poi` (`setId`, `gridCell`)",
            "CREATE INDEX IF NOT EXISTS `index_poi_gridCell` ON `poi` (`gridCell`)",
            "CREATE INDEX IF NOT EXISTS `index_poi_categoryId` ON `poi` (`categoryId`)",
        )
        val PLACE = listOf(
            "CREATE TABLE IF NOT EXISTS `place` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `placeId` TEXT, `name` TEXT NOT NULL, `address` TEXT, " +
                "`lat` REAL NOT NULL, `lon` REAL NOT NULL, `kind` TEXT NOT NULL, `lastUsedAt` INTEGER NOT NULL, `useCount` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_place_lastUsedAt` ON `place` (`lastUsedAt`)",
        )
        fun report(v2Columns: Boolean) = listOf(
            "CREATE TABLE IF NOT EXISTS `camera_report` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `lat` REAL NOT NULL, " +
                "`lon` REAL NOT NULL, `heading` REAL, `speedMps` REAL, `accuracyM` REAL, `road` TEXT, `speedLimit` INTEGER, `directionType` TEXT NOT NULL, " +
                "`relatedPoiId` INTEGER, `comment` TEXT, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `lastError` TEXT" +
                (if (v2Columns) ", `audioPath` TEXT, `limitSource` TEXT NOT NULL, `localPoiId` INTEGER, `transcription` TEXT" else "") + ")",
            "CREATE INDEX IF NOT EXISTS `index_camera_report_status` ON `camera_report` (`status`)",
        )
        const val CONTACT = "CREATE TABLE IF NOT EXISTS `contact_geocode` (`key` TEXT NOT NULL, `placeId` TEXT, `lat` REAL NOT NULL, `lon` REAL NOT NULL, " +
            "`formattedAddress` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))"

        /** v1 as the entities had it before the voice-comment columns, source ids and the contact cache. */
        val V1_SCHEMA = listOf(POI_SET) + CATEGORY + poi(sourceId = false) + PLACE + report(v2Columns = false)
        /** v4 (and v6 to v9): every table of today. */
        val V4_SCHEMA = listOf(POI_SET) + CATEGORY + poi(sourceId = true) + PLACE + report(v2Columns = true) + CONTACT
        /** The Overpass tile tables v5 used to make (§8.4a "Replaced"), made up to the shape the design describes: limits with a tile index, and tiles. */
        val OLD_V5_OSM_TABLES = listOf(
            "CREATE TABLE IF NOT EXISTS `osm_limit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tile` INTEGER NOT NULL, `mph` INTEGER NOT NULL, `points` BLOB NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_osm_limit_tile` ON `osm_limit` (`tile`)",
            "CREATE TABLE IF NOT EXISTS `osm_tile` (`tile` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`tile`))",
            "INSERT INTO osm_tile (tile, fetchedAt) VALUES (7, 1)",
        )

        fun set(id: Int, name: String, source: String, alerts: Int = 1, a1: String = "'TIME', 242, 15", a2: String = "'TIME', 142, 7") =
            "INSERT INTO poi_set (id, name, source, format, enabled, showOnMap, alertsEnabled, soundEnabled, defaultIconId, firstTemplate, " +
                "secondTemplate, passingTemplate, minZoom, state, replacesSetId, poiCount, version, lastUpdated, a1_enabled, a1_mode, a1_metres, " +
                "a1_seconds, a2_enabled, a2_mode, a2_metres, a2_seconds) VALUES ($id, '$name', '$source', 'F$id', 1, 1, $alerts, 1, 'builtin:pin', " +
                "'{category}', '{category}', '{category}', 11.0, 'ACTIVE', NULL, 0, NULL, 1, 1, $a1, 1, $a2)"
    }
}

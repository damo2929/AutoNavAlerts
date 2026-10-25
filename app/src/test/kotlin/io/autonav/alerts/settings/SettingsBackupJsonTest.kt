// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.alerts.Units
import io.autonav.alerts.core.pgw.PgwDownload
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.PoiSourceType
import io.autonav.alerts.data.StageCols
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the settings backup file's `settings` and `poiSets` layout (DESIGN.md §13.4, format 1), the restore
 * rules (a missing or unreadable value keeps the current one, the clamps, `alertLeadSeconds` from a JSON
 * double) and the DataStore key names behind [SettingsCodec], so the field table can change shape without
 * the file or the stored keys changing. Real org.json on the JVM (Android's is a stub there).
 */
class SettingsBackupJsonTest {
    /** A JSONObject as plain maps (nested objects too, JSON null as null); the compile-time stub has no `toMap()`. */
    private fun JSONObject.asMap(): Map<String, Any?> = keys().asSequence().associateWith { k ->
        when (val v = opt(k)) {
            JSONObject.NULL -> null
            is JSONObject -> v.asMap()
            else -> v
        }
    }

    /** Every field away from its default. */
    private val all = AppSettings(
        units = Units.METRIC, voiceOn = false, chimeOn = false, overspeedPct = 0.123, alertLeadSeconds = 42,
        announceLimitChanges = false, pgwAutoSync = false, pgwWifiOnly = true, speedPanelOn = false, satelliteMap = true,
        evEfficientRoutes = true, avoidTolls = false, osmLimitsOn = false, osmWifiOnly = false, mutedUntilMs = 123_456_789L,
        simulationSpeed = 4f, speedSource = SpeedSource.PHONE_GPS, pgwDownload = PgwDownload.IGO, driveRecordingOn = true,
    )

    /** The 16 backed-up fields by JSON name; mute, the simulator speed and drive recording are left out (§13.4 "Not included"). */
    private val settingsLiteral = mapOf(
        "units" to "METRIC", "voiceOn" to false, "chimeOn" to false, "overspeedPct" to 0.123, "alertLeadSeconds" to 42,
        "announceLimitChanges" to false, "speedSource" to "PHONE_GPS", "speedPanelOn" to false, "satelliteMap" to true,
        "evEfficientRoutes" to true, "avoidTolls" to false, "osmLimitsOn" to false, "osmWifiOnly" to false,
        "pgwAutoSync" to false, "pgwWifiOnly" to true, "pgwDownload" to "IGO",
    )

    @Test fun settingsJsonIsTheFormat1Layout() {
        assertEquals(settingsLiteral, SettingsBackup.settingsJson(all).asMap())
    }

    @Test fun settingsRoundTripRestoresEveryBackedUpField() {
        val restored = SettingsBackup.applySettings(AppSettings(), SettingsBackup.settingsJson(all))
        // The three fields outside the backup keep the current (default) values.
        assertEquals(all.copy(mutedUntilMs = 0, simulationSpeed = 1f, driveRecordingOn = false), restored)
    }

    @Test fun restoreKeepsCurrentValuesForMissingOrBadOnesAndClamps() {
        val cur = all
        val j = JSONObject("""{"units":"FURLONGS","voiceOn":null,"chimeOn":"maybe","overspeedPct":0.5,"alertLeadSeconds":30.7,
            "speedSource":7,"pgwDownload":"","mutedUntilMs":5,"simulationSpeed":2,"driveRecordingOn":false}""")
        val r = SettingsBackup.applySettings(cur, j)
        assertEquals(cur.copy(overspeedPct = 0.20, alertLeadSeconds = 30), r)
        assertEquals(60, SettingsBackup.applySettings(cur, JSONObject("""{"alertLeadSeconds":99}""")).alertLeadSeconds)
        assertEquals(15, SettingsBackup.applySettings(cur, JSONObject("""{"alertLeadSeconds":1}""")).alertLeadSeconds)
        assertEquals(-0.05, SettingsBackup.applySettings(cur, JSONObject("""{"overspeedPct":-1}""")).overspeedPct, 0.0)
        assertEquals(Units.IMPERIAL, SettingsBackup.applySettings(cur, JSONObject("""{"units":"IMPERIAL"}""")).units)
        assertEquals(cur, SettingsBackup.applySettings(cur, JSONObject("{}")))
    }

    @Test fun dataStoreKeysAndValuesAreUnchanged() {
        val p = mutablePreferencesOf()
        SettingsCodec.write(p, all)
        val expected = mapOf<String, Any>(
            "units" to "METRIC", "voice" to false, "chime" to false, "overspeed_pct" to 0.123, "alert_lead_seconds" to 42,
            "announce_limit_changes" to false, "pgw_auto" to false, "pgw_wifi_only" to true, "speed_panel" to false,
            "satellite_map" to true, "osm_limits" to false, "osm_wifi_only" to false, "ev_efficient_routes" to true,
            "avoid_tolls" to false, "muted_until" to 123_456_789L, "speed_source" to "PHONE_GPS", "pgw_download" to "IGO",
            "sim_speed" to 4f, "drive_recording" to true,
        )
        assertEquals(expected, p.asMap().mapKeys { it.key.name })
        assertEquals(all, SettingsCodec.read(p))
        assertEquals(AppSettings(), SettingsCodec.read(mutablePreferencesOf()))
    }

    @Test fun dataStoreReadClampsAndDefaultsBadValues() {
        val p = mutablePreferencesOf()
        SettingsCodec.write(p, all.copy(overspeedPct = 9.0, alertLeadSeconds = 1))
        val r = SettingsCodec.read(p)
        assertEquals(0.20, r.overspeedPct, 0.0)
        assertEquals(15, r.alertLeadSeconds)
        p[androidx.datastore.preferences.core.stringPreferencesKey("units")] = "FURLONGS"
        p[androidx.datastore.preferences.core.stringPreferencesKey("speed_source")] = "BIKE"
        p[androidx.datastore.preferences.core.stringPreferencesKey("pgw_download")] = "FLOPPY"
        assertEquals(Units.IMPERIAL, SettingsCodec.read(p).units)
        assertEquals(SpeedSource.CAR_WHEN_AVAILABLE, SettingsCodec.read(p).speedSource)
        assertEquals(PgwDownload.DEFAULT, SettingsCodec.read(p).pgwDownload)
    }

    @Test fun pgwStateRoundTripsAndNullsRemoveKeys() {
        val full = PgwState(
            installedVersion = "24.101", installedPackage = 288, installedLayout = 3, latestVersion = "24.102", latestSummary = "New mobiles",
            pendingVersion = "24.102", lastFeedCheckMs = 1_000L, lastSyncMs = 2_000L, lastResult = "Updated", feedEtag = "W/\"x\"", feedLastModified = "Mon",
        )
        val p = mutablePreferencesOf()
        SettingsCodec.writePgw(p, full)
        assertEquals(
            setOf("pgw_installed", "pgw_installed_package", "pgw_installed_layout", "pgw_latest", "pgw_latest_summary", "pgw_pending",
                "pgw_feed_check", "pgw_last_sync", "pgw_result", "pgw_etag", "pgw_last_modified"),
            p.asMap().keys.map { it.name }.toSet(),
        )
        assertEquals(full, SettingsCodec.readPgw(p))
        SettingsCodec.writePgw(p, PgwState())
        assertEquals(setOf("pgw_feed_check", "pgw_last_sync"), p.asMap().keys.map { it.name }.toSet())
        assertEquals(PgwState(), SettingsCodec.readPgw(p))
        assertEquals(PgwState(), SettingsCodec.readPgw(mutablePreferencesOf()))
    }

    private fun set(name: String, source: PoiSourceType) = PoiSetEntity(
        id = 7, name = name, source = source, format = "OV2:x", enabled = false, showOnMap = false, alertsEnabled = false, soundEnabled = false,
        defaultIconId = "user:pgw_gatso_30", firstTemplate = "{category} ahead", secondTemplate = "{category}, {distance}", passingTemplate = "{category} now",
        firstAlert = StageCols(true, DistanceMode.TIME, 457, 30), secondAlert = StageCols(true, DistanceMode.FIXED, 274, null),
        minZoom = 12.5f, poiCount = 9, version = "24.101", lastUpdated = 5,
    )

    @Test fun setJsonIsTheFormat1LayoutAndRestores() {
        val a = set("PGW fixed cameras · 30 mph", PoiSourceType.PGW)
        val b = set("My reports", PoiSourceType.REPORT)
        assertEquals(
            mapOf(
                "name" to "PGW fixed cameras · 30 mph", "source" to "PGW", "enabled" to false, "showOnMap" to false, "alertsEnabled" to false,
                "soundEnabled" to false, "icon" to "user:pgw_gatso_30", "minZoom" to 12.5,
                "templates" to mapOf("first" to "{category} ahead", "second" to "{category}, {distance}", "passing" to "{category} now"),
                "firstAlert" to mapOf("enabled" to true, "mode" to "TIME", "metres" to 457, "seconds" to 30),
                "secondAlert" to mapOf("enabled" to true, "mode" to "FIXED", "metres" to 274, "seconds" to null),
            ),
            SettingsBackup.setJson(a).asMap(),
        )
        assertEquals("REPORT", SettingsBackup.setJson(b).getString("source"))
        // A set on this phone with the defaults takes the file's switches, icon (it exists here), templates, stages and zoom.
        val here = a.copy(enabled = true, showOnMap = true, alertsEnabled = true, soundEnabled = true, defaultIconId = "builtin:camera",
            firstTemplate = "x", secondTemplate = "y", passingTemplate = "z",
            firstAlert = StageCols(true, DistanceMode.FIXED, 300, null), secondAlert = StageCols(false, DistanceMode.FIXED, 150, null), minZoom = 11f)
        assertEquals(a, SettingsBackup.applySet(here, SettingsBackup.setJson(a), setOf("user:pgw_gatso_30", "builtin:camera")))
        // An icon the phone doesn't have keeps the current one.
        assertEquals("builtin:camera", SettingsBackup.applySet(here, SettingsBackup.setJson(a), setOf("builtin:camera")).defaultIconId)
    }
}

// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.BuildConfig
import io.autonav.alerts.core.alerts.Units
import io.autonav.alerts.core.location.RateCurve
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val units: Units = Units.IMPERIAL,
    val voiceOn: Boolean = true,
    val chimeOn: Boolean = true,
    val overspeedPct: Double = 0.05,
    val rateCurve: RateCurve = RateCurve(),
    val pgwAutoSync: Boolean = true,
    val pgwWifiOnly: Boolean = false,
    val reviewReportsFirst: Boolean = true,
    val speedPanelOn: Boolean = true,
    val mutedUntilMs: Long = 0,
)

data class PgwState(
    val installedVersion: String? = null,
    val latestVersion: String? = null,
    val latestSummary: String? = null,
    val pendingVersion: String? = null,
    val lastFeedCheckMs: Long = 0,
    val lastSyncMs: Long = 0,
    val lastResult: String? = null,
    val feedEtag: String? = null,
    val feedLastModified: String? = null,
)

/** All persisted settings. Secrets are stored encrypted with [SecretBox]. */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val ds get() = context.dataStore

    private object K {
        val units = stringPreferencesKey("units")
        val voice = booleanPreferencesKey("voice")
        val chime = booleanPreferencesKey("chime")
        val overspeed = doublePreferencesKey("overspeed_pct")
        val gpsMinMph = doublePreferencesKey("gps_min_mph")
        val gpsMaxMph = doublePreferencesKey("gps_max_mph")
        val gpsMaxHz = intPreferencesKey("gps_max_hz")
        val pgwAuto = booleanPreferencesKey("pgw_auto")
        val pgwWifi = booleanPreferencesKey("pgw_wifi_only")
        val reviewReports = booleanPreferencesKey("review_reports")
        val speedPanel = booleanPreferencesKey("speed_panel")
        val mutedUntil = longPreferencesKey("muted_until")

        val mapsKey = stringPreferencesKey("maps_api_key_enc")
        val pgwUser = stringPreferencesKey("pgw_user_enc")
        val pgwPass = stringPreferencesKey("pgw_pass_enc")
        val pgwCookies = stringPreferencesKey("pgw_cookies_enc")

        val pgwInstalled = stringPreferencesKey("pgw_installed")
        val pgwLatest = stringPreferencesKey("pgw_latest")
        val pgwLatestSummary = stringPreferencesKey("pgw_latest_summary")
        val pgwPending = stringPreferencesKey("pgw_pending")
        val pgwFeedCheck = longPreferencesKey("pgw_feed_check")
        val pgwLastSync = longPreferencesKey("pgw_last_sync")
        val pgwResult = stringPreferencesKey("pgw_result")
        val pgwEtag = stringPreferencesKey("pgw_etag")
        val pgwLastModified = stringPreferencesKey("pgw_last_modified")
    }

    val settings: Flow<AppSettings> = ds.data.map { p ->
        AppSettings(
            units = p[K.units]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: Units.IMPERIAL,
            voiceOn = p[K.voice] ?: true,
            chimeOn = p[K.chime] ?: true,
            overspeedPct = p[K.overspeed] ?: 0.05,
            rateCurve = runCatching {
                RateCurve(minMph = p[K.gpsMinMph] ?: 10.0, maxMph = p[K.gpsMaxMph] ?: 50.0, maxHz = p[K.gpsMaxHz] ?: 10)
            }.getOrDefault(RateCurve()),
            pgwAutoSync = p[K.pgwAuto] ?: true,
            pgwWifiOnly = p[K.pgwWifi] ?: false,
            reviewReportsFirst = p[K.reviewReports] ?: true,
            speedPanelOn = p[K.speedPanel] ?: true,
            mutedUntilMs = p[K.mutedUntil] ?: 0,
        )
    }

    val pgwState: Flow<PgwState> = ds.data.map { p ->
        PgwState(
            installedVersion = p[K.pgwInstalled], latestVersion = p[K.pgwLatest], latestSummary = p[K.pgwLatestSummary],
            pendingVersion = p[K.pgwPending], lastFeedCheckMs = p[K.pgwFeedCheck] ?: 0, lastSyncMs = p[K.pgwLastSync] ?: 0,
            lastResult = p[K.pgwResult], feedEtag = p[K.pgwEtag], feedLastModified = p[K.pgwLastModified],
        )
    }

    suspend fun current(): AppSettings = settings.first()
    suspend fun currentPgw(): PgwState = pgwState.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        val s = block(current())
        ds.edit { p ->
            p[K.units] = s.units.name
            p[K.voice] = s.voiceOn
            p[K.chime] = s.chimeOn
            p[K.overspeed] = s.overspeedPct
            p[K.gpsMinMph] = s.rateCurve.minMph
            p[K.gpsMaxMph] = s.rateCurve.maxMph
            p[K.gpsMaxHz] = s.rateCurve.maxHz
            p[K.pgwAuto] = s.pgwAutoSync
            p[K.pgwWifi] = s.pgwWifiOnly
            p[K.reviewReports] = s.reviewReportsFirst
            p[K.speedPanel] = s.speedPanelOn
            p[K.mutedUntil] = s.mutedUntilMs
        }
    }

    suspend fun updatePgw(block: (PgwState) -> PgwState) {
        val s = block(currentPgw())
        ds.edit { p ->
            fun put(k: Preferences.Key<String>, v: String?) { if (v == null) p.remove(k) else p[k] = v }
            put(K.pgwInstalled, s.installedVersion); put(K.pgwLatest, s.latestVersion)
            put(K.pgwLatestSummary, s.latestSummary); put(K.pgwPending, s.pendingVersion)
            put(K.pgwResult, s.lastResult); put(K.pgwEtag, s.feedEtag); put(K.pgwLastModified, s.feedLastModified)
            p[K.pgwFeedCheck] = s.lastFeedCheckMs
            p[K.pgwLastSync] = s.lastSyncMs
        }
    }

    // --- Secrets ---------------------------------------------------------------------------

    private suspend fun getSecret(k: Preferences.Key<String>): String? = ds.data.first()[k]?.let(SecretBox::decrypt)

    private suspend fun putSecret(k: Preferences.Key<String>, v: String?) {
        ds.edit { p -> if (v.isNullOrEmpty()) p.remove(k) else p[k] = SecretBox.encrypt(v) }
    }

    /** User-provided Maps key (Settings), falling back to a key baked in at build time, if any. */
    suspend fun mapsApiKey(): String? =
        getSecret(K.mapsKey)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.MAPS_API_KEY.takeIf { it.isNotBlank() && it != "DEFAULT_API_KEY" }

    suspend fun hasUserMapsKey(): Boolean = getSecret(K.mapsKey)?.isNotBlank() == true
    suspend fun setMapsApiKey(key: String?) = putSecret(K.mapsKey, key?.trim())

    suspend fun pgwCredentials(): Pair<String, String>? {
        val u = getSecret(K.pgwUser) ?: return null
        val p = getSecret(K.pgwPass) ?: return null
        return u to p
    }

    suspend fun setPgwCredentials(user: String?, password: String?) {
        putSecret(K.pgwUser, user)
        putSecret(K.pgwPass, password)
        putSecret(K.pgwCookies, null)
    }

    suspend fun pgwCookies(): String? = getSecret(K.pgwCookies)
    suspend fun setPgwCookies(serialized: String?) = putSecret(K.pgwCookies, serialized)
}

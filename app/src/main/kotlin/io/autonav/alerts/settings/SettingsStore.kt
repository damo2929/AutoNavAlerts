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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

const val DEFAULT_OVERSPEED = -0.001
const val DEFAULT_ALERT_LEAD_SECONDS = 15
const val MIN_ALERT_LEAD_SECONDS = 15
const val MAX_ALERT_LEAD_SECONDS = 60

/**
 * The voice languages offered (DESIGN.md §12.3): one language for the turn-by-turn voice and the
 * alerts. No US English (owner, 2026-10-23: there is no US camera data).
 */
object VoiceLanguages {
    const val DEFAULT = "en-GB"
    val ALL: List<Pair<String, String>> = listOf(
        "en-GB" to "English (UK)",
        "en-IE" to "English (Ireland)",
        "en-AU" to "English (Australia)",
        "en-IN" to "English (India)",
    )

    /**
     * The TTS language and voice that sound like the Navigation SDK's prompts in each language,
     * measured on the bench (DESIGN.md §12.3): voice names without the `-local`/`-network` suffix.
     * Irish English is the UK voice: its SDK pack is the UK one and the engine has no en-IE voices.
     */
    val TTS_VOICE: Map<String, Pair<String, String>> = mapOf(
        "en-GB" to ("en-GB" to "en-gb-x-rjs"),
        "en-IE" to ("en-GB" to "en-gb-x-rjs"),
        "en-AU" to ("en-AU" to "en-au-x-aua"),
        "en-IN" to ("en-IN" to "en-in-x-enc"),
    )

    /** A stored tag that's no longer offered (or none) reads as the default. */
    fun valid(tag: String?): String = tag?.takeIf { t -> ALL.any { it.first == t } } ?: DEFAULT

    fun label(tag: String): String = ALL.firstOrNull { it.first == tag }?.second ?: tag
}

/** Where speed comes from (DESIGN.md §10.6). */
enum class SpeedSource { CAR_WHEN_AVAILABLE, PHONE_GPS }

data class AppSettings(
    val units: Units = Units.IMPERIAL,
    val voiceOn: Boolean = true,
    /** One language for the turn-by-turn voice and the alerts (DESIGN.md §12.3). */
    val voiceLanguage: String = VoiceLanguages.DEFAULT,
    val chimeOn: Boolean = true,
    /** Over-limit warning threshold as a fraction: −0.001 = warn above 99.9% of the limit. */
    val overspeedPct: Double = DEFAULT_OVERSPEED,
    /**
     * Seconds ahead at the current speed for the 1st alert of every set in TIME mode; the 2nd fires
     * at half (DESIGN.md §11.1). 15–60.
     */
    val alertLeadSeconds: Int = DEFAULT_ALERT_LEAD_SECONDS,
    /** Say the new limit once as the car approaches a change of limit (DESIGN.md §8.4a). */
    val announceLimitChanges: Boolean = true,
    val pgwAutoSync: Boolean = true,
    val pgwWifiOnly: Boolean = false,
    val speedPanelOn: Boolean = true,
    /** Car map shows satellite imagery (with road names) instead of the road map. */
    val satelliteMap: Boolean = false,
    /** Route for least energy use by an electric car (DESIGN.md §8.3a). */
    val evEfficientRoutes: Boolean = false,
    /** Avoid toll roads where there's a way round (DESIGN.md §8.3a). */
    val avoidTolls: Boolean = true,
    /** OpenStreetMap limits on the speed panel (DESIGN.md §8.4a). */
    val osmLimitsOn: Boolean = true,
    /** The speed-limit download waits for Wi-Fi; off lets it use mobile data too. */
    val osmWifiOnly: Boolean = true,
    val mutedUntilMs: Long = 0,
    /** Debug builds: Navigation SDK simulator speed multiplier for bench testing. */
    val simulationSpeed: Float = 1f,
    val speedSource: SpeedSource = SpeedSource.CAR_WHEN_AVAILABLE,
    /** Which PocketGPSWorld download to use (Cameras tab). */
    val pgwDownload: io.autonav.alerts.core.pgw.PgwDownload = io.autonav.alerts.core.pgw.PgwDownload.DEFAULT,
    /** Record every fix to a CSV for bench replay (DESIGN.md §15.5). Not in the settings backup. */
    val driveRecordingOn: Boolean = false,
)

data class PgwState(
    val installedVersion: String? = null,
    /** PGW package id the installed data came from (61 single file, 288 consolidated by type). */
    val installedPackage: Int? = null,
    /** Import layout the installed data was built with; a different value forces a re-download. */
    val installedLayout: Int? = null,
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
        val voiceLanguage = stringPreferencesKey("voice_language")
        val chime = booleanPreferencesKey("chime")
        val overspeed = doublePreferencesKey("overspeed_pct")
        val alertLead = intPreferencesKey("alert_lead_seconds")
        val announceLimits = booleanPreferencesKey("announce_limit_changes")
        val pgwAuto = booleanPreferencesKey("pgw_auto")
        val pgwWifi = booleanPreferencesKey("pgw_wifi_only")
        val speedPanel = booleanPreferencesKey("speed_panel")
        val satellite = booleanPreferencesKey("satellite_map")
        val osmLimits = booleanPreferencesKey("osm_limits")
        val osmWifiOnly = booleanPreferencesKey("osm_wifi_only")
        val evRoutes = booleanPreferencesKey("ev_efficient_routes")
        val avoidTolls = booleanPreferencesKey("avoid_tolls")
        val mutedUntil = longPreferencesKey("muted_until")
        val speedSource = stringPreferencesKey("speed_source")
        val pgwDownload = stringPreferencesKey("pgw_download")
        val simSpeed = androidx.datastore.preferences.core.floatPreferencesKey("sim_speed")
        val driveRecording = booleanPreferencesKey("drive_recording")

        val mapsKey = stringPreferencesKey("maps_api_key_enc")
        val pgwUser = stringPreferencesKey("pgw_user_enc")
        val pgwPass = stringPreferencesKey("pgw_pass_enc")
        val pgwCookies = stringPreferencesKey("pgw_cookies_enc")

        val termsAcceptedAt = longPreferencesKey("nav_terms_accepted_at")
        val pgwInstalled = stringPreferencesKey("pgw_installed")
        val pgwInstalledPackage = intPreferencesKey("pgw_installed_package")
        val pgwInstalledLayout = intPreferencesKey("pgw_installed_layout")
        val pgwLatest = stringPreferencesKey("pgw_latest")
        val pgwLatestSummary = stringPreferencesKey("pgw_latest_summary")
        val pgwPending = stringPreferencesKey("pgw_pending")
        val pgwFeedCheck = longPreferencesKey("pgw_feed_check")
        val pgwLastSync = longPreferencesKey("pgw_last_sync")
        val pgwResult = stringPreferencesKey("pgw_result")
        val pgwEtag = stringPreferencesKey("pgw_etag")
        val pgwLastModified = stringPreferencesKey("pgw_last_modified")
    }

    val settings: Flow<AppSettings> = ds.data.map { p -> read(p) }

    private fun read(p: Preferences): AppSettings =
        AppSettings(
            units = p[K.units]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: Units.IMPERIAL,
            voiceOn = p[K.voice] ?: true,
            voiceLanguage = VoiceLanguages.valid(p[K.voiceLanguage]),
            chimeOn = p[K.chime] ?: true,
            overspeedPct = (p[K.overspeed] ?: DEFAULT_OVERSPEED).coerceIn(-0.05, 0.20),
            alertLeadSeconds = (p[K.alertLead] ?: DEFAULT_ALERT_LEAD_SECONDS).coerceIn(MIN_ALERT_LEAD_SECONDS, MAX_ALERT_LEAD_SECONDS),
            announceLimitChanges = p[K.announceLimits] ?: true,
            pgwAutoSync = p[K.pgwAuto] ?: true,
            pgwWifiOnly = p[K.pgwWifi] ?: false,
            speedPanelOn = p[K.speedPanel] ?: true,
            satelliteMap = p[K.satellite] ?: false,
            evEfficientRoutes = p[K.evRoutes] ?: false,
            avoidTolls = p[K.avoidTolls] ?: true,
            osmLimitsOn = p[K.osmLimits] ?: true,
            osmWifiOnly = p[K.osmWifiOnly] ?: true,
            mutedUntilMs = p[K.mutedUntil] ?: 0,
            simulationSpeed = p[K.simSpeed] ?: 1f,
            pgwDownload = io.autonav.alerts.core.pgw.PgwDownload.fromName(p[K.pgwDownload]),
            speedSource = p[K.speedSource]?.let { runCatching { SpeedSource.valueOf(it) }.getOrNull() } ?: SpeedSource.CAR_WHEN_AVAILABLE,
            driveRecordingOn = p[K.driveRecording] ?: false,
        )

    val pgwState: Flow<PgwState> = ds.data.map { p ->
        PgwState(
            installedVersion = p[K.pgwInstalled], installedPackage = p[K.pgwInstalledPackage], installedLayout = p[K.pgwInstalledLayout], latestVersion = p[K.pgwLatest], latestSummary = p[K.pgwLatestSummary],
            pendingVersion = p[K.pgwPending], lastFeedCheckMs = p[K.pgwFeedCheck] ?: 0, lastSyncMs = p[K.pgwLastSync] ?: 0,
            lastResult = p[K.pgwResult], feedEtag = p[K.pgwEtag], feedLastModified = p[K.pgwLastModified],
        )
    }

    suspend fun current(): AppSettings = settings.first()
    suspend fun currentPgw(): PgwState = pgwState.first()

    /** Read-modify-write inside one DataStore transaction, so two updates at once can't lose either. */
    suspend fun update(block: (AppSettings) -> AppSettings) {
        ds.edit { p ->
            val s = block(read(p))
            p[K.units] = s.units.name
            p[K.voice] = s.voiceOn
            p[K.voiceLanguage] = VoiceLanguages.valid(s.voiceLanguage)
            p[K.chime] = s.chimeOn
            p[K.overspeed] = s.overspeedPct
            p[K.alertLead] = s.alertLeadSeconds
            p[K.announceLimits] = s.announceLimitChanges
            p[K.pgwAuto] = s.pgwAutoSync
            p[K.pgwWifi] = s.pgwWifiOnly
            p[K.speedPanel] = s.speedPanelOn
            p[K.satellite] = s.satelliteMap
            p[K.evRoutes] = s.evEfficientRoutes
            p[K.avoidTolls] = s.avoidTolls
            p[K.osmLimits] = s.osmLimitsOn
            p[K.osmWifiOnly] = s.osmWifiOnly
            p[K.mutedUntil] = s.mutedUntilMs
            p[K.simSpeed] = s.simulationSpeed
            p[K.speedSource] = s.speedSource.name
            p[K.pgwDownload] = s.pgwDownload.name
            p[K.driveRecording] = s.driveRecordingOn
        }
    }

    suspend fun updatePgw(block: (PgwState) -> PgwState) {
        val s = block(currentPgw())
        ds.edit { p ->
            fun put(k: Preferences.Key<String>, v: String?) { if (v == null) p.remove(k) else p[k] = v }
            put(K.pgwInstalled, s.installedVersion); put(K.pgwLatest, s.latestVersion)
            put(K.pgwLatestSummary, s.latestSummary); put(K.pgwPending, s.pendingVersion)
            put(K.pgwResult, s.lastResult); put(K.pgwEtag, s.feedEtag); put(K.pgwLastModified, s.feedLastModified)
            s.installedPackage?.let { p[K.pgwInstalledPackage] = it } ?: p.remove(K.pgwInstalledPackage)
            s.installedLayout?.let { p[K.pgwInstalledLayout] = it } ?: p.remove(K.pgwInstalledLayout)
            p[K.pgwFeedCheck] = s.lastFeedCheckMs
            p[K.pgwLastSync] = s.lastSyncMs
        }
    }

    // --- Navigation SDK terms (accepted once, DESIGN.md §13) --------------------------------

    /** When the user accepted Google's navigation terms in this app, or null if never. */
    suspend fun navTermsAcceptedAt(): Long? = ds.data.first()[K.termsAcceptedAt]?.takeIf { it > 0 }

    suspend fun setNavTermsAccepted(atMs: Long) { ds.edit { it[K.termsAcceptedAt] = atMs } }

    // --- Secrets ---------------------------------------------------------------------------

    private suspend fun getSecret(k: Preferences.Key<String>): String? = ds.data.first()[k]?.let(SecretBox::decrypt)

    private suspend fun putSecret(k: Preferences.Key<String>, v: String?) {
        ds.edit { p -> if (v.isNullOrEmpty()) p.remove(k) else p[k] = SecretBox.encrypt(v) }
    }

    /**
     * The user's own Maps key (created by the app or pasted). There's deliberately no built-in or
     * build-time fallback: every user's Maps use is billed to their own Google account (DESIGN.md §1.4).
     */
    suspend fun mapsApiKey(): String? = getSecret(K.mapsKey)?.takeIf { it.isNotBlank() }
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

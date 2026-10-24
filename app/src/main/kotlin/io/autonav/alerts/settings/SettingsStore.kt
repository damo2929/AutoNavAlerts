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

// One Preferences DataStore file, "settings", for every setting, the PGW sync state and the encrypted secrets.
private val Context.dataStore by preferencesDataStore(name = "settings")

/** Over-limit warning default: −0.1%, i.e. warn just below the limit (DESIGN.md §11 step 9; slider −5% to +20%). */
const val DEFAULT_OVERSPEED = -0.001
/** The global alert lead-time slider (DESIGN.md §11.1): 1st alert N s ahead, 2nd at N/2; 15–60 s, default 15. */
const val DEFAULT_ALERT_LEAD_SECONDS = 15
const val MIN_ALERT_LEAD_SECONDS = 15
const val MAX_ALERT_LEAD_SECONDS = 60

/** Where speed comes from (DESIGN.md §10.6): the car's own speed when the head unit offers it, or the phone's GPS only. */
enum class SpeedSource { CAR_WHEN_AVAILABLE, PHONE_GPS }

/**
 * Every user setting, as one immutable snapshot (DESIGN.md §13 item 9; each one explained in docs/SETTINGS.md).
 * Read as a Flow from [SettingsStore.settings] or once with [SettingsStore.current]; changed only through
 * [SettingsStore.update]. All but [mutedUntilMs], [simulationSpeed] and [driveRecordingOn] go in the settings
 * backup file (§13.4).
 */
data class AppSettings(
    /** mph and miles, or km/h and km, on the car screen and in speech (§7.2 `{units}`). */
    val units: Units = Units.IMPERIAL,
    /** Spoken camera alerts. Off silences speech only; cards still show, and directions are always spoken (§12.3). */
    val voiceOn: Boolean = true,
    /** A chime before the passing alert (§11.1). */
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
    /** The daily feed check and automatic download (§6.2a); off is the switch §6.4 promises. */
    val pgwAutoSync: Boolean = true,
    /** Hold the ~220 KB camera download for Wi-Fi (§6.2 "An optional Wi-Fi only setting, off by default"). */
    val pgwWifiOnly: Boolean = false,
    /** The speed and limit pill on the car map (§8.4). */
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
    /** Epoch millis until which alerts are muted from the car's Mute action (§12.3: speech only); 0 = not muted. Not in the backup. */
    val mutedUntilMs: Long = 0,
    /** Debug builds: Navigation SDK simulator speed multiplier for bench testing. */
    val simulationSpeed: Float = 1f,
    /** See [SpeedSource] (§10.6). */
    val speedSource: SpeedSource = SpeedSource.CAR_WHEN_AVAILABLE,
    /** Which PocketGPSWorld download to use (Cameras tab). */
    val pgwDownload: io.autonav.alerts.core.pgw.PgwDownload = io.autonav.alerts.core.pgw.PgwDownload.DEFAULT,
    /** Record every fix to a CSV for bench replay (DESIGN.md §15.5). Not in the settings backup. */
    val driveRecordingOn: Boolean = false,
)

/**
 * The PocketGPSWorld sync state (DESIGN.md §6.1 step 4, §6.2), kept by `PgwFeedCheckWorker` and `PgwSyncWorker`
 * and shown on the phone's Cameras tab. Not in the settings backup (§13.4 "Not included").
 */
data class PgwState(
    /** The database version the cameras on the phone came from, e.g. "24.101", from Release.txt (§5.2); null before the first sync. */
    val installedVersion: String? = null,
    /** PGW package id the installed data came from (61 single file, 288 consolidated by type). */
    val installedPackage: Int? = null,
    /** Import layout the installed data was built with; a different value forces a re-download. */
    val installedLayout: Int? = null,
    /** The newest release the feed has shown, and its summary line (§6.2). */
    val latestVersion: String? = null,
    val latestSummary: String? = null,
    /** A release seen in the feed but not yet installed; kept across failed attempts (§6.2 "Backoff"). */
    val pendingVersion: String? = null,
    /** Epoch millis of the last feed check and the last sync that finished (the 14-day safety net reads the latter). */
    val lastFeedCheckMs: Long = 0,
    val lastSyncMs: Long = 0,
    /** One line for the Cameras tab: "Updated to …", "Already up to date", or the failure. */
    val lastResult: String? = null,
    /** The feed's validators for the next conditional GET (§6.2). */
    val feedEtag: String? = null,
    val feedLastModified: String? = null,
)

/**
 * All persisted settings (DESIGN.md §3 `settings/SettingsStore.kt`): user settings, the PGW sync state, the
 * navigation-terms record and the secrets, in one Preferences DataStore. A Hilt singleton; every call is a
 * suspend function or a Flow and DataStore does its I/O on its own thread, so callers may be on any
 * dispatcher. Secrets are stored encrypted with [SecretBox] and come back as null when missing or unreadable.
 * Upholds hard rule 5 (AGENTS.md §3.1): [mapsApiKey] is the only source of a Maps key and there is no
 * fallback; and hard rule 6: the PGW login is the user's own, entered once (§6.2a).
 */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val ds get() = context.dataStore

    /** The DataStore keys. Names are stable storage: never rename one without a migration. `_enc` keys hold [SecretBox] output. */
    private object K {
        val units = stringPreferencesKey("units")
        val voice = booleanPreferencesKey("voice")
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

    /** Live settings; emits again after every [update]. The alert coordinator keeps the latest value rather than reading per fix (§4.3). */
    val settings: Flow<AppSettings> = ds.data.map { p -> read(p) }

    /** Preferences → [AppSettings]. Every value has a default and the numeric ones are clamped, so a stray or old value can't break the app. */
    private fun read(p: Preferences): AppSettings =
        AppSettings(
            units = p[K.units]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: Units.IMPERIAL,
            voiceOn = p[K.voice] ?: true,
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

    /** Live PGW sync state for the Cameras tab. */
    val pgwState: Flow<PgwState> = ds.data.map { p ->
        PgwState(
            installedVersion = p[K.pgwInstalled], installedPackage = p[K.pgwInstalledPackage], installedLayout = p[K.pgwInstalledLayout], latestVersion = p[K.pgwLatest], latestSummary = p[K.pgwLatestSummary],
            pendingVersion = p[K.pgwPending], lastFeedCheckMs = p[K.pgwFeedCheck] ?: 0, lastSyncMs = p[K.pgwLastSync] ?: 0,
            lastResult = p[K.pgwResult], feedEtag = p[K.pgwEtag], feedLastModified = p[K.pgwLastModified],
        )
    }

    /** One-off reads of the latest values. */
    suspend fun current(): AppSettings = settings.first()
    suspend fun currentPgw(): PgwState = pgwState.first()

    /** Read-modify-write inside one DataStore transaction, so two updates at once can't lose either. */
    suspend fun update(block: (AppSettings) -> AppSettings) {
        ds.edit { p ->
            val s = block(read(p))
            p[K.units] = s.units.name
            p[K.voice] = s.voiceOn
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

    /**
     * Applies [block] to the PGW state. Unlike [update] the read happens before the edit, so two writers at
     * once could lose one change; in practice one worker runs at a time (unique work, `PgwScheduler`).
     * Null strings remove their key, so "no pending version" is an absent key, not an empty string.
     */
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

    /**
     * When the user accepted Google's navigation terms in this app, or null if never. The app's own record is
     * checked before any SDK call (§1.4 "No SDK call before the key"); it is written to the backup as
     * information only and never restored (§13.4), since the terms must be accepted on each install.
     */
    suspend fun navTermsAcceptedAt(): Long? = ds.data.first()[K.termsAcceptedAt]?.takeIf { it > 0 }

    /** Records acceptance; called by the phone once the SDK's terms dialog returns accepted. */
    suspend fun setNavTermsAccepted(atMs: Long) { ds.edit { it[K.termsAcceptedAt] = atMs } }

    // --- Secrets ---------------------------------------------------------------------------

    /** Decrypts a stored secret; null when absent or unreadable (a lost Keystore key reads as "not set", see [SecretBox.decrypt]). */
    private suspend fun getSecret(k: Preferences.Key<String>): String? = ds.data.first()[k]?.let(SecretBox::decrypt)

    /** Stores a secret encrypted, or removes it for null or empty, so a cleared field leaves no key behind. */
    private suspend fun putSecret(k: Preferences.Key<String>, v: String?) {
        ds.edit { p -> if (v.isNullOrEmpty()) p.remove(k) else p[k] = SecretBox.encrypt(v) }
    }

    /**
     * The user's own Maps key (created by the app or pasted). There's deliberately no built-in or
     * build-time fallback: every user's Maps use is billed to their own Google account (DESIGN.md §1.4).
     */
    suspend fun mapsApiKey(): String? = getSecret(K.mapsKey)?.takeIf { it.isNotBlank() }
    /** Saves (trimmed) or removes the key. The caller then tells `NavigatorHolder.keyChanged()` so the SDK re-applies it (§1.4 "Changing the key"). */
    suspend fun setMapsApiKey(key: String?) = putSecret(K.mapsKey, key?.trim())

    /** The user's own PGW username and password (§6.1 step 1), or null when either is missing. There is no built-in or default account (§6.4). */
    suspend fun pgwCredentials(): Pair<String, String>? {
        val u = getSecret(K.pgwUser) ?: return null
        val p = getSecret(K.pgwPass) ?: return null
        return u to p
    }

    /** Saves or clears the login. The session cookies are dropped with it, so the next sync logs in afresh (§13.4 "PGW login"). */
    suspend fun setPgwCredentials(user: String?, password: String?) {
        putSecret(K.pgwUser, user)
        putSecret(K.pgwPass, password)
        putSecret(K.pgwCookies, null)
    }

    /** The persisted PGW session, one cookie per line, as `PgwClient` serialises it (§6.1 step 2.1). */
    suspend fun pgwCookies(): String? = getSecret(K.pgwCookies)
    suspend fun setPgwCookies(serialized: String?) = putSecret(K.pgwCookies, serialized)
}

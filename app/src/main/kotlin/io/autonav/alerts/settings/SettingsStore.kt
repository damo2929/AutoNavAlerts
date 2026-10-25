// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.core.alerts.Units
import io.autonav.alerts.core.pgw.PgwDownload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
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
 * backup file (§13.4). The store key, backup name and clamp of each field are one row of [SETTINGS_FIELDS].
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
    val pgwDownload: PgwDownload = PgwDownload.DEFAULT,
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

    /** The DataStore keys for the terms record and the secrets; the settings and the PGW state have theirs in [SettingsCodec]. `_enc` keys hold [SecretBox] output. */
    private object K {
        val mapsKey = stringPreferencesKey("maps_api_key_enc")
        val pgwUser = stringPreferencesKey("pgw_user_enc")
        val pgwPass = stringPreferencesKey("pgw_pass_enc")
        val pgwCookies = stringPreferencesKey("pgw_cookies_enc")
        val termsAcceptedAt = longPreferencesKey("nav_terms_accepted_at")
    }

    /** Live settings; emits again after every [update]. The alert coordinator keeps the latest value rather than reading per fix (§4.3). */
    val settings: Flow<AppSettings> = ds.data.map(SettingsCodec::read)

    /** Live PGW sync state for the Cameras tab. */
    val pgwState: Flow<PgwState> = ds.data.map(SettingsCodec::readPgw)

    /** One-off reads of the latest values. */
    suspend fun current(): AppSettings = settings.first()
    suspend fun currentPgw(): PgwState = pgwState.first()

    /** Read-modify-write inside one DataStore transaction, so two updates at once can't lose either. */
    suspend fun update(block: (AppSettings) -> AppSettings) {
        ds.edit { p -> SettingsCodec.write(p, block(SettingsCodec.read(p))) }
    }

    /**
     * Applies [block] to the PGW state. Unlike [update] the read happens before the edit, so two writers at
     * once could lose one change; in practice one worker runs at a time (unique work, `PgwScheduler`).
     * Null strings remove their key, so "no pending version" is an absent key, not an empty string.
     */
    suspend fun updatePgw(block: (PgwState) -> PgwState) {
        val s = block(currentPgw())
        ds.edit { p -> SettingsCodec.writePgw(p, s) }
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
    suspend fun pgwCredentials(): Pair<String, String>? = getSecret(K.pgwUser)?.let { u -> getSecret(K.pgwPass)?.let { p -> u to p } }

    /** Saves or clears the login. The session cookies are dropped with it, so the next sync logs in afresh (§13.4 "PGW login"). */
    suspend fun setPgwCredentials(user: String?, password: String?) =
        listOf(K.pgwUser to user, K.pgwPass to password, K.pgwCookies to null).forEach { (k, v) -> putSecret(k, v) }

    /** The persisted PGW session, one cookie per line, as `PgwClient` serialises it (§6.1 step 2.1). */
    suspend fun pgwCookies(): String? = getSecret(K.pgwCookies)
    suspend fun setPgwCookies(serialized: String?) = putSecret(K.pgwCookies, serialized)
}

// --- The field table (DESIGN.md §13 item 9, §13.4) -------------------------------------------

/** The enum constant named [name], or null when there is none (an unknown name in the store or the backup file). */
internal inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

/** The enum constant named [name], or [default] when there is none. */
internal inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumOrNull<E>(name) ?: default

/**
 * One [AppSettings] field, as stored in DataStore under [key] (type [R]) and, when [json] is set, in the backup
 * file's `settings` object (§13.4). Reading from either source applies [clamp] and treats a missing or
 * unreadable value as "keep what we have" (the default when reading the store, the current setting on
 * restore; §13.4 step 4). Built by the factories below; the rows are [SETTINGS_FIELDS].
 */
internal class Field<T, R : Any>(
    private val key: Preferences.Key<R>,
    /** The name in the backup file, or null for a field that stays out of it (§13.4 "Not included"). */
    val json: String?,
    private val get: (AppSettings) -> T,
    private val set: AppSettings.(T) -> AppSettings,
    private val toStored: (T) -> R,
    private val fromStored: (R) -> T?,
    private val fromJson: (JSONObject, String) -> T?,
    private val toJson: (T) -> Any,
    private val clamp: (T) -> T,
) {
    /** The stored value onto [s], clamped; absent or unreadable leaves [s] as it is. */
    fun read(p: Preferences, s: AppSettings): AppSettings = p[key]?.let(fromStored)?.let { s.set(clamp(it)) } ?: s
    fun write(p: MutablePreferences, s: AppSettings) { p[key] = toStored(get(s)) }
    /** The file's value onto [s], clamped; a field outside the backup, or one missing or unreadable, leaves [s] as it is. */
    fun restore(j: JSONObject, s: AppSettings): AppSettings = json?.let { fromJson(j, it) }?.let { s.set(clamp(it)) } ?: s
    fun backup(j: JSONObject, s: AppSettings) { if (json != null) j.put(json, toJson(get(s))) }
}

// A field outside the backup has nothing to read from the file.
private val noJson: (JSONObject, String) -> Nothing? = { _, _ -> null }

private fun bool(key: String, json: String?, get: (AppSettings) -> Boolean, set: AppSettings.(Boolean) -> AppSettings) =
    Field(booleanPreferencesKey(key), json, get, set, { it }, { it }, { j, k -> runCatching { j.getBoolean(k) }.getOrNull() }, { it }, { it })

private fun int(key: String, json: String, range: IntRange, get: (AppSettings) -> Int, set: AppSettings.(Int) -> AppSettings) =
    Field(intPreferencesKey(key), json, get, set, { it }, { it }, { j, k -> j.optDouble(k).takeIf { !it.isNaN() }?.toInt() }, { it }, { it.coerceIn(range) })

private fun double(key: String, json: String, range: ClosedFloatingPointRange<Double>, get: (AppSettings) -> Double, set: AppSettings.(Double) -> AppSettings) =
    Field(doublePreferencesKey(key), json, get, set, { it }, { it }, { j, k -> j.optDouble(k).takeIf { !it.isNaN() } }, { it }, { it.coerceIn(range) })

// A field stored as it is and kept out of the backup (mute-until, the simulator speed).
private fun <T : Any> plain(key: Preferences.Key<T>, get: (AppSettings) -> T, set: AppSettings.(T) -> AppSettings) =
    Field(key, null, get, set, { it }, { it }, noJson, { it }, { it })

private inline fun <reified E : Enum<E>> enum(key: String, json: String, noinline get: (AppSettings) -> E, noinline set: AppSettings.(E) -> AppSettings) =
    Field(stringPreferencesKey(key), json, get, set, { it.name }, { enumOrNull<E>(it) }, { j, k -> enumOrNull<E>(j.optString(k)) }, { it.name }, { it })

/**
 * Every [AppSettings] field: its DataStore key (stable storage, never rename without a migration), its name in
 * the backup file, and its clamp. In the backup file's order (§13.4 "What's in it"), with the three fields the
 * backup leaves out last.
 */
internal val SETTINGS_FIELDS: List<Field<*, *>> = listOf(
    enum<Units>("units", "units", { it.units }, { copy(units = it) }),
    bool("voice", "voiceOn", { it.voiceOn }, { copy(voiceOn = it) }),
    bool("chime", "chimeOn", { it.chimeOn }, { copy(chimeOn = it) }),
    double("overspeed_pct", "overspeedPct", -0.05..0.20, { it.overspeedPct }, { copy(overspeedPct = it) }),
    int("alert_lead_seconds", "alertLeadSeconds", MIN_ALERT_LEAD_SECONDS..MAX_ALERT_LEAD_SECONDS, { it.alertLeadSeconds }, { copy(alertLeadSeconds = it) }),
    bool("announce_limit_changes", "announceLimitChanges", { it.announceLimitChanges }, { copy(announceLimitChanges = it) }),
    enum<SpeedSource>("speed_source", "speedSource", { it.speedSource }, { copy(speedSource = it) }),
    bool("speed_panel", "speedPanelOn", { it.speedPanelOn }, { copy(speedPanelOn = it) }),
    bool("satellite_map", "satelliteMap", { it.satelliteMap }, { copy(satelliteMap = it) }),
    bool("ev_efficient_routes", "evEfficientRoutes", { it.evEfficientRoutes }, { copy(evEfficientRoutes = it) }),
    bool("avoid_tolls", "avoidTolls", { it.avoidTolls }, { copy(avoidTolls = it) }),
    bool("osm_limits", "osmLimitsOn", { it.osmLimitsOn }, { copy(osmLimitsOn = it) }),
    bool("osm_wifi_only", "osmWifiOnly", { it.osmWifiOnly }, { copy(osmWifiOnly = it) }),
    bool("pgw_auto", "pgwAutoSync", { it.pgwAutoSync }, { copy(pgwAutoSync = it) }),
    bool("pgw_wifi_only", "pgwWifiOnly", { it.pgwWifiOnly }, { copy(pgwWifiOnly = it) }),
    enum<PgwDownload>("pgw_download", "pgwDownload", { it.pgwDownload }, { copy(pgwDownload = it) }),
    plain(longPreferencesKey("muted_until"), { it.mutedUntilMs }, { copy(mutedUntilMs = it) }),
    plain(floatPreferencesKey("sim_speed"), { it.simulationSpeed }, { copy(simulationSpeed = it) }),
    bool("drive_recording", null, { it.driveRecordingOn }, { copy(driveRecordingOn = it) }),
)

/** One [PgwState] field under [key]; a null value removes the key, and an absent key reads as null (0 for the two times, via [set]). */
private class PgwField<R : Any>(val key: Preferences.Key<R>, val get: (PgwState) -> R?, val set: PgwState.(R?) -> PgwState) {
    fun read(p: Preferences, s: PgwState): PgwState = s.set(p[key])
    fun write(p: MutablePreferences, s: PgwState) { get(s)?.let { p[key] = it } ?: p.remove(key) }
}

/** Every [PgwState] field and its DataStore key (stable storage). */
private val PGW_FIELDS = listOf(
    PgwField(stringPreferencesKey("pgw_installed"), { it.installedVersion }, { copy(installedVersion = it) }),
    PgwField(intPreferencesKey("pgw_installed_package"), { it.installedPackage }, { copy(installedPackage = it) }),
    PgwField(intPreferencesKey("pgw_installed_layout"), { it.installedLayout }, { copy(installedLayout = it) }),
    PgwField(stringPreferencesKey("pgw_latest"), { it.latestVersion }, { copy(latestVersion = it) }),
    PgwField(stringPreferencesKey("pgw_latest_summary"), { it.latestSummary }, { copy(latestSummary = it) }),
    PgwField(stringPreferencesKey("pgw_pending"), { it.pendingVersion }, { copy(pendingVersion = it) }),
    PgwField(longPreferencesKey("pgw_feed_check"), { it.lastFeedCheckMs }, { copy(lastFeedCheckMs = it ?: 0) }),
    PgwField(longPreferencesKey("pgw_last_sync"), { it.lastSyncMs }, { copy(lastSyncMs = it ?: 0) }),
    PgwField(stringPreferencesKey("pgw_result"), { it.lastResult }, { copy(lastResult = it) }),
    PgwField(stringPreferencesKey("pgw_etag"), { it.feedEtag }, { copy(feedEtag = it) }),
    PgwField(stringPreferencesKey("pgw_last_modified"), { it.feedLastModified }, { copy(feedLastModified = it) }),
)

/**
 * [AppSettings] and [PgwState] to and from DataStore `Preferences`, row by row of the tables above; shared by
 * [SettingsStore] and tested on the JVM without a DataStore (`SettingsBackupJsonTest`). Pure functions, any thread.
 */
internal object SettingsCodec {
    /** Preferences → [AppSettings]: the defaults, then every stored value that reads, clamped. */
    fun read(p: Preferences): AppSettings = SETTINGS_FIELDS.fold(AppSettings()) { s, f -> f.read(p, s) }
    /** [AppSettings] → Preferences, every field written. */
    fun write(p: MutablePreferences, s: AppSettings) = SETTINGS_FIELDS.forEach { it.write(p, s) }
    /** Preferences → [PgwState]. */
    fun readPgw(p: Preferences): PgwState = PGW_FIELDS.fold(PgwState()) { s, f -> f.read(p, s) }
    /** [PgwState] → Preferences; a null field removes its key. */
    fun writePgw(p: MutablePreferences, s: PgwState) = PGW_FIELDS.forEach { it.write(p, s) }
}

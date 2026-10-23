// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.settings

import android.content.Context
import android.net.Uri
import io.autonav.alerts.BuildConfig
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.alerts.Units
import io.autonav.alerts.core.pgw.PgwDownload
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.StageCols
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings backup file (DESIGN.md §13.4): app settings, the Maps key and PGW login (in plain
 * text, by design) and each POI set's own settings, as one JSON file the user saves where they like.
 */
@Singleton
class SettingsBackup @Inject constructor(
    private val settings: SettingsStore,
    private val pois: PoiRepository,
    private val icons: IconRepository,
) {
    suspend fun toJson(): String {
        val s = settings.current()
        val root = JSONObject()
            .put("app", "AutoNavAlerts")
            .put("format", FORMAT)
            .put("createdAt", Instant.now().toString())
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("package", BuildConfig.APPLICATION_ID)
            .put("settings", JSONObject()
                .put("units", s.units.name)
                .put("voiceOn", s.voiceOn)
                .put("voiceLanguage", s.voiceLanguage)
                .put("chimeOn", s.chimeOn)
                .put("overspeedPct", s.overspeedPct)
                .put("alertLeadSeconds", s.alertLeadSeconds)
                .put("announceLimitChanges", s.announceLimitChanges)
                .put("speedSource", s.speedSource.name)
                .put("speedPanelOn", s.speedPanelOn)
                .put("satelliteMap", s.satelliteMap)
                .put("evEfficientRoutes", s.evEfficientRoutes)
                .put("avoidTolls", s.avoidTolls)
                .put("osmLimitsOn", s.osmLimitsOn)
                .put("osmWifiOnly", s.osmWifiOnly)
                .put("pgwAutoSync", s.pgwAutoSync)
                .put("pgwWifiOnly", s.pgwWifiOnly)
                .put("pgwDownload", s.pgwDownload.name))
        val secrets = JSONObject()
        settings.mapsApiKey()?.let { secrets.put("mapsApiKey", it) }
        settings.pgwCredentials()?.let { (user, pass) -> secrets.put("pgwUsername", user).put("pgwPassword", pass) }
        root.put("secrets", secrets)
        root.put("navTermsAcceptedAt", settings.navTermsAcceptedAt()?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        // An explicit JSONArray: Android's JSONObject.put stores a Kotlin List as one string.
        root.put("poiSets", JSONArray(pois.sets().values.sortedBy { it.name }.map(::setJson)))
        return root.toString(2)
    }

    /** Writes the backup to [uri] (from the system file picker). */
    suspend fun writeTo(context: Context, uri: Uri) {
        val text = toJson()
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open the file")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun setJson(set: PoiSetEntity) = JSONObject()
        .put("name", set.name)
        .put("source", set.source.name)
        .put("enabled", set.enabled)
        .put("showOnMap", set.showOnMap)
        .put("alertsEnabled", set.alertsEnabled)
        .put("soundEnabled", set.soundEnabled)
        .put("icon", set.defaultIconId)
        .put("minZoom", set.minZoom.toDouble())
        .put("templates", JSONObject()
            .put("first", set.firstTemplate)
            .put("second", set.secondTemplate)
            .put("passing", set.passingTemplate))
        .put("firstAlert", stageJson(set.firstAlert))
        .put("secondAlert", stageJson(set.secondAlert))

    private fun stageJson(s: StageCols) = JSONObject()
        .put("enabled", s.enabled)
        .put("mode", s.mode.name)
        .put("metres", s.metres)
        .put("seconds", s.seconds ?: JSONObject.NULL)

    // --- Restore (DESIGN.md §13.4) ------------------------------------------------------------

    /** What a backup file holds, shown in the confirm dialog before anything changes. */
    data class Preview(
        val text: String,
        val createdAt: String?,
        val appVersion: String?,
        val hasMapsKey: Boolean,
        val pgwUser: String?,
        val sets: Int,
        val matchingSets: Int,
    )

    data class Restored(val settings: Boolean, val mapsKey: Boolean, val pgwLogin: Boolean, val sets: Int, val setsNotFound: Int)

    class BadBackup(message: String) : Exception(message)

    fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Couldn't open the file")

    /** Checks the file and says what's in it. Throws [BadBackup] if it isn't one of ours. */
    suspend fun preview(text: String): Preview {
        val root = parse(text)
        val secrets = root.optJSONObject("secrets")
        val names = setNames(root)
        val here = pois.sets().values.map { it.name }.toSet()
        return Preview(
            text = text,
            createdAt = root.optString("createdAt").takeIf { it.isNotEmpty() },
            appVersion = root.optString("appVersion").takeIf { it.isNotEmpty() },
            hasMapsKey = !secrets?.optString("mapsApiKey").isNullOrBlank(),
            pgwUser = secrets?.optString("pgwUsername")?.takeIf { it.isNotBlank() && !secrets.optString("pgwPassword").isNullOrEmpty() },
            sets = names.size,
            matchingSets = names.count { it in here },
        )
    }

    /** Applies the file. The caller tells the Navigation SDK if [Restored.mapsKey] is true. */
    suspend fun restore(text: String): Restored {
        val root = parse(text)
        val restoredSettings = root.optJSONObject("settings")?.let { j -> settings.update { applySettings(it, j) }; true } ?: false

        val secrets = root.optJSONObject("secrets")
        val key = secrets?.optString("mapsApiKey")?.takeIf { it.isNotBlank() }
        key?.let { settings.setMapsApiKey(it) }
        val user = secrets?.optString("pgwUsername")?.takeIf { it.isNotBlank() }
        val pass = secrets?.optString("pgwPassword")?.takeIf { it.isNotEmpty() }
        if (user != null && pass != null) settings.setPgwCredentials(user, pass)

        val byName = pois.sets().values.associateBy { it.name }
        val iconIds = (icons.builtinIds() + icons.userIds()).toSet()
        val updated = ArrayList<PoiSetEntity>()
        var notFound = 0
        root.optJSONArray("poiSets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                val set = byName[j.optString("name")]
                if (set == null) { notFound++; continue }
                updated += applySet(set, j, iconIds)
            }
        }
        if (updated.isNotEmpty()) pois.updateSets(updated)
        return Restored(restoredSettings, key != null, user != null && pass != null, updated.size, notFound)
    }

    private fun parse(text: String): JSONObject {
        val root = runCatching { JSONObject(text) }.getOrElse { throw BadBackup("This isn't an AutoNavAlerts settings backup (not JSON)") }
        if (root.optString("app") != "AutoNavAlerts") throw BadBackup("This isn't an AutoNavAlerts settings backup")
        val format = root.optInt("format", -1)
        if (format > FORMAT) throw BadBackup("This backup was made by a newer version of the app")
        if (format < 1) throw BadBackup("This backup's format isn't recognised")
        return root
    }

    private fun setNames(root: JSONObject): List<String> {
        val arr = root.optJSONArray("poiSets") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf(String::isNotEmpty) }
    }

    /** Each value replaces the current one; a missing or unreadable value keeps it. */
    private fun applySettings(cur: AppSettings, j: JSONObject): AppSettings {
        fun bool(k: String, d: Boolean) = if (j.has(k) && !j.isNull(k)) j.optBoolean(k, d) else d
        fun num(o: JSONObject, k: String) = o.optDouble(k).takeIf { !it.isNaN() }
        return cur.copy(
            units = j.optString("units").let { n -> Units.entries.firstOrNull { it.name == n } } ?: cur.units,
            voiceOn = bool("voiceOn", cur.voiceOn),
            voiceLanguage = if (j.has("voiceLanguage")) VoiceLanguages.valid(j.optString("voiceLanguage")) else cur.voiceLanguage,
            chimeOn = bool("chimeOn", cur.chimeOn),
            overspeedPct = (num(j, "overspeedPct") ?: cur.overspeedPct).coerceIn(-0.05, 0.20),
            alertLeadSeconds = (num(j, "alertLeadSeconds")?.toInt() ?: cur.alertLeadSeconds).coerceIn(MIN_ALERT_LEAD_SECONDS, MAX_ALERT_LEAD_SECONDS),
            announceLimitChanges = bool("announceLimitChanges", cur.announceLimitChanges),
            speedSource = j.optString("speedSource").let { n -> SpeedSource.entries.firstOrNull { it.name == n } } ?: cur.speedSource,
            speedPanelOn = bool("speedPanelOn", cur.speedPanelOn),
            satelliteMap = bool("satelliteMap", cur.satelliteMap),
            osmLimitsOn = bool("osmLimitsOn", cur.osmLimitsOn),
            evEfficientRoutes = bool("evEfficientRoutes", cur.evEfficientRoutes),
            avoidTolls = bool("avoidTolls", cur.avoidTolls),
            osmWifiOnly = bool("osmWifiOnly", cur.osmWifiOnly),
            pgwAutoSync = bool("pgwAutoSync", cur.pgwAutoSync),
            pgwWifiOnly = bool("pgwWifiOnly", cur.pgwWifiOnly),
            pgwDownload = j.optString("pgwDownload").let { n -> PgwDownload.entries.firstOrNull { it.name == n } } ?: cur.pgwDownload,
        )
    }

    private fun applySet(set: PoiSetEntity, j: JSONObject, iconIds: Set<String>): PoiSetEntity {
        fun bool(k: String, d: Boolean) = if (j.has(k) && !j.isNull(k)) j.optBoolean(k, d) else d
        val t = j.optJSONObject("templates")
        fun tpl(k: String, d: String) = t?.optString(k)?.takeIf { it.isNotEmpty() } ?: d
        val first = j.optJSONObject("firstAlert")?.let { stage(it, set.firstAlert) } ?: set.firstAlert
        val second = j.optJSONObject("secondAlert")?.let { stage(it, set.secondAlert) } ?: set.secondAlert
        // The same checks as the set editor (§11.1): stages that fail them keep this phone's values.
        val stagesOk = io.autonav.alerts.data.StageRules.problem(first, second) == null
        return set.copy(
            enabled = bool("enabled", set.enabled),
            showOnMap = bool("showOnMap", set.showOnMap),
            alertsEnabled = bool("alertsEnabled", set.alertsEnabled),
            soundEnabled = bool("soundEnabled", set.soundEnabled),
            defaultIconId = j.optString("icon").takeIf { it in iconIds } ?: set.defaultIconId,
            minZoom = j.optDouble("minZoom").takeIf { !it.isNaN() && it in 0.0..21.0 }?.toFloat() ?: set.minZoom,
            firstTemplate = tpl("first", set.firstTemplate),
            secondTemplate = tpl("second", set.secondTemplate),
            passingTemplate = tpl("passing", set.passingTemplate),
            firstAlert = if (stagesOk) first else set.firstAlert,
            secondAlert = if (stagesOk) second else set.secondAlert,
        )
    }

    private fun stage(j: JSONObject, cur: StageCols) = StageCols(
        enabled = if (j.has("enabled")) j.optBoolean("enabled", cur.enabled) else cur.enabled,
        mode = j.optString("mode").let { n -> DistanceMode.entries.firstOrNull { it.name == n } } ?: cur.mode,
        metres = j.optInt("metres", cur.metres).takeIf { it > 0 } ?: cur.metres,
        seconds = if (j.has("seconds")) (if (j.isNull("seconds")) null else j.optInt("seconds").takeIf { it > 0 }) else cur.seconds,
    )

    companion object {
        const val FORMAT = 1
        fun fileName(): String = "autonavalerts-settings-${LocalDate.now()}.json"
    }
}

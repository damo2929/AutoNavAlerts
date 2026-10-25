// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.settings

import android.content.Context
import android.net.Uri
import io.autonav.alerts.BuildConfig
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
 * The settings backup file (DESIGN.md §13.4): app settings, the Maps key and PGW login (in plain text, by
 * design: the user asked for a readable file, and the card warns them) and each POI set's own settings, as
 * one JSON file the user saves where they like through Android's file picker. A Hilt singleton; called by the
 * phone's Backup card (`MainActivity` and `PhoneScreens`) from a coroutine, with the file I/O on the caller's
 * dispatcher. The app never uploads the file and keeps no copy.
 * Hard rule 6 (AGENTS.md §3.1): no camera data goes in. Sets are keyed by name and carry only their switches,
 * icon, templates and stages; the PocketGPSWorld data itself is never copied out of the app (§13.4 "Not included").
 */
@Singleton
class SettingsBackup @Inject constructor(
    private val settings: SettingsStore,
    private val pois: PoiRepository,
    private val icons: IconRepository,
) {
    /** The whole backup as pretty-printed JSON, format [FORMAT] (the layout in §13.4 "What's in it"). */
    suspend fun toJson(): String {
        val s = settings.current()
        val root = JSONObject()
            .put("app", "AutoNavAlerts")
            .put("format", FORMAT)
            .put("createdAt", Instant.now().toString())
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("package", BuildConfig.APPLICATION_ID)
            .put("settings", settingsJson(s))
        // Secrets are decrypted only here, to write this file (§13.4 "Plain text, with a warning"); unset ones are left out.
        val secrets = JSONObject()
        settings.mapsApiKey()?.let { secrets.put("mapsApiKey", it) }
        settings.pgwCredentials()?.let { (user, pass) -> secrets.put("pgwUsername", user).put("pgwPassword", pass) }
        root.put("secrets", secrets)
        root.put("navTermsAcceptedAt", settings.navTermsAcceptedAt()?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        // An explicit JSONArray: Android's JSONObject.put stores a Kotlin List as one string.
        root.put("poiSets", JSONArray(pois.sets().values.sortedBy { it.name }.map(::setJson)))
        return root.toString(2)
    }

    /** Writes the backup to [uri] (from `CreateDocument`, §13.4). "wt" truncates, so overwriting an old backup leaves no tail. */
    suspend fun writeTo(context: Context, uri: Uri) {
        val text = toJson()
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open the file")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

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

    /** What [restore] applied, for the summary line (§13.4 step 5): which parts were in the file, how many sets matched and how many didn't. */
    data class Restored(val settings: Boolean, val mapsKey: Boolean, val pgwLogin: Boolean, val sets: Int, val setsNotFound: Int)

    /** The file isn't an AutoNavAlerts backup this version can read; the message is shown as it is (§13.4 step 2). */
    class BadBackup(message: String) : Exception(message)

    /** Reads the picked file whole (it is a few KB of JSON). */
    fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Couldn't open the file")

    /** Checks the file and says what's in it for the confirm dialog (§13.4 step 3). Throws [BadBackup] if it isn't one of ours. Changes nothing. */
    suspend fun preview(text: String): Preview {
        val root = parse(text)
        val secrets = Secrets(root)
        val names = setNames(root)
        val here = pois.sets().values.map { it.name }.toSet()
        return Preview(
            text = text,
            createdAt = root.optString("createdAt").takeIf { it.isNotEmpty() },
            appVersion = root.optString("appVersion").takeIf { it.isNotEmpty() },
            hasMapsKey = secrets.mapsKey != null,
            pgwUser = secrets.pgwUser?.takeIf { secrets.pgwPass != null },
            sets = names.size,
            matchingSets = names.count { it in here },
        )
    }

    /**
     * Applies the file (§13.4 step 4). Settings replace the current ones value by value; a Maps key or PGW login
     * in the file is saved, one missing from the file never removes the current one; set settings are matched
     * to this phone's active sets by name, and unmatched ones are skipped (the user downloads the cameras and
     * restores again). Navigation terms, sync state, mute and all data are untouched. Safe to run twice.
     * The caller tells the Navigation SDK (`NavigatorHolder.keyChanged`) if [Restored.mapsKey] is true.
     */
    suspend fun restore(text: String): Restored {
        val root = parse(text)
        val restoredSettings = root.optJSONObject("settings")?.let { j -> settings.update { applySettings(it, j) }; true } ?: false

        val secrets = Secrets(root)
        val key = secrets.mapsKey
        key?.let { settings.setMapsApiKey(it) }
        val user = secrets.pgwUser
        val pass = secrets.pgwPass
        // Saving the login also clears the PGW session cookies, so the next download logs in afresh (§13.4).
        if (user != null && pass != null) settings.setPgwCredentials(user, pass)

        val byName = pois.sets().values.associateBy { it.name }
        // An icon from the file is taken only if it exists on this phone; a PGW icon arrives with the camera download (§13.4).
        val iconIds = (icons.builtinIds() + icons.userIds()).toSet()
        val (found, notFound) = root.optJSONArray("poiSets")?.objects().orEmpty().partition { byName.containsKey(it.optString("name")) }
        val updated = found.map { applySet(byName.getValue(it.optString("name")), it, iconIds) }
        if (updated.isNotEmpty()) pois.updateSets(updated)
        return Restored(restoredSettings, key != null, user != null && pass != null, updated.size, notFound.size)
    }

    /** The three checks of §13.4 step 2: JSON, `"app": "AutoNavAlerts"`, and a `format` this version knows (1). */
    private fun parse(text: String): JSONObject {
        val root = runCatching { JSONObject(text) }.getOrElse { throw BadBackup("This isn't an AutoNavAlerts settings backup (not JSON)") }
        if (root.optString("app") != "AutoNavAlerts") throw BadBackup("This isn't an AutoNavAlerts settings backup")
        val format = root.optInt("format", -1)
        if (format > FORMAT) throw BadBackup("This backup was made by a newer version of the app")
        if (format < 1) throw BadBackup("This backup's format isn't recognised")
        return root
    }

    /** The file's `secrets` (§13.4): each null when absent or blank, so a missing one never removes the current value. */
    private class Secrets(root: JSONObject) {
        private val j = root.optJSONObject("secrets")
        val mapsKey = j?.optString("mapsApiKey")?.takeIf { it.isNotBlank() }
        val pgwUser = j?.optString("pgwUsername")?.takeIf { it.isNotBlank() }
        val pgwPass = j?.optString("pgwPassword")?.takeIf { it.isNotEmpty() }
    }

    /** The set names in the file, for the preview's match count. */
    private fun setNames(root: JSONObject): List<String> =
        root.optJSONArray("poiSets")?.objects().orEmpty().mapNotNull { it.optString("name").takeIf(String::isNotEmpty) }

    /** The objects of a JSON array, in order; anything else in it is skipped. */
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        /** The file format this version writes and the highest it reads (§13.4 "format 1"). Bump when a field changes meaning. */
        const val FORMAT = 1
        /** The name offered in the file picker: `autonavalerts-settings-YYYY-MM-DD.json` (§13.4). */
        fun fileName(): String = "autonavalerts-settings-${LocalDate.now()}.json"

        /** The `settings` object of the file (§13.4): every backed-up row of [SETTINGS_FIELDS] by its JSON name, in table order. */
        internal fun settingsJson(s: AppSettings): JSONObject = JSONObject().also { j -> SETTINGS_FIELDS.forEach { it.backup(j, s) } }

        /** One active set's own settings, keyed by name because set ids change on every download (§13.4 `poiSets`). No POIs. */
        internal fun setJson(set: PoiSetEntity) = JSONObject()
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

        /** One alert stage as stored (§11.1); `seconds` is written as JSON null when unset. */
        private fun stageJson(s: StageCols) = JSONObject()
            .put("enabled", s.enabled)
            .put("mode", s.mode.name)
            .put("metres", s.metres)
            .put("seconds", s.seconds ?: JSONObject.NULL)

        /**
         * Each value replaces the current one; a missing or unreadable value (unknown enum name, non-number) keeps
         * it, so a hand-edited or older file still restores what it can; numbers are clamped as `SettingsStore` does
         * (§13.4 step 4). Row by row of [SETTINGS_FIELDS]; the fields outside the backup are left alone.
         */
        internal fun applySettings(cur: AppSettings, j: JSONObject): AppSettings = SETTINGS_FIELDS.fold(cur) { s, f -> f.restore(j, s) }

        /** A matched set takes the file's switches, templates, stages, zoom and (if it exists here) icon (§13.4 step 4 "Camera set settings"). */
        internal fun applySet(set: PoiSetEntity, j: JSONObject, iconIds: Set<String>): PoiSetEntity {
            val t = j.optJSONObject("templates")
            fun tpl(k: String, d: String) = t?.optString(k)?.takeIf { it.isNotEmpty() } ?: d
            val first = j.optJSONObject("firstAlert")?.let { stage(it, set.firstAlert) } ?: set.firstAlert
            val second = j.optJSONObject("secondAlert")?.let { stage(it, set.secondAlert) } ?: set.secondAlert
            // The same checks as the set editor (§11.1): stages that fail them keep this phone's values.
            val stagesOk = io.autonav.alerts.data.StageRules.problem(first, second) == null
            return set.copy(
                enabled = j.optBoolean("enabled", set.enabled),
                showOnMap = j.optBoolean("showOnMap", set.showOnMap),
                alertsEnabled = j.optBoolean("alertsEnabled", set.alertsEnabled),
                soundEnabled = j.optBoolean("soundEnabled", set.soundEnabled),
                defaultIconId = j.optString("icon").takeIf { it in iconIds } ?: set.defaultIconId,
                minZoom = j.optDouble("minZoom").takeIf { !it.isNaN() && it in 0.0..21.0 }?.toFloat() ?: set.minZoom,
                firstTemplate = tpl("first", set.firstTemplate),
                secondTemplate = tpl("second", set.secondTemplate),
                passingTemplate = tpl("passing", set.passingTemplate),
                firstAlert = if (stagesOk) first else set.firstAlert,
                secondAlert = if (stagesOk) second else set.secondAlert,
            )
        }

        /** One stage from the file, each field falling back to the phone's; a JSON null `seconds` means none. */
        private fun stage(j: JSONObject, cur: StageCols) = StageCols(
            enabled = if (j.has("enabled")) j.optBoolean("enabled", cur.enabled) else cur.enabled,
            mode = enumOr(j.optString("mode"), cur.mode),
            metres = j.optInt("metres", cur.metres).takeIf { it > 0 } ?: cur.metres,
            seconds = if (j.has("seconds")) (if (j.isNull("seconds")) null else j.optInt("seconds").takeIf { it > 0 }) else cur.seconds,
        )
    }
}

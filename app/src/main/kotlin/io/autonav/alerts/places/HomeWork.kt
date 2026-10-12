// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.places

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.core.poi.importers.ZipEntries
import io.autonav.alerts.data.AppDatabase
import io.autonav.alerts.data.PlaceEntity
import io.autonav.alerts.data.PlaceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Home and Work from the user's Google account (DESIGN.md §8.2a): the Data Portability API's
 * labeled places, stored as the HOME and WORK places the car offers.
 */
@Singleton
class HomeWork @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val places: PlacesSearch,
) {
    class Problem(message: String) : Exception(message)
    /** The grant was already used for an export; it has been reset and the user must allow access again. */
    class NeedsConsent : Exception("Google needs you to allow access again")

    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun current(): Pair<PlaceEntity?, PlaceEntity?> =
        db.places().byKind(PlaceKind.HOME) to db.places().byKind(PlaceKind.WORK)

    suspend fun clear() { db.places().deleteKind(PlaceKind.HOME); db.places().deleteKind(PlaceKind.WORK) }

    /** Fetches labeled places with a Data Portability access token, then saves Home and Work. */
    suspend fun fromGoogle(accessToken: String, progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        progress("Asking Google for your labeled places…")
        val job = initiate(accessToken)
        var urls: JSONArray? = null
        var tries = 0
        while (urls == null) {
            val state = call(accessToken, Request.Builder().url("$API/archiveJobs/$job/portabilityArchiveState").get())
            when (state.optString("state")) {
                "COMPLETE" -> urls = state.optJSONArray("urls") ?: JSONArray()
                "FAILED" -> throw Problem("Google's export failed")
                "CANCELLED" -> throw Problem("Google's export was cancelled")
                else -> {
                    if (++tries >= 60) throw Problem("Google is taking too long; try again in a minute")
                    progress("Google is preparing your places…")
                    delay(3_000)
                }
            }
        }
        val list = urls!!
        progress("Reading your places…")
        val found = ArrayList<LabeledPlaces.Labeled>()
        for (i in 0 until list.length()) {
            val tmp = File.createTempFile("labeled", ".dat", context.cacheDir)
            try {
                http.newCall(Request.Builder().url(list.getString(i)).build()).execute().use { r ->
                    if (!r.isSuccessful) throw Problem("Couldn't download Google's export (HTTP ${r.code})")
                    tmp.outputStream().use { r.body.byteStream().copyTo(it) }
                }
                // Debug builds keep Google's file (app-private, `run-as`) to check its layout on the bench.
                if (io.autonav.alerts.BuildConfig.DEBUG) tmp.copyTo(File(context.filesDir, "debug/labeled-$i.dat").apply { parentFile?.mkdirs() }, true)
                found += parseFile(tmp)
            } finally { tmp.delete() }
        }
        save(found)
    }

    /** Google's export: a zip (any JSON inside it, read one at a time) or a JSON file. */
    private fun parseFile(f: File): List<LabeledPlaces.Labeled> {
        val zip = f.inputStream().use { val h = ByteArray(2); it.read(h) == 2 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() }
        if (!zip) return LabeledPlaces.parse(f.readText())
        return ZipEntries.read(f).use { e ->
            e.keys.filter { it.endsWith(".json", ignoreCase = true) }.flatMap { LabeledPlaces.parse(String(e.getValue(it), Charsets.UTF_8)) }
        }
    }

    /** Saves the Home and Work found (looking up an address with no position), replacing the old ones. */
    private suspend fun save(found: List<LabeledPlaces.Labeled>): String {
        val saved = ArrayList<String>()
        for ((label, kind, title) in listOf(Triple(LabeledPlaces.Label.HOME, PlaceKind.HOME, "Home"), Triple(LabeledPlaces.Label.WORK, PlaceKind.WORK, "Work"))) {
            val p = found.firstOrNull { it.label == label } ?: continue
            var lat = p.lat; var lon = p.lon; var placeId: String? = null; var address = p.address
            if (lat == null || lon == null) {
                val hit = p.address?.let { places.textSearch(it, null).firstOrNull() }
                    ?: run { Log.w(TAG, "$title has no position and its address couldn't be found"); continue }
                lat = hit.lat; lon = hit.lon; placeId = hit.placeId; address = address ?: hit.subtitle
            }
            if (lat == null || lon == null) continue
            db.places().deleteKind(kind)
            db.places().insert(PlaceEntity(placeId = placeId, name = title, address = address, lat = lat, lon = lon,
                kind = kind, lastUsedAt = System.currentTimeMillis()))
            saved += title
        }
        Log.i(TAG, "Labeled places: ${found.size} found; saved ${saved.ifEmpty { listOf("none") }}")
        return when {
            saved.isEmpty() -> "No Home or Work found. Set them in Google Maps (You → Saved → Labeled), then try again."
            else -> "${saved.joinToString(" and ")} updated from your Google account."
        }
    }

    /**
     * Starts the export. One grant gives one export: on 409 ("Requested resources have already been
     * exported in job <id>") the grant is reset so the user can allow access again and get a new
     * export with current data (DESIGN.md §8.2a).
     */
    private fun initiate(token: String): String {
        val b = Request.Builder().url("$API/portabilityArchive:initiate")
            .post(JSONObject().put("resources", JSONArray().put(RESOURCE)).toString().toRequestBody(JSON))
            .header("Authorization", "Bearer $token")
        http.newCall(b.build()).execute().use { r ->
            val text = r.body.string()
            if (r.code == 409) {
                Log.i(TAG, "Grant already used; resetting it")
                http.newCall(Request.Builder().url("$API/authorization:reset").post("{}".toRequestBody(JSON))
                    .header("Authorization", "Bearer $token").build()).execute().use { rr ->
                    if (!rr.isSuccessful) failed(rr.code, rr.body.string())
                }
                throw NeedsConsent()
            }
            if (!r.isSuccessful) failed(r.code, text)
            return JSONObject(text).optString("archiveJobId").ifBlank { throw Problem("Google didn't start the export") }
        }
    }

    private fun failed(code: Int, text: String): Nothing {
        val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
        Log.w(TAG, "Data Portability $code: $msg")
        throw Problem(when (code) {
            403 -> "Google refused: $msg (this needs a UK/EEA account that is allowed to use the app's Google sign-in)"
            else -> "Google error $code: $msg"
        })
    }

    private fun call(token: String, b: Request.Builder): JSONObject =
        http.newCall(b.header("Authorization", "Bearer $token").build()).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) failed(r.code, text)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }

    companion object {
        private const val TAG = "HomeWork"
        private const val API = "https://dataportability.googleapis.com/v1"
        private const val RESOURCE = "maps.aliased_places"
        const val SCOPE = "https://www.googleapis.com/auth/dataportability.maps.aliased_places"
        private val JSON = "application/json".toMediaType()
    }
}

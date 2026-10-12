// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the user's own Maps API key in their own Google Cloud account (DESIGN.md §13.1):
 * project → billing → APIs → restricted key. Every step is safe to repeat, so "Try again"
 * after an error carries on from where it stopped. The access token is only held for one run.
 */
@Singleton
class MapsKeyCreator @Inject constructor(@ApplicationContext private val context: Context) {

    /** A step that needs the user before it can go on. */
    sealed class Stop(message: String) : Exception(message) {
        /** No open billing account: one has to be added in the Console first. */
        class NoBilling : Stop("Google Maps needs a billing account. Add one in Google Cloud, then press Try again.")
        /** Several billing accounts: the user picks one, then the run is repeated with it. */
        class ChooseBilling(val accounts: List<BillingAccount>) : Stop("Choose the billing account to use.")
        /**
         * The app's Google sign-in isn't registered (no Android OAuth client for this package and
         * signing SHA-1). Retrying can't help until the maintainer adds one, so no Try again is offered.
         */
        class NotSetUp : Stop(
            "Automatic set-up isn't available for this copy of the app, because it isn't registered for Google sign-in. " +
                "Follow the steps below instead: they do the same thing in your browser.")
        /** Google refused a call; [consoleUrl] opens the page where it can be fixed, if known. */
        class Refused(message: String, val consoleUrl: String?) : Stop(message)
    }

    data class BillingAccount(val name: String, val displayName: String)
    data class Created(val key: String, val projectId: String)

    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    /** The SHA-1 of the certificate this installed APK is signed with, as "AB:CD:…". */
    fun signingSha1(): String {
        val pm = context.packageManager
        val cert = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: error("No signing information for this app")
            (if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory).first()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!!.first()
        }
        return MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
    }

    /**
     * Runs every step with [token] (an OAuth access token for the cloud-platform scope), reporting
     * each to [progress]. [billingAccount] is the user's choice after a [Stop.ChooseBilling].
     */
    suspend fun create(token: String, billingAccount: String? = null, progress: (String) -> Unit): Created = withContext(Dispatchers.IO) {
        val api = Api(token)

        progress("Finding or creating your AutoNavAlerts project…")
        val project = findProject(api) ?: createProject(api)
        val projectId = project.getString("projectId")
        val projectNumber = project.getString("name").removePrefix("projects/")

        progress("Checking billing…")
        ensureBilling(api, projectId, billingAccount)

        progress("Turning on the Navigation SDK, Maps SDK for Android, Places API (New) and Routes API…")
        val services = listOf(navigationService(api, projectNumber), MAPS_ANDROID, PLACES, ROUTES)
        enableServices(api, projectNumber, projectId, services)

        progress("Creating a key only this app can use…")
        val keyName = findKey(api, projectNumber)?.also { allowServices(api, it, services) } ?: createKey(api, projectNumber, services)
        val key = api.get("https://apikeys.googleapis.com/v2/$keyName/keyString").getString("keyString")
        Created(key, projectId)
    }

    // --- Project ---------------------------------------------------------------------------------

    private fun findProject(api: Api): JSONObject? {
        val r = api.get("$CRM/projects:search?query=" + java.net.URLEncoder.encode("labels.$LABEL_KEY:$LABEL_VALUE", "UTF-8"))
        val list = r.optJSONArray("projects") ?: return null
        return (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optString("state") == "ACTIVE" }
    }

    private suspend fun createProject(api: Api): JSONObject {
        val id = "autonavalerts-" + (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val body = JSONObject()
            .put("projectId", id)
            .put("displayName", "AutoNavAlerts")
            .put("labels", JSONObject().put(LABEL_KEY, LABEL_VALUE))
        val op = api.post("$CRM/projects", body)
        api.waitFor("$CRM/${op.getString("name")}")
        return api.get("$CRM/projects/$id")
    }

    // --- Billing ---------------------------------------------------------------------------------

    private fun ensureBilling(api: Api, projectId: String, chosen: String?) {
        val info = api.get("$BILLING/projects/$projectId/billingInfo")
        if (info.optBoolean("billingEnabled")) return
        val account = chosen ?: run {
            val list = api.get("$BILLING/billingAccounts").optJSONArray("billingAccounts") ?: JSONArray()
            val open = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optBoolean("open") }
                .map { BillingAccount(it.getString("name"), it.optString("displayName", it.getString("name"))) }
            when (open.size) {
                0 -> throw Stop.NoBilling()
                1 -> open.single().name
                else -> throw Stop.ChooseBilling(open)
            }
        }
        api.put("$BILLING/projects/$projectId/billingInfo", JSONObject().put("billingAccountName", account))
    }

    // --- APIs ------------------------------------------------------------------------------------

    /** The Navigation SDK is `navigationsdk.googleapis.com` (checked 2026-10-12); if that ever changes, search by title. */
    private fun navigationService(api: Api, projectNumber: String): String {
        runCatching { api.get("$SERVICE_USAGE/projects/$projectNumber/services/$NAVIGATION_GUESS") }
            .onSuccess { return NAVIGATION_GUESS }
        var page: String? = null
        do {
            val r = api.get("$SERVICE_USAGE/projects/$projectNumber/services?pageSize=200" + (page?.let { "&pageToken=$it" } ?: ""))
            val list = r.optJSONArray("services") ?: JSONArray()
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i)
                if (s.optJSONObject("config")?.optString("title").equals("Navigation SDK", ignoreCase = true)) {
                    return s.getJSONObject("config").getString("name")
                }
            }
            page = r.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        throw Stop.Refused("The Navigation SDK isn't available to this Google account yet. Open it in Google Cloud to request or enable it.",
            "https://console.cloud.google.com/apis/library?q=Navigation%20SDK")
    }

    private suspend fun enableServices(api: Api, projectNumber: String, projectId: String, services: List<String>) {
        try {
            val op = api.post("$SERVICE_USAGE/projects/$projectNumber/services:batchEnable",
                JSONObject().put("serviceIds", JSONArray(services)))
            if (!op.optBoolean("done")) api.waitFor("$SERVICE_USAGE/${op.getString("name")}")
        } catch (e: Stop.Refused) {
            // Usually terms to accept or access to request: send the user to the project's API library.
            throw Stop.Refused(e.message.orEmpty(), "https://console.cloud.google.com/apis/library?project=$projectId")
        }
    }

    // --- Key -------------------------------------------------------------------------------------

    private val keyDisplayName get() = "AutoNavAlerts · ${context.packageName} · ${signingSha1().take(11)}"

    private fun findKey(api: Api, projectNumber: String): String? {
        val list = api.get("$API_KEYS/projects/$projectNumber/locations/global/keys").optJSONArray("keys") ?: return null
        return (0 until list.length()).map { list.getJSONObject(it) }
            .firstOrNull { it.optString("displayName") == keyDisplayName && !it.has("deleteTime") }?.getString("name")
    }

    private suspend fun createKey(api: Api, projectNumber: String, services: List<String>): String {
        val app = JSONObject().put("packageName", context.packageName).put("sha1Fingerprint", signingSha1())
        val restrictions = JSONObject()
            .put("androidKeyRestrictions", JSONObject().put("allowedApplications", JSONArray().put(app)))
            .put("apiTargets", JSONArray(services.map { JSONObject().put("service", it) }))
        val body = JSONObject().put("displayName", keyDisplayName).put("restrictions", restrictions)
        val op = api.post("$API_KEYS/projects/$projectNumber/locations/global/keys", body)
        val done = if (op.optBoolean("done")) op else api.waitFor("$API_KEYS/${op.getString("name")}")
        return done.getJSONObject("response").getString("name")
    }

    /** An existing key gets any API it lacks (e.g. the Routes API added in 2026-10). */
    private suspend fun allowServices(api: Api, keyName: String, services: List<String>) {
        val key = api.get("$API_KEYS/$keyName")
        val restrictions = key.optJSONObject("restrictions") ?: JSONObject()
        val targets = restrictions.optJSONArray("apiTargets") ?: JSONArray()
        val have = (0 until targets.length()).map { targets.getJSONObject(it).optString("service") }.toSet()
        val missing = services.filter { it !in have }
        if (missing.isEmpty()) return
        missing.forEach { targets.put(JSONObject().put("service", it)) }
        restrictions.put("apiTargets", targets)
        val op = api.patch("$API_KEYS/$keyName?updateMask=restrictions", JSONObject().put("restrictions", restrictions))
        if (!op.optBoolean("done")) api.waitFor("$API_KEYS/${op.getString("name")}")
    }

    // --- HTTP ------------------------------------------------------------------------------------

    private inner class Api(private val token: String) {
        fun get(url: String) = call(Request.Builder().url(url).get())
        fun post(url: String, body: JSONObject) = call(Request.Builder().url(url).post(body.toString().toRequestBody(json)))
        fun put(url: String, body: JSONObject) = call(Request.Builder().url(url).put(body.toString().toRequestBody(json)))
        fun patch(url: String, body: JSONObject) = call(Request.Builder().url(url).patch(body.toString().toRequestBody(json)))

        /** Polls a long-running operation until it's done (up to about 2 minutes). */
        suspend fun waitFor(opUrl: String): JSONObject {
            repeat(60) {
                val op = get(opUrl)
                if (op.optBoolean("done")) {
                    op.optJSONObject("error")?.let { throw Stop.Refused(it.optString("message", "Google Cloud reported an error"), null) }
                    return op
                }
                delay(2_000)
            }
            throw Stop.Refused("Google Cloud is taking too long. Press Try again in a minute.", null)
        }

        private fun call(b: Request.Builder): JSONObject {
            http.newCall(b.header("Authorization", "Bearer $token").build()).execute().use { r ->
                val text = r.body.string()
                if (r.isSuccessful) return if (text.isBlank()) JSONObject() else JSONObject(text)
                val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                val message = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP ${r.code}"
                throw Stop.Refused(message, helpUrl(err))
            }
        }

        /** Google's error details often carry a link to the page that fixes the problem. */
        private fun helpUrl(err: JSONObject?): String? {
            val details = err?.optJSONArray("details") ?: return null
            for (i in 0 until details.length()) {
                val links = details.getJSONObject(i).optJSONArray("links") ?: continue
                if (links.length() > 0) return links.getJSONObject(0).optString("url").ifBlank { null }
            }
            return null
        }
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        const val BILLING_CREATE_URL = "https://console.cloud.google.com/billing/create"
        const val CREDENTIALS_URL = "https://console.cloud.google.com/apis/credentials"

        /** Console pages for the guided set-up (DESIGN.md §13.2); each applies to the project last opened. */
        const val CONSOLE_PROJECT_CREATE = "https://console.cloud.google.com/projectcreate"
        const val CONSOLE_BILLING = "https://console.cloud.google.com/billing/linkedaccount"
        const val CONSOLE_NAVIGATION = "https://console.cloud.google.com/apis/library/navigationsdk.googleapis.com"
        const val CONSOLE_MAPS_ANDROID = "https://console.cloud.google.com/apis/library/maps-android-backend.googleapis.com"
        const val CONSOLE_PLACES = "https://console.cloud.google.com/apis/library/places.googleapis.com"
        const val CONSOLE_ROUTES = "https://console.cloud.google.com/apis/library/routes.googleapis.com"

        /** A Google API key: "AIza" then 35 letters, digits, '-' or '_'. */
        fun looksLikeKey(text: String) = Regex("AIza[0-9A-Za-z_-]{35}").matches(text.trim())
        private const val CRM = "https://cloudresourcemanager.googleapis.com/v3"
        private const val BILLING = "https://cloudbilling.googleapis.com/v1"
        private const val SERVICE_USAGE = "https://serviceusage.googleapis.com/v1"
        private const val API_KEYS = "https://apikeys.googleapis.com/v2"
        private const val LABEL_KEY = "autonavalerts"
        private const val LABEL_VALUE = "maps-key"
        private const val NAVIGATION_GUESS = "navigationsdk.googleapis.com"
        private const val MAPS_ANDROID = "maps-android-backend.googleapis.com"
        private const val PLACES = "places.googleapis.com"
        private const val ROUTES = "routes.googleapis.com"
    }
}

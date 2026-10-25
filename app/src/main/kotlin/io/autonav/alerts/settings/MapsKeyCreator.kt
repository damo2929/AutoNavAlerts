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
 * Creates the user's own Maps API key in their own Google Cloud account (DESIGN.md §13.1): project → billing →
 * APIs → restricted key, through Google's REST APIs over OkHttp. Every step is safe to repeat, so "Try again"
 * after an error carries on from where it stopped. A Hilt singleton; [create] runs on Dispatchers.IO and is
 * called by the phone's **Create a key with my Google account** button (`MainActivity`), which first gets a
 * short-lived access token from Google Identity Services and passes it in; the token is only held for one run
 * and never stored. The result is saved like a pasted key (`SettingsStore.setMapsApiKey`).
 * Upholds hard rule 5 (AGENTS.md §3.1, §1.4): the key is made in the user's own project and billed to their
 * own account; nothing here or anywhere else supplies a built-in or fallback key.
 * The console links in the companion serve the guided set-up (§13.2) when the app isn't registered for sign-in.
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

    /** An open billing account: [name] is the API resource name ("billingAccounts/…"), [displayName] what the user sees. */
    data class BillingAccount(val name: String, val displayName: String)
    /** The key string and the project it lives in, for "✓ Key created in project <id>" (§13.1 step 5). */
    data class Created(val key: String, val projectId: String)

    // One client for the run; 60 s covers Google's slower long-running operation polls.
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    /**
     * The SHA-1 of the certificate this installed APK is signed with, as "AB:CD:…" (§13.1 step 4: the key is
     * restricted to this package and this SHA-1, so a debug and a release build each get a key that works
     * for them; §13.3). Also shown with a Copy button in the guided set-up (§13.2 step 4).
     */
    fun signingSha1(): String {
        val pm = context.packageManager
        val cert = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: error("No signing information for this app")
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

        // §13.1 step 1: reuse the project labelled autonavalerts=maps-key, or create one.
        progress("Finding or creating your AutoNavAlerts project…")
        val project = findProject(api) ?: createProject(api)
        val projectId = project.getString("projectId")
        // Service Usage and API Keys address projects by number, Resource Manager and Billing by id.
        val projectNumber = project.getString("name").removePrefix("projects/")

        // §13.1 step 2: Maps Platform needs a billing account on the project.
        progress("Checking billing…")
        ensureBilling(api, projectId, billingAccount)

        // §13.1 step 3, plus the Routes API for energy-efficient routes (§8.3a "Key and billing").
        progress("Turning on the Navigation SDK, Maps SDK for Android, Places API (New) and Routes API…")
        val services = listOf(navigationService(api, projectNumber), "maps-android-backend.googleapis.com", "places.googleapis.com", "routes.googleapis.com")
        enableServices(api, projectNumber, projectId, services)

        // §13.1 step 4: reuse the key named for this package and SHA-1 (adding any API it lacks), else create it.
        progress("Creating a key only this app can use…")
        val keyName = findKey(api, projectNumber)?.also { allowServices(api, it, services) } ?: createKey(api, projectNumber, services)
        val key = api.send("GET", "https://apikeys.googleapis.com/v2/$keyName/keyString").getString("keyString")
        Created(key, projectId)
    }

    // --- Project ---------------------------------------------------------------------------------

    /** The first ACTIVE project carrying our label (§13.1 "Find project"), or null. */
    private fun findProject(api: Api): JSONObject? {
        val r = api.send("GET", "$CRM/projects:search?query=" + java.net.URLEncoder.encode("labels.$LABEL_KEY:$LABEL_VALUE", "UTF-8"))
        val list = r.optJSONArray("projects") ?: return null
        return list.objects().firstOrNull { it.optString("state") == "ACTIVE" }
    }

    /** Creates `autonavalerts-<6 random chars>` named "AutoNavAlerts" with the label, waits for the operation, then reads it back (§13.1 "Create project"). */
    private suspend fun createProject(api: Api): JSONObject {
        // Project ids are global across Google Cloud, so a random suffix avoids a clash with anyone else's.
        val id = "autonavalerts-" + (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val body = JSONObject().put("projectId", id).put("displayName", "AutoNavAlerts").put("labels", JSONObject().put(LABEL_KEY, LABEL_VALUE))
        val op = api.send("POST", "$CRM/projects", body)
        api.waitFor("$CRM/${op.getString("name")}")
        return api.send("GET", "$CRM/projects/$id")
    }

    // --- Billing ---------------------------------------------------------------------------------

    /**
     * §13.1 step 2: carry on if billing is already on; else link [chosen], or the only open account; several →
     * [Stop.ChooseBilling]; none → [Stop.NoBilling] (accounts can't be created by API, they need a payment method).
     */
    private fun ensureBilling(api: Api, projectId: String, chosen: String?) {
        val info = api.send("GET", "$BILLING/projects/$projectId/billingInfo")
        if (info.optBoolean("billingEnabled")) return
        val account = chosen ?: run {
            val list = api.send("GET", "$BILLING/billingAccounts").optJSONArray("billingAccounts") ?: JSONArray()
            val open = list.objects().filter { it.optBoolean("open") }
                .map { BillingAccount(it.getString("name"), it.optString("displayName", it.getString("name"))) }
            when (open.size) {
                0 -> throw Stop.NoBilling()
                1 -> open.single().name
                else -> throw Stop.ChooseBilling(open)
            }
        }
        api.send("PUT", "$BILLING/projects/$projectId/billingInfo", JSONObject().put("billingAccountName", account))
    }

    // --- APIs ------------------------------------------------------------------------------------

    /** The Navigation SDK is `navigationsdk.googleapis.com` (checked 2026-10-12); if that ever changes, search by title. */
    private fun navigationService(api: Api, projectNumber: String): String {
        runCatching { api.send("GET", "$SERVICE_USAGE/projects/$projectNumber/services/$NAVIGATION_GUESS") }
            .onSuccess { return NAVIGATION_GUESS }
        var page: String? = null
        do {
            val r = api.send("GET", "$SERVICE_USAGE/projects/$projectNumber/services?pageSize=200" + (page?.let { "&pageToken=$it" } ?: ""))
            for (s in (r.optJSONArray("services") ?: JSONArray()).objects()) {
                if (s.optJSONObject("config")?.optString("title").equals("Navigation SDK", ignoreCase = true)) {
                    return s.getJSONObject("config").getString("name")
                }
            }
            page = r.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        throw Stop.Refused("The Navigation SDK isn't available to this Google account yet. Open it in Google Cloud to request or enable it.",
            "https://console.cloud.google.com/apis/library?q=Navigation%20SDK")
    }

    /** `services:batchEnable` for the four APIs, waiting for the long-running operation (§13.1 "Enable APIs"). */
    private suspend fun enableServices(api: Api, projectNumber: String, projectId: String, services: List<String>) {
        try {
            val op = api.send("POST", "$SERVICE_USAGE/projects/$projectNumber/services:batchEnable", JSONObject().put("serviceIds", JSONArray(services)))
            api.finish(op, SERVICE_USAGE)
        } catch (e: Stop.Refused) {
            // Usually terms to accept or access to request: send the user to the project's API library.
            throw Stop.Refused(e.message.orEmpty(), "https://console.cloud.google.com/apis/library?project=$projectId")
        }
    }

    // --- Key -------------------------------------------------------------------------------------

    /** "AutoNavAlerts · <package> · <first 11 characters of the SHA-1>": how a key is recognised on a repeat run (§13.1 step 4). */
    private val keyDisplayName get() = "AutoNavAlerts · ${context.packageName} · ${signingSha1().take(11)}"

    /** An existing key with our display name, skipping deleted ones (they linger with a `deleteTime`), or null. */
    private fun findKey(api: Api, projectNumber: String): String? {
        val list = api.send("GET", "$API_KEYS/projects/$projectNumber/locations/global/keys").optJSONArray("keys") ?: return null
        return list.objects().firstOrNull { it.optString("displayName") == keyDisplayName && !it.has("deleteTime") }?.getString("name")
    }

    /**
     * Creates the key restricted to this Android app (package + SHA-1) and to [services] only (§13.1 step 4),
     * waits for the operation, and returns the key's resource name (the key string is read separately).
     */
    private suspend fun createKey(api: Api, projectNumber: String, services: List<String>): String {
        val app = JSONObject().put("packageName", context.packageName).put("sha1Fingerprint", signingSha1())
        val restrictions = JSONObject()
            .put("androidKeyRestrictions", JSONObject().put("allowedApplications", JSONArray().put(app)))
            .put("apiTargets", JSONArray(services.map { JSONObject().put("service", it) }))
        val body = JSONObject().put("displayName", keyDisplayName).put("restrictions", restrictions)
        val op = api.send("POST", "$API_KEYS/projects/$projectNumber/locations/global/keys", body)
        return api.finish(op, API_KEYS).getJSONObject("response").getString("name")
    }

    /** An existing key gets any API it lacks (e.g. the Routes API added in 2026-10). */
    private suspend fun allowServices(api: Api, keyName: String, services: List<String>) {
        val key = api.send("GET", "$API_KEYS/$keyName")
        val restrictions = key.optJSONObject("restrictions") ?: JSONObject()
        val targets = restrictions.optJSONArray("apiTargets") ?: JSONArray()
        val have = targets.objects().map { it.optString("service") }.toSet()
        val missing = services.filter { it !in have }
        if (missing.isEmpty()) return
        missing.forEach { targets.put(JSONObject().put("service", it)) }
        restrictions.put("apiTargets", targets)
        val op = api.send("PATCH", "$API_KEYS/$keyName?updateMask=restrictions", JSONObject().put("restrictions", restrictions))
        api.finish(op, API_KEYS)
    }

    // --- HTTP ------------------------------------------------------------------------------------

    /** The objects of a JSON array, in order. */
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    /** The REST calls with `Authorization: Bearer <token>`; every reply is parsed as JSON and every error becomes a [Stop.Refused]. */
    private inner class Api(private val token: String) {
        /** The finished operation: [op] itself when it came back done, else polled at `<base>/<op name>` with [waitFor]. */
        suspend fun finish(op: JSONObject, base: String): JSONObject = if (op.optBoolean("done")) op else waitFor("$base/${op.getString("name")}")

        /** Polls a long-running operation every 2 s, up to 60 times (about 2 minutes, §13.1); an operation that finishes with an error stops the run with its message. */
        suspend fun waitFor(opUrl: String): JSONObject {
            repeat(60) {
                val op = send("GET", opUrl)
                if (op.optBoolean("done")) {
                    op.optJSONObject("error")?.let { throw Stop.Refused(it.optString("message", "Google Cloud reported an error"), null) }
                    return op
                }
                delay(2_000)
            }
            throw Stop.Refused("Google Cloud is taking too long. Press Try again in a minute.", null)
        }

        /** Runs one request ([body] as JSON for POST, PUT and PATCH); a non-2xx reply becomes [Stop.Refused] with Google's own message and, when its details carry one, a help link (§13.1 "Error messages"). */
        fun send(method: String, url: String, body: JSONObject? = null): JSONObject {
            val request = Request.Builder().url(url).method(method, body?.toString()?.toRequestBody(json)).header("Authorization", "Bearer $token").build()
            http.newCall(request).execute().use { r ->
                val text = r.body.string()
                if (r.isSuccessful) return if (text.isBlank()) JSONObject() else JSONObject(text)
                val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                val message = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP ${r.code}"
                throw Stop.Refused(message, helpUrl(err))
            }
        }
    }

    companion object {
        /** The one OAuth scope asked for, on its own (§13.1 "Sign-in flow"): Google treats it as sensitive, so the app stays in Testing until verified. */
        const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        /** Opened by the "no billing account" stop (§13.1 step 2) and by the guided set-up's credentials step (§13.2 step 4). */
        const val BILLING_CREATE_URL = "https://console.cloud.google.com/billing/create"
        const val CREDENTIALS_URL = "https://console.cloud.google.com/apis/credentials"

        /** Console pages for the guided set-up (DESIGN.md §13.2); each applies to the project last opened. */
        const val CONSOLE_PROJECT_CREATE = "https://console.cloud.google.com/projectcreate"
        const val CONSOLE_BILLING = "https://console.cloud.google.com/billing/linkedaccount"
        const val CONSOLE_NAVIGATION = "https://console.cloud.google.com/apis/library/navigationsdk.googleapis.com"
        const val CONSOLE_MAPS_ANDROID = "https://console.cloud.google.com/apis/library/maps-android-backend.googleapis.com"
        const val CONSOLE_PLACES = "https://console.cloud.google.com/apis/library/places.googleapis.com"
        const val CONSOLE_ROUTES = "https://console.cloud.google.com/apis/library/routes.googleapis.com"

        /** Google's error details often carry a link to the page that fixes the problem. Internal for `MapsKeyCreatorTest`. */
        internal fun helpUrl(err: JSONObject?): String? {
            val details = err?.optJSONArray("details") ?: return null
            // The first detail with any links decides, as the loop it replaces did.
            val links = (0 until details.length()).map { details.getJSONObject(it) }
                .firstNotNullOfOrNull { d -> d.optJSONArray("links")?.takeIf { it.length() > 0 } } ?: return null
            return links.getJSONObject(0).optString("url").ifBlank { null }
        }

        /** A Google API key: "AIza" then 35 letters, digits, '-' or '_'. The check behind "Paste key from clipboard" (§13.2 step 5). */
        fun looksLikeKey(text: String) = Regex("AIza[0-9A-Za-z_-]{35}").matches(text.trim())
        // The four Google Cloud APIs the run uses (§13.1 "The calls"); they must also be enabled in the maintainer's project that owns the OAuth client (§13.3).
        private const val CRM = "https://cloudresourcemanager.googleapis.com/v3"
        private const val BILLING = "https://cloudbilling.googleapis.com/v1"
        private const val SERVICE_USAGE = "https://serviceusage.googleapis.com/v1"
        private const val API_KEYS = "https://apikeys.googleapis.com/v2"
        // The project label that marks a project as ours, so a repeat run reuses it (§13.1 step 1).
        private const val LABEL_KEY = "autonavalerts"
        private const val LABEL_VALUE = "maps-key"
        // The Navigation SDK service, the first of the four to enable and restrict the key to (§13.1 step 3; the rest are listed in [create]).
        private const val NAVIGATION_GUESS = "navigationsdk.googleapis.com"
    }
}

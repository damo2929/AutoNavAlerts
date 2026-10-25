// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.pgw

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.BuildConfig
import io.autonav.alerts.core.pgw.PgwConfig
import io.autonav.alerts.core.pgw.PgwDownload
import io.autonav.alerts.core.pgw.PgwPages
import io.autonav.alerts.core.pgw.PgwRelease
import io.autonav.alerts.core.pgw.PgwSubmission
import io.autonav.alerts.core.pgw.PgwUpdateFeed
import io.autonav.alerts.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// The three failure kinds of DESIGN.md §6.3, so the sync worker can act on each: wrong or missing login
// (AUTH_FAILED), logged in but no download offered (SUBSCRIPTION_REQUIRED), the site's pages changed (PARSE_FAILED).
/** Wrong credentials, no credentials saved, or the session was lost mid-way (§6.3 "Wrong credentials"). */
class PgwAuthException(msg: String) : IOException(msg)
/** Logged in, but the download page has no package link: the subscription has lapsed (§6.2a "Expired subscription"). */
class PgwSubscriptionException(msg: String) : IOException(msg)
/** A page or response wasn't what `PgwPages` expects: the site changed, or the download wasn't a zip (§6.3). */
class PgwParseException(msg: String) : IOException(msg)

/** What a feed check returned (§6.2): a 304 with nothing new, or the parsed releases with the validators to send next time. */
sealed interface FeedResult {
    data object NotModified : FeedResult
    /** [newest] is the highest (major, minor) release, or null for an empty feed; [etag] and [lastModified] feed the next conditional GET. */
    data class Releases(val newest: PgwRelease?, val etag: String?, val lastModified: String?) : FeedResult
}

/**
 * PocketGPSWorld access with the user's own subscription (DESIGN.md §6.1, §6.2a). A Hilt singleton; every
 * network call runs on Dispatchers.IO. Called by `PgwFeedCheckWorker` (the feed), `PgwSyncWorker` (login and
 * download), `ReportSubmitWorker` (report submission, §8.5) and the phone's PGW account screen (test login,
 * forget session). Only ever talks to www.pocketgpsworld.com over HTTPS: the interceptor refuses any other
 * host, so a redirect can't carry the session elsewhere. Credentials and cookies are stored encrypted
 * (`SettingsStore`, §6.1 step 1). The URLs, form fields and page checks live in `PgwConfig` and `PgwPages`
 * in `:core` (§6.1 "All URLs, form field names and selectors").
 * Hard rule 6 (AGENTS.md §3.1): there is no built-in or default account; the data downloaded is the user's
 * own watermarked copy and never leaves the device (§6.4).
 */
@Singleton
class PgwClient internal constructor(
    private val context: Context,
    private val settings: SettingsStore,
    /** The site's root and the feed's URL: always `PgwConfig`'s in the app; a JVM test points them at a local server (§15.2). */
    private val base: String,
    private val rssUrl: String,
) {
    @Inject constructor(@ApplicationContext context: Context, settings: SettingsStore) : this(context, settings, PgwConfig.BASE, PgwConfig.RSS_URL)

    // The session cookies by name, in memory for this process; persisted encrypted by saveCookies() (§6.1 step 2.1).
    private val cookies = HashMap<String, Cookie>()

    /** OkHttp's view of [cookies]; expired ones are left out on the way back in. */
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
            synchronized(this@PgwClient.cookies) { cookies.forEach { this@PgwClient.cookies[it.name] = it } }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(cookies) { cookies.values.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() } }
    }

    // §6.1 step 2: an in-memory cookie jar, a 30 s connect timeout (60 s to read a slow page) and a User-Agent naming the app.
    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            // Credentials and cookies go to PGW's own host only (§6.2a "only sent to www.pocketgpsworld.com over HTTPS").
            // [base]'s host is www.pocketgpsworld.com itself in the app, so this adds no host there.
            require(req.url.host == "www.pocketgpsworld.com" || req.url.host == "pocketgpsworld.com" || req.url.host == base.toHttpUrl().host) { "Unexpected host" }
            chain.proceed(req.newBuilder().header("User-Agent", "AutoNavAlerts/${BuildConfig.VERSION_NAME} (Android)").build())
        }
        .build()

    /**
     * Daily feed check with a conditional GET (DESIGN.md §6.2): `If-None-Match` [etag] and `If-Modified-Since`
     * [lastModified] from the last check, so most checks are a 304 and cost nothing. No login needed: the
     * feed is public and carries no camera data (§6.4). Throws IOException on a non-2xx reply.
     */
    suspend fun checkFeed(etag: String?, lastModified: String?): FeedResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(rssUrl).apply {
            etag?.let { header("If-None-Match", it) }
            lastModified?.let { header("If-Modified-Since", it) }
        }.build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 304) return@withContext FeedResult.NotModified
            if (!resp.isSuccessful) throw IOException("Feed HTTP ${resp.code}")
            val releases = PgwUpdateFeed.parse(resp.body.byteStream())
            FeedResult.Releases(PgwUpdateFeed.newest(releases), resp.header("ETag"), resp.header("Last-Modified"))
        }
    }

    /**
     * Logs in once if the saved session is no longer valid (DESIGN.md §6.2a step 1): the saved cookies are
     * tried against the Cameras page first, and only a page that shows the login form triggers the plain form
     * POST of §6.1 step 2.1 (no CSRF token, no captcha). Throws [PgwAuthException] with no saved login or a
     * rejected one. A successful login persists the cookies so the next sync can skip this.
     */
    suspend fun ensureLoggedIn() = withContext(Dispatchers.IO) {
        restoreCookies()
        val page = get(base + PgwConfig.CAMERAS_PATH)
        if (PgwPages.loginState(page) == PgwPages.LoginState.LOGGED_IN) return@withContext
        val (user, pass) = settings.pgwCredentials() ?: throw PgwAuthException("No PocketGPSWorld login saved")
        val html = post(base + PgwConfig.LOGIN_PATH, formBody(PgwConfig.loginForm(user, pass))).use { it.body.string() }
        // "Logged in" means a session cookie and a page with logout / My Account and no password field (§6.1 step 2.1).
        if (PgwPages.loginState(html) != PgwPages.LoginState.LOGGED_IN) throw PgwAuthException("Login failed: check your username and password")
        saveCookies()
    }

    /**
     * Requests the chosen package (iGO by type, or TomTom OV2 speed-zoned, §5.2 and §5.2a) and downloads the zip
     * to [dest] (DESIGN.md §6.1 steps 2.2–2.4): one POST that makes the site generate the user's watermarked zip
     * and returns its temp link, then one GET streamed to disk. Checks: the reply is a zip and over 50 KB. The
     * caller deletes the file after import (§6.1 "The zip is deleted after import"). Throws [PgwSubscriptionException]
     * when logged in but offered no link, [PgwAuthException] when the session was lost, [PgwParseException] for a bad reply.
     */
    suspend fun download(dest: File, pkg: PgwDownload): File = withContext(Dispatchers.IO) {
        ensureLoggedIn()
        // System and voicePack only fill the site's package list, but the browser sends them, so we do too (§6.1 step 2.2).
        val body = formBody(PgwConfig.downloadForm(pkg.idPackage), "System" to pkg.system, "voicePack" to "noVoice")
        val html = post(base + PgwConfig.CAMERAS_PATH, body).use { it.body.string() }
        // No link while logged in means no active subscription (§6.2a "Expired subscription"); logged out means the session died.
        // The link is absolute on PgwConfig.BASE; re-rooted on [base], which is the same string in the app.
        val link = PgwPages.findZipLink(html)?.replaceFirst(PgwConfig.BASE, base).also { Log.i(TAG, "Download page: ${html.length} chars, zip link: $it") }
            ?: if (PgwPages.loginState(html) == PgwPages.LoginState.LOGGED_IN) throw PgwSubscriptionException("No download link: is your subscription active?")
            else throw PgwAuthException("Logged out while downloading")
        // The temp link needs no cookie, but sending it is harmless and matches the browser.
        http.newCall(Request.Builder().url(link).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download HTTP ${resp.code}")
            val type = resp.header("Content-Type").orEmpty()
            Log.i(TAG, "Zip response: HTTP ${resp.code}, type=$type, length=${resp.header("Content-Length")}")
            if (!type.contains("zip")) throw PgwParseException("Download wasn't a zip ($type)")
            dest.parentFile?.mkdirs()
            // Streamed straight to disk: the zip is never held in memory (§1.3).
            dest.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
        }
        // §6.1 step 2.4: a real package is a few hundred KB; a tiny file is an error page saved as a zip.
        if (dest.length() < 50_000) throw PgwParseException("Download too small (${dest.length()} bytes)")
        dest
    }

    /**
     * Sends one camera report through PGW's "Send Entry" form (DESIGN.md §8.5 `PgwReportSubmitter`). Only ever
     * reached from `ReportSubmitWorker`, which the phone's Send button queues and which refuses to run while
     * driving (hard rule 8, AGENTS.md §3.1; there is no auto-send). Returns the response HTML for the caller's
     * success check. Throws [PgwParseException] when the form's hidden fields can't be found (the site changed).
     */
    suspend fun submit(s: PgwSubmission): String = withContext(Dispatchers.IO) {
        ensureLoggedIn()
        // The per-user hidden fields (POISubmitted, email, userid, Country, MAX_FILE_SIZE) are read from the
        // page each time and sent back as they are: never hard-coded, never logged (§8.5 step 1).
        val form = get(base + PgwConfig.SUBMIT_PATH)
        val hidden = PgwPages.submitHiddenFields(form)
        if (hidden.isEmpty()) throw PgwParseException("Submission form not found")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            hidden.forEach { (k, v) -> addFormDataPart(k, v) }
            s.formFields().forEach { (k, v) -> addFormDataPart(k, v) }
        }.build()
        post(base + PgwConfig.CAMERAS_PATH, body).use { resp ->
            if (!resp.isSuccessful) throw IOException("Submit HTTP ${resp.code}")
            resp.body.string()
        }
    }

    /** A plain GET returning the page text; blocking, so only called inside `withContext(Dispatchers.IO)`. */
    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

    /** A POST of [body] to [url], executed; the caller reads and closes the response. Blocking, like [get]. */
    private fun post(url: String, body: RequestBody): Response = http.newCall(Request.Builder().url(url).post(body).build()).execute()

    /** The site's form [fields] (from `PgwConfig`) and any [extra] ones, in that order, as a URL-encoded body. */
    private fun formBody(fields: List<Pair<String, String>>, vararg extra: Pair<String, String>): FormBody =
        FormBody.Builder().apply { (fields + extra).forEach { (k, v) -> add(k, v) } }.build()

    /** Persists the session, one Set-Cookie line per cookie, encrypted (§6.1 step 2.1 "persisted in encrypted storage"). */
    private suspend fun saveCookies() {
        val s = synchronized(cookies) { cookies.values.joinToString("\n") { it.toString() } }
        settings.setPgwCookies(s)
    }

    /** Loads the persisted session into memory once per process; a non-empty jar is left alone. */
    private suspend fun restoreCookies() {
        if (synchronized(cookies) { cookies.isNotEmpty() }) return
        val url = base.toHttpUrl()
        settings.pgwCookies()?.lineSequence()?.mapNotNull { Cookie.parse(url, it) }?.forEach { cookies[it.name] = it }
    }

    /** Drops the session in memory and on disk: the phone calls it when the login is changed or removed, so the next sync logs in afresh (§13.4 "PGW login"). */
    suspend fun forgetSession() {
        synchronized(cookies) { cookies.clear() }
        settings.setPgwCookies(null)
    }

    /** `cacheDir/pgw/`, where a download lands until it is imported (§6.1 step 2.4). */
    fun cacheDir(): File = File(context.cacheDir, "pgw")

    companion object { private const val TAG = "PgwClient" }
}

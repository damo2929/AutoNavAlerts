// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.pgw

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.BuildConfig
import io.autonav.alerts.core.pgw.PgwConfig
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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class PgwAuthException(msg: String) : IOException(msg)
class PgwSubscriptionException(msg: String) : IOException(msg)
class PgwParseException(msg: String) : IOException(msg)

sealed interface FeedResult {
    data object NotModified : FeedResult
    data class Releases(val newest: PgwRelease?, val etag: String?, val lastModified: String?) : FeedResult
}

/**
 * PocketGPSWorld access with the user's own subscription (DESIGN.md §6). Only ever talks to
 * www.pocketgpsworld.com over HTTPS. Credentials and cookies are stored encrypted.
 */
@Singleton
class PgwClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    private val cookies = HashMap<String, Cookie>()

    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(this@PgwClient.cookies) { cookies.forEach { this@PgwClient.cookies[it.name] = it } }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(cookies) { cookies.values.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() } }
    }

    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            require(req.url.host == "www.pocketgpsworld.com" || req.url.host == "pocketgpsworld.com") { "Unexpected host" }
            chain.proceed(req.newBuilder().header("User-Agent", "AutoNavAlerts/${BuildConfig.VERSION_NAME} (Android)").build())
        }
        .build()

    /** Daily feed check with a conditional GET (DESIGN.md §6.2). No login needed. */
    suspend fun checkFeed(etag: String?, lastModified: String?): FeedResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(PgwConfig.RSS_URL).apply {
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

    /** Logs in once if the saved session is no longer valid. */
    suspend fun ensureLoggedIn() = withContext(Dispatchers.IO) {
        restoreCookies()
        val page = get(PgwConfig.BASE + PgwConfig.CAMERAS_PATH)
        if (PgwPages.loginState(page) == PgwPages.LoginState.LOGGED_IN) return@withContext
        val (user, pass) = settings.pgwCredentials() ?: throw PgwAuthException("No PocketGPSWorld login saved")
        val body = FormBody.Builder().apply { PgwConfig.loginForm(user, pass).forEach { (k, v) -> add(k, v) } }.build()
        val html = http.newCall(Request.Builder().url(PgwConfig.BASE + PgwConfig.LOGIN_PATH).post(body).build()).execute().use { it.body.string() }
        if (PgwPages.loginState(html) != PgwPages.LoginState.LOGGED_IN) throw PgwAuthException("Login failed: check your username and password")
        saveCookies()
    }

    /** Requests the iGO package and downloads the zip (DESIGN.md §6.1). */
    suspend fun downloadIgo(dest: File): File = withContext(Dispatchers.IO) {
        ensureLoggedIn()
        val body = FormBody.Builder().apply { PgwConfig.downloadForm().forEach { (k, v) -> add(k, v) } }
            .add("System", "iGO").add("voicePack", "noVoice").build()
        val html = http.newCall(Request.Builder().url(PgwConfig.BASE + PgwConfig.CAMERAS_PATH).post(body).build()).execute().use { it.body.string() }
        val link = PgwPages.findZipLink(html)
            ?: if (PgwPages.loginState(html) == PgwPages.LoginState.LOGGED_IN) throw PgwSubscriptionException("No download link: is your subscription active?")
            else throw PgwAuthException("Logged out while downloading")
        // The temp link needs no cookie, but sending it is harmless and matches the browser.
        http.newCall(Request.Builder().url(link).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download HTTP ${resp.code}")
            val type = resp.header("Content-Type").orEmpty()
            if (!type.contains("zip")) throw PgwParseException("Download wasn't a zip ($type)")
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
        }
        if (dest.length() < 50_000) throw PgwParseException("Download too small (${dest.length()} bytes)")
        dest
    }

    /**
     * Sends one camera report through PGW's "Send Entry" form (DESIGN.md §8.5). Only called when the
     * user presses Send on the phone, or has turned on auto-send. Returns the response text for checks.
     */
    suspend fun submit(s: PgwSubmission): String = withContext(Dispatchers.IO) {
        ensureLoggedIn()
        val form = get(PgwConfig.BASE + PgwConfig.SUBMIT_PATH)
        val hidden = PgwPages.submitHiddenFields(form)
        if (hidden.isEmpty()) throw PgwParseException("Submission form not found")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            hidden.forEach { (k, v) -> addFormDataPart(k, v) }
            s.formFields().forEach { (k, v) -> addFormDataPart(k, v) }
        }.build()
        http.newCall(Request.Builder().url(PgwConfig.BASE + PgwConfig.CAMERAS_PATH).post(body).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Submit HTTP ${resp.code}")
            resp.body.string()
        }
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

    private suspend fun saveCookies() {
        val s = synchronized(cookies) { cookies.values.joinToString("\n") { it.toString() } }
        settings.setPgwCookies(s)
    }

    private suspend fun restoreCookies() {
        if (synchronized(cookies) { cookies.isNotEmpty() }) return
        val url = PgwConfig.BASE.toHttpUrl()
        settings.pgwCookies()?.lineSequence()?.mapNotNull { Cookie.parse(url, it) }?.forEach { cookies[it.name] = it }
    }

    suspend fun forgetSession() {
        synchronized(cookies) { cookies.clear() }
        settings.setPgwCookies(null)
    }

    fun cacheDir(): File = File(context.cacheDir, "pgw")
}
